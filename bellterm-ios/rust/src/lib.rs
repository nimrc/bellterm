//! The Swift surface of Bellterm's core, mirroring Android's
//! `app.bellterm.core.Native`. Sessions are UniFFI objects, so Swift's ARC
//! owns their lifetime instead of an ID registry; strings are parsed into
//! domain types here and nowhere else.
//!
//! Input calls (keys, text, touches, resizes) log and drop failures, as the
//! JNI layer does: a keystroke is not worth an exception.

use bellterm::{
    config,
    input::Touch,
    keys,
    session::{self, Session as Core},
    ssh_config,
    theme::Theme,
};
use std::{
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};
use zeroize::Zeroizing;

uniffi::setup_scaffolding!();

/// Named apart from `Error` so the Swift binding does not shadow `Swift.Error`.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{message}")]
    Failed { message: String },
}

impl From<bellterm::Error> for CoreError {
    fn from(error: bellterm::Error) -> Self {
        Self::Failed {
            message: error.to_string(),
        }
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(error: serde_json::Error) -> Self {
        bellterm::Error::from(error).into()
    }
}

pub type Result<T> = std::result::Result<T, CoreError>;

pub const TOUCH_TAP: i32 = 0;
pub const TOUCH_SCROLL_UP: i32 = 1;
pub const TOUCH_SCROLL_DOWN: i32 = 2;

fn u16_of(value: i32) -> u16 {
    u16::try_from(value.max(0)).unwrap_or(u16::MAX)
}

fn u32_of(value: i32) -> u32 {
    u32::try_from(value.max(0)).unwrap_or(0)
}

fn dropped(result: bellterm::Result<()>) {
    if let Err(error) = result {
        tracing::debug!(%error, "input not sent");
    }
}

#[uniffi::export]
pub fn init_logging() {
    #[cfg(target_os = "ios")]
    {
        use tracing_subscriber::layer::SubscriberExt as _;
        let subscriber = tracing_subscriber::registry()
            .with(tracing_oslog::OsLogger::new("app.bellterm", "core"));
        let _ = tracing::subscriber::set_global_default(subscriber);
    }
}

/// A new Ed25519 identity, as OpenSSH private key text.
#[uniffi::export]
pub fn generate_key(comment: String) -> Result<String> {
    Ok(keys::generate(&comment)?.private.to_string())
}

/// The `authorized_keys` line for a stored private key.
#[uniffi::export]
pub fn public_key(private_key: String) -> Result<String> {
    let private = Zeroizing::new(private_key);
    Ok(keys::public_of(&private)?)
}

/// `{"private": …, "info": KeyInfo}`, the private key decrypted for storage.
#[uniffi::export]
pub fn import_key(text: String, passphrase: Option<String>) -> Result<String> {
    let text = Zeroizing::new(text);
    let passphrase = passphrase.map(Zeroizing::new);
    let (private, info) = keys::import(&text, passphrase.as_deref().map(String::as_str))?;
    Ok(serde_json::json!({"private": private.as_str(), "info": info}).to_string())
}

#[uniffi::export]
pub fn certificate_info(certificate: String, private_key: String) -> Result<String> {
    let private = Zeroizing::new(private_key);
    Ok(serde_json::to_string(&keys::certificate(
        &certificate,
        &private,
    )?)?)
}

/// `{"entries": […], "skipped": […]}` from OpenSSH client configuration text.
#[uniffi::export]
pub fn parse_ssh_config(text: String) -> Result<String> {
    Ok(serde_json::to_string(&ssh_config::parse(&text))?)
}

/// One attachment to a daemon. Every method is cheap and non-blocking except
/// [`Session::next_event`], which waits up to its timeout.
#[derive(uniffi::Object)]
pub struct Session {
    core: Core,
    closed: AtomicBool,
}

#[uniffi::export]
impl Session {
    /// Starts connecting with a route document (see `native/src/config.rs`).
    /// The document holds secrets: it is parsed into zeroizing types and not
    /// kept.
    #[uniffi::constructor]
    pub fn connect(config_json: String) -> Result<Self> {
        let json = Zeroizing::new(config_json);
        Ok(Self {
            core: Core::connect(config::parse(&json)?)?,
            closed: AtomicBool::new(false),
        })
    }

    /// The next event as JSON, or nil after `timeout_ms` without one.
    pub fn next_event(&self, timeout_ms: i32) -> Option<String> {
        if self.closed.load(Ordering::Acquire) {
            return Some(r#"{"type":"closed"}"#.to_owned());
        }
        let timeout = Duration::from_millis(u64::from(u32_of(timeout_ms)));
        self.core.next_event(timeout).map(|event| event.to_json())
    }

    pub fn take_frame(&self) -> Option<Vec<u8>> {
        self.core.take_frame().map(|frame| frame.pack())
    }

    pub fn take_view(&self) -> Option<String> {
        self.core.take_view()
    }

    /// True when the key was sent; false leaves it to text input. `key_code`
    /// and `meta` are Android `KeyEvent` values, which `input.rs` maps.
    pub fn key(
        &self,
        pane: Option<String>,
        key_code: i32,
        meta: i32,
        unicode: u32,
        repeat: bool,
    ) -> bool {
        let unicode = char::from_u32(unicode).filter(|c| *c != '\0');
        self.core
            .key(pane.as_deref(), key_code, meta, unicode, repeat)
            .unwrap_or_else(|error| {
                tracing::debug!(%error, "key not sent");
                false
            })
    }

    pub fn chord(&self, pane: Option<String>, codepoint: u32, meta: i32) {
        if let Some(ch) = char::from_u32(codepoint) {
            dropped(self.core.chord(pane.as_deref(), ch, meta));
        }
    }

    pub fn text(&self, pane: Option<String>, text: String) {
        dropped(self.core.text_input(pane.as_deref(), &text));
    }

    pub fn paste(&self, pane: Option<String>, text: String) {
        dropped(self.core.paste(pane.as_deref(), &text));
    }

    pub fn touch(&self, pane: String, kind: i32, column: i32, row: i32, lines: i32) {
        let kind = match kind {
            TOUCH_SCROLL_UP => Touch::ScrollUp,
            TOUCH_SCROLL_DOWN => Touch::ScrollDown,
            _ => Touch::Tap,
        };
        dropped(
            self.core
                .touch(&pane, kind, u16_of(column), u16_of(row), u16_of(lines)),
        );
    }

    pub fn resize(&self, cols: i32, rows: i32, cell_width: i32, cell_height: i32) {
        dropped(self.core.resize(session::options(
            u16_of(cols),
            u16_of(rows),
            u32_of(cell_width),
            u32_of(cell_height),
        )));
    }

    /// Invokes an endpoint method; returns the request ID.
    pub fn request(&self, method: String, params_json: String) -> Result<String> {
        let params = serde_json::from_str(&params_json)?;
        Ok(self.core.request(&method, params)?)
    }

    pub fn set_theme(&self, theme_json: String) {
        match serde_json::from_str::<Theme>(&theme_json) {
            Ok(theme) => self.core.set_theme(theme),
            Err(error) => tracing::warn!(%error, "theme not applied"),
        }
    }

    pub fn selection_text(
        &self,
        start_column: i32,
        start_row: i32,
        end_column: i32,
        end_row: i32,
    ) -> String {
        self.core.text(
            (u16_of(start_column), u16_of(start_row)),
            (u16_of(end_column), u16_of(end_row)),
        )
    }

    /// The worker stops on its own thread; nothing is joined here, so the
    /// main thread never waits on the network.
    pub fn disconnect(&self) {
        if !self.closed.swap(true, Ordering::AcqRel) {
            self.core.disconnect();
        }
    }
}
