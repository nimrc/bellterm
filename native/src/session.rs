//! One connection to a daemon, as the Kotlin UI sees it. herdr-client's
//! worker owns the socket; a `bellterm-screen` thread drains its events,
//! owns the libghostty screen, and publishes the latest frame and shell view
//! into mailboxes the UI takes from, so a slow view coalesces frames instead
//! of queueing them. Rarer events go through a small bounded queue.

use crate::{
    Error, Result, clipboard,
    input::{self, Modifiers, Touch},
    render::RenderFrame,
    screen::Screen,
    ssh::{self, Route},
    theme::Theme,
    view::ShellView,
};
use crossbeam_channel::{Receiver, Sender, bounded, never, select};
use herdr_client::{
    ClientEvent, ClientHandle, ConnectOptions, ConnectTarget, MIN_HERDR_VERSION, Method, Transport,
    VersionMismatch,
    protocol::{ClientPaneInputEvent, ClientSurfaceSize, PaneSurfaceFrame, ServerMessage},
};
use serde_json::{Value, json};
use std::{
    io,
    path::PathBuf,
    sync::{
        Arc, Mutex, PoisonError,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::Duration,
};

/// Rare events waiting for the UI; frames and snapshots never queue here.
const EVENT_CAPACITY: usize = 64;

#[derive(Debug)]
pub struct Config {
    pub route: Route,
    pub options: ConnectOptions,
    pub theme: Theme,
}

/// Something the UI should react to, as one JSON object.
#[derive(Debug, Clone, PartialEq)]
pub enum UiEvent {
    Connected {
        server_version: String,
    },
    /// A new shell view is waiting in the mailbox.
    Snapshot,
    /// A new frame is waiting in the mailbox.
    Frame,
    Response {
        request_id: String,
        response: Value,
    },
    Rejected {
        reason: String,
    },
    Notification {
        title: String,
        body: Option<String>,
        attention: bool,
        pane: Option<String>,
    },
    Clipboard {
        text: String,
    },
    Bell,
    /// Updating one side fixes this, not retrying: Herdr on the host when
    /// `daemon_outdated`, else this app.
    VersionMismatch {
        daemon_outdated: bool,
        server_version: Option<String>,
    },
    UntrustedHostKey {
        hop: usize,
        host: String,
        fingerprint: String,
        changed: bool,
    },
    /// Hop number `hop` refused the key or password it was given.
    AuthRejected {
        hop: usize,
        host: String,
    },
    Disconnected {
        reason: String,
    },
}

impl From<VersionMismatch> for UiEvent {
    fn from(mismatch: VersionMismatch) -> Self {
        let daemon_outdated = matches!(mismatch, VersionMismatch::DaemonOutdated { .. });
        Self::VersionMismatch {
            daemon_outdated,
            server_version: mismatch.server_version().map(str::to_owned),
        }
    }
}

/// The version mismatch behind a connector failure. herdr-client classifies
/// only its own errors, and the connector hands it this one as an opaque
/// `io::Error`, so it is recognised here before being wrapped.
/// The connector failures the UI answers with its own prompt, not a plain
/// disconnect: a host key to trust or a sign-in to retry.
fn connector_refusal(error: &Error) -> Option<UiEvent> {
    match error {
        Error::UntrustedHostKey {
            hop,
            host,
            fingerprint,
            changed,
        } => Some(UiEvent::UntrustedHostKey {
            hop: *hop,
            host: host.clone(),
            fingerprint: fingerprint.clone(),
            changed: *changed,
        }),
        Error::AuthRejected { hop, host } => Some(UiEvent::AuthRejected {
            hop: *hop,
            host: host.clone(),
        }),
        _ => None,
    }
}

fn connector_mismatch(error: &Error) -> Option<VersionMismatch> {
    match error {
        Error::Client(error) => error.version_mismatch(),
        _ => None,
    }
}

impl UiEvent {
    pub fn to_json(&self) -> String {
        match self {
            Self::Connected { server_version } => {
                json!({"type": "connected", "server_version": server_version})
            }
            Self::Snapshot => json!({"type": "snapshot"}),
            Self::Frame => json!({"type": "frame"}),
            Self::Response {
                request_id,
                response,
            } => {
                json!({"type": "response", "request_id": request_id, "response": response})
            }
            Self::Rejected { reason } => json!({"type": "rejected", "reason": reason}),
            Self::Notification {
                title,
                body,
                attention,
                pane,
            } => json!({
                "type": "notification", "title": title, "body": body,
                "attention": attention, "pane": pane,
            }),
            Self::Clipboard { text } => json!({"type": "clipboard", "text": text}),
            Self::Bell => json!({"type": "bell"}),
            Self::VersionMismatch {
                daemon_outdated,
                server_version,
            } => json!({
                "type": "version_mismatch",
                "outdated": if *daemon_outdated { "daemon" } else { "app" },
                "server_version": server_version,
                "min_version": MIN_HERDR_VERSION,
            }),
            Self::UntrustedHostKey {
                hop,
                host,
                fingerprint,
                changed,
            } => json!({
                "type": "host_key", "hop": hop, "host": host,
                "fingerprint": fingerprint, "changed": changed,
            }),
            Self::AuthRejected { hop, host } => {
                json!({"type": "auth_rejected", "hop": hop, "host": host})
            }
            Self::Disconnected { reason } => json!({"type": "disconnected", "reason": reason}),
        }
        .to_string()
    }
}

/// Where input goes: the open popup, else the given or focused pane.
#[derive(Debug, Default)]
struct Targets {
    popup: Option<String>,
    focused: Option<String>,
}

#[derive(Debug)]
struct Shared {
    events: Sender<UiEvent>,
    frame: Mutex<Option<Arc<RenderFrame>>>,
    frame_fresh: AtomicBool,
    view: Mutex<Option<String>>,
    view_fresh: AtomicBool,
    boot: Mutex<Option<String>>,
    targets: Mutex<Targets>,
    /// Set by the connector when it fails with a [`connector_refusal`];
    /// reported in place of the disconnect it causes.
    refusal: Mutex<Option<UiEvent>>,
    /// Set by the connector when the host's Herdr cannot serve this app;
    /// reported before the disconnect it causes.
    mismatch: Mutex<Option<VersionMismatch>>,
}

impl Shared {
    fn emit(&self, event: UiEvent) {
        if self
            .events
            .send_timeout(event, Duration::from_secs(1))
            .is_err()
        {
            tracing::warn!("ui event dropped");
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

enum Control {
    Theme(Theme),
}

pub struct Session {
    handle: ClientHandle,
    shared: Arc<Shared>,
    events: Receiver<UiEvent>,
    control: Sender<Control>,
}

impl std::fmt::Debug for Session {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Session").finish_non_exhaustive()
    }
}

impl std::fmt::Debug for Control {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Theme(theme) => write!(f, "Theme(dark: {})", theme.dark),
        }
    }
}

impl Session {
    /// Start connecting. Returns at once; progress and failure arrive as
    /// [`UiEvent`]s.
    pub fn connect(config: Config) -> Result<Self> {
        let (events_tx, events) = bounded(EVENT_CAPACITY);
        let shared = Arc::new(Shared {
            events: events_tx,
            frame: Mutex::default(),
            frame_fresh: AtomicBool::default(),
            view: Mutex::default(),
            view_fresh: AtomicBool::default(),
            boot: Mutex::default(),
            targets: Mutex::default(),
            refusal: Mutex::default(),
            mismatch: Mutex::default(),
        });
        let Config {
            route,
            options,
            theme,
        } = config;
        let connector_shared = shared.clone();
        // The connector, not the target, decides how to reach the daemon;
        // the socket path is never opened.
        let client = herdr_client::connect_with_connector(
            ConnectTarget::Socket(PathBuf::from("ssh")),
            options,
            true,
            move |_, stop| match ssh::open(route, stop) {
                Ok(stream) => Ok(Transport::Remote(stream)),
                Err(error) => {
                    if let Some(mismatch) = connector_mismatch(&error) {
                        *lock(&connector_shared.mismatch) = Some(mismatch);
                    }
                    if let Some(refusal) = connector_refusal(&error) {
                        *lock(&connector_shared.refusal) = Some(refusal);
                    }
                    Err(io::Error::other(error))
                }
            },
        )?;
        let (control, control_rx) = bounded(4);
        let screen_shared = shared.clone();
        let handle = client.handle.clone();
        thread::Builder::new()
            .name("bellterm-screen".into())
            .spawn(move || {
                Pump {
                    shared: screen_shared,
                    handle,
                    theme,
                    surface: None,
                    theme_sent: false,
                }
                .run(client.events, control_rx);
            })?;
        Ok(Self {
            handle: client.handle,
            shared,
            events,
            control,
        })
    }

    pub fn next_event(&self, timeout: Duration) -> Option<UiEvent> {
        self.events.recv_timeout(timeout).ok()
    }

    pub fn take_frame(&self) -> Option<Arc<RenderFrame>> {
        self.shared.frame_fresh.store(false, Ordering::Release);
        lock(&self.shared.frame).clone()
    }

    pub fn take_view(&self) -> Option<String> {
        self.shared.view_fresh.store(false, Ordering::Release);
        lock(&self.shared.view).clone()
    }

    /// Text of the last frame between two cells, for copying a selection.
    pub fn text(&self, start: (u16, u16), end: (u16, u16)) -> String {
        lock(&self.shared.frame)
            .as_ref()
            .map(|frame| frame.text(start, end))
            .unwrap_or_default()
    }

    fn boot(&self) -> Result<String> {
        lock(&self.shared.boot).clone().ok_or(Error::NotReady)
    }

    /// Send input to the open popup, else to `pane` or the focused pane.
    pub fn input(&self, pane: Option<&str>, events: Vec<ClientPaneInputEvent>) -> Result<()> {
        if events.is_empty() {
            return Ok(());
        }
        let boot = self.boot()?;
        let targets = lock(&self.shared.targets);
        if let Some(popup) = &targets.popup {
            return Ok(self.handle.send_popup_input(&boot, popup, events)?);
        }
        let pane = pane.or(targets.focused.as_deref()).ok_or(Error::NotReady)?;
        Ok(self.handle.send_input(&boot, pane, events)?)
    }

    pub fn key(
        &self,
        pane: Option<&str>,
        code: i32,
        meta: i32,
        unicode: Option<char>,
        repeat: bool,
    ) -> Result<bool> {
        let Some(event) = input::key(code, Modifiers::from_meta_state(meta), unicode, repeat)
        else {
            return Ok(false);
        };
        self.input(pane, vec![event])?;
        Ok(true)
    }

    pub fn chord(&self, pane: Option<&str>, ch: char, meta: i32) -> Result<()> {
        let events = input::chord(ch, Modifiers::from_meta_state(meta))
            .into_iter()
            .collect();
        self.input(pane, events)
    }

    pub fn text_input(&self, pane: Option<&str>, text: &str) -> Result<()> {
        self.input(pane, input::text(text))
    }

    pub fn paste(&self, pane: Option<&str>, text: &str) -> Result<()> {
        self.input(pane, vec![ClientPaneInputEvent::Paste(text.to_owned())])
    }

    pub fn touch(&self, pane: &str, kind: Touch, column: u16, row: u16, lines: u16) -> Result<()> {
        self.input(Some(pane), input::touch(kind, column, row, lines))
    }

    pub fn resize(&self, options: ConnectOptions) -> Result<()> {
        Ok(self.handle.resize(&self.boot()?, options)?)
    }

    pub fn set_theme(&self, theme: Theme) {
        let _ = self.control.try_send(Control::Theme(theme));
    }

    /// Invoke an endpoint method by its wire name; returns the request ID
    /// its response will carry.
    pub fn request(&self, method: &str, params: Value) -> Result<String> {
        Ok(self
            .handle
            .request(&self.boot()?, parse_method(method)?, params)?)
    }

    /// Have the host prove the link alive within a few seconds, as after the
    /// network changed under it; a dead link then ends as a disconnect.
    pub fn check_liveness(&self) {
        self.handle.check_liveness();
    }

    pub fn disconnect(&self) {
        self.handle.disconnect();
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.handle.disconnect();
    }
}

/// The endpoint methods the UI may invoke, parsed once at the JNI boundary.
pub fn parse_method(name: &str) -> Result<Method> {
    Ok(match name {
        // keep-sorted start
        "pane.close" => Method::PaneClose,
        "pane.focus" => Method::PaneFocus,
        "pane.rename" => Method::PaneRename,
        "pane.scroll" => Method::PaneScroll,
        "pane.split" => Method::PaneSplit,
        "pane.zoom" => Method::PaneZoom,
        "tab.close" => Method::TabClose,
        "tab.create" => Method::TabCreate,
        "tab.focus" => Method::TabFocus,
        "tab.rename" => Method::TabRename,
        "workspace.close" => Method::WorkspaceClose,
        "workspace.create" => Method::WorkspaceCreate,
        "workspace.focus" => Method::WorkspaceFocus,
        "workspace.rename" => Method::WorkspaceRename,
        // keep-sorted end
        _ => return Err(Error::UnknownMethod(name.to_owned())),
    })
}

/// The screen thread's state: everything libghostty touches stays here.
struct Pump {
    shared: Arc<Shared>,
    handle: ClientHandle,
    theme: Theme,
    surface: Option<Arc<PaneSurfaceFrame>>,
    theme_sent: bool,
}

impl Pump {
    fn run(mut self, events: Receiver<ClientEvent>, mut control: Receiver<Control>) {
        let mut screen = match Screen::new(self.theme) {
            Ok(screen) => screen,
            Err(error) => {
                self.handle.disconnect();
                self.shared.emit(UiEvent::Disconnected {
                    reason: error.to_string(),
                });
                return;
            }
        };
        loop {
            select! {
                recv(events) -> event => match event {
                    Ok(event) => self.event(&mut screen, event),
                    Err(_) => break,
                },
                recv(control) -> message => match message {
                    Ok(Control::Theme(theme)) => self.theme(&mut screen, theme),
                    // The session is gone; keep draining until the worker stops.
                    Err(_) => control = never(),
                },
            }
        }
    }

    fn theme(&mut self, screen: &mut Screen, theme: Theme) {
        if theme == screen.theme() {
            return;
        }
        self.theme = theme;
        if let Err(error) = screen.set_theme(theme) {
            tracing::warn!(%error, "theme not applied");
        }
        self.theme_sent = false;
        self.send_theme();
        if let Some(surface) = self.surface.clone() {
            self.show(screen, &surface);
        }
    }

    fn send_theme(&mut self) {
        if self.theme_sent {
            return;
        }
        let Some(boot) = lock(&self.shared.boot).clone() else {
            return;
        };
        self.theme_sent = self
            .handle
            .set_host_theme(&boot, &self.theme.host())
            .is_ok();
    }

    fn show(&mut self, screen: &mut Screen, surface: &PaneSurfaceFrame) {
        match screen.show(surface) {
            Ok(frame) => {
                {
                    let mut targets = lock(&self.shared.targets);
                    targets.popup = surface.popup.as_ref().map(|p| p.terminal_id.clone());
                    targets.focused = surface
                        .panes
                        .iter()
                        .find(|pane| pane.focused)
                        .map(|pane| pane.pane_id.clone())
                        .or(targets.focused.take());
                }
                *lock(&self.shared.frame) = Some(Arc::new(frame));
                if !self.shared.frame_fresh.swap(true, Ordering::AcqRel) {
                    self.shared.emit(UiEvent::Frame);
                }
            }
            Err(error) => tracing::warn!(%error, "surface not shown"),
        }
    }

    fn event(&mut self, screen: &mut Screen, event: ClientEvent) {
        match event {
            ClientEvent::Connected(welcome) => self.shared.emit(UiEvent::Connected {
                server_version: welcome.server_version.clone(),
            }),
            ClientEvent::Snapshot(snapshot) => {
                *lock(&self.shared.boot) = Some(snapshot.boot_id.clone());
                {
                    let mut targets = lock(&self.shared.targets);
                    if let Some(focused) = &snapshot.focused_pane_id {
                        targets.focused = Some(focused.clone());
                    }
                }
                self.send_theme();
                match serde_json::to_string(&ShellView::from(&*snapshot)) {
                    Ok(view) => {
                        *lock(&self.shared.view) = Some(view);
                        if !self.shared.view_fresh.swap(true, Ordering::AcqRel) {
                            self.shared.emit(UiEvent::Snapshot);
                        }
                    }
                    Err(error) => tracing::warn!(%error, "shell view not encoded"),
                }
            }
            ClientEvent::Surface(surface) => {
                self.show(screen, &surface);
                self.surface = Some(surface);
            }
            ClientEvent::SurfaceImages(_) => {}
            ClientEvent::Response {
                request_id,
                response,
            } => self.shared.emit(UiEvent::Response {
                request_id,
                response,
            }),
            ClientEvent::CommandRejected { reason, .. } => self.shared.emit(UiEvent::Rejected {
                reason: reason.to_string(),
            }),
            ClientEvent::Message(message) => self.message(message),
            ClientEvent::VersionMismatch(mismatch) => self.shared.emit(mismatch.into()),
            ClientEvent::Disconnected { reason, .. } => {
                if let Some(mismatch) = lock(&self.shared.mismatch).take() {
                    self.shared.emit(mismatch.into());
                }
                let refusal = lock(&self.shared.refusal).take();
                self.shared
                    .emit(refusal.unwrap_or(UiEvent::Disconnected { reason }));
            }
        }
    }

    fn message(&self, message: ServerMessage) {
        match message {
            ServerMessage::Clipboard { data } => {
                if let Some(text) = clipboard::decode(&data) {
                    self.shared.emit(UiEvent::Clipboard { text });
                }
            }
            ServerMessage::SemanticNotification(note) => {
                use herdr_client::protocol::SemanticNotificationKind as Kind;
                self.shared.emit(UiEvent::Notification {
                    title: note.title,
                    body: note.body,
                    attention: matches!(note.kind, Kind::NeedsAttention),
                    pane: note.pane_id,
                });
            }
            ServerMessage::Notify { message, body, .. } => {
                self.shared.emit(UiEvent::Notification {
                    title: message,
                    body,
                    attention: false,
                    pane: None,
                });
            }
            ServerMessage::TerminalBell { count } => {
                tracing::debug!(count, "terminal bell");
                self.shared.emit(UiEvent::Bell);
            }
            _ => {}
        }
    }
}

/// Connection geometry from the view's size in cells and pixels.
pub fn options(cols: u16, rows: u16, cell_width: u32, cell_height: u32) -> ConnectOptions {
    ConnectOptions {
        surface_size: ClientSurfaceSize {
            cols: cols.max(1),
            rows: rows.max(1),
        },
        cell_width_px: cell_width,
        cell_height_px: cell_height,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
