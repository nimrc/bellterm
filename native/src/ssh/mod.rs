//! SSH without an `ssh` executable, which Android does not have. An
//! in-process client (russh) walks a route of hops: it connects to the first,
//! opens a `direct-tcpip` channel through each jump host to the next, checks
//! every host key against the one the user approved, authenticates each hop,
//! and runs Herdr's remote bridge on the last. The bridge channel is pumped
//! through a Unix socket pair so herdr-client's blocking worker reads it like
//! the bridge it spawns on the desktop. The async runtime is confined to this
//! one thread and owns only the SSH sessions. [`Remote::run`] runs a command
//! over the same sessions and collects what it prints. A Windows OpenSSH host
//! cannot run the POSIX bridge, so [`open`] then probes it through PowerShell
//! and bridges the `herdr.exe` it finds over another channel.

mod auth;

use crate::{Error, Result};
pub use auth::Credential;
use herdr_client::{
    Stream, answer_remote_bridge, answer_windows_bridge, remote_bridge_command,
    windows_bridge_command, windows_probe_command,
};
use russh::{
    ChannelMsg, Disconnect,
    client::{self, Handle},
    keys::{HashAlg, PublicKey, PublicKeyOrCertificate},
};
use std::{
    os::unix::net::UnixStream,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant},
};

/// Connecting and authenticating every hop together may take this long.
const SETUP_TIMEOUT: Duration = Duration::from_secs(30);
const POLL: Duration = Duration::from_millis(100);

/// One SSH server on the way to Herdr: a jump host, or the host itself.
#[derive(Debug)]
pub struct Hop {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub credential: Credential,
    /// The host key fingerprint the user trusted, if any.
    pub pinned: Option<String>,
}

impl Hop {
    /// How the trust prompt and the known-hosts list name this server.
    pub fn address(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }
}

/// Jump hosts in order, then the host that runs Herdr.
#[derive(Debug)]
pub struct Route {
    pub hops: Vec<Hop>,
    pub session: String,
    /// SSH keepalive; `None` disables it.
    pub keepalive: Option<Duration>,
}

/// Open the bridge to the route's Herdr and answer its discovery. Blocks, so
/// it runs on herdr-client's I/O worker inside the connector; `stop` cancels.
pub fn open(route: Route, stop: &AtomicBool) -> Result<Stream> {
    let session = route.session.clone();
    let posix = remote_bridge_command(&session)?;
    let remote = Remote::connect(route, stop)?;
    match remote.bridge(posix, stop, answer_remote_bridge) {
        Err(Error::Client(herdr_client::Error::SshClosed)) => {}
        result => return result,
    }
    // Without a banner the host either has no Herdr, or is Windows OpenSSH,
    // whose `cmd.exe` or PowerShell cannot run the POSIX bridge.
    let probe = remote.run(
        windows_probe_command(&session)?,
        Collect {
            timeout: PROBE_TIMEOUT,
            limit: PROBE_LIMIT,
            stderr: false,
        },
        stop,
    )?;
    match windows_bridge_command(&probe.tail, &session) {
        None => Err(herdr_client::Error::SshClosed.into()),
        Some(command) => remote.bridge(command?, stop, answer_windows_bridge),
    }
}

/// The Windows probe asks each `herdr.exe` for its status, which can start a
/// cold PowerShell first.
const PROBE_TIMEOUT: Duration = Duration::from_secs(30);
const PROBE_LIMIT: usize = 64 * 1024;

/// What a command run with [`Remote::run`] left behind.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Output {
    /// `None` when the host closed the channel without reporting one.
    pub exit_status: Option<u32>,
    /// The last bytes of stdout, and of stderr when collected, interleaved as
    /// they arrived.
    pub tail: Vec<u8>,
}

/// How [`Remote::run`] collects a command's output.
#[derive(Debug, Clone, Copy)]
pub struct Collect {
    /// How long the command may run once its channel is open.
    pub timeout: Duration,
    /// How many of the last bytes to keep.
    pub limit: usize,
    /// Whether stderr is kept along with stdout.
    pub stderr: bool,
}

/// Every hop of a route, connected and signed in on one runtime thread that
/// opens a channel on the last for each request. Dropping it lets the thread
/// disconnect once every bridge it pumps has closed.
pub struct Remote {
    requests: tokio::sync::mpsc::UnboundedSender<Request>,
}

enum Request {
    Bridge {
        command: String,
        stream: UnixStream,
        opened: mpsc::SyncSender<Result<()>>,
    },
    Run {
        command: String,
        collect: Collect,
        done: mpsc::SyncSender<Result<Output>>,
    },
}

impl Remote {
    /// Connect and authenticate every hop. Blocks: call it from a background
    /// thread; `stop` cancels.
    pub fn connect(route: Route, stop: &AtomicBool) -> Result<Self> {
        if route.hops.is_empty() {
            return Err(Error::EmptyRoute);
        }
        let (requests, received) = tokio::sync::mpsc::unbounded_channel();
        let (ready, outcome) = mpsc::sync_channel(1);
        thread::Builder::new()
            .name("bellterm-ssh".into())
            .spawn(move || {
                let runtime = match tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                {
                    Ok(runtime) => runtime,
                    Err(error) => {
                        let _ = ready.send(Err(Error::Io(error)));
                        return;
                    }
                };
                runtime.block_on(serve(route, received, ready));
            })?;
        wait(&outcome, stop, SETUP_TIMEOUT)?;
        Ok(Self { requests })
    }

    /// Run `command` as a bridge pumped through a socket, then let `answer`
    /// read its banner on the end returned.
    fn bridge(
        &self,
        command: String,
        stop: &AtomicBool,
        answer: impl FnOnce(&mut UnixStream, &AtomicBool) -> herdr_client::Result<()>,
    ) -> Result<Stream> {
        let (mut ours, theirs) = UnixStream::pair()?;
        let (opened, outcome) = mpsc::sync_channel(1);
        self.send(Request::Bridge {
            command,
            stream: theirs,
            opened,
        })?;
        wait(&outcome, stop, SETUP_TIMEOUT)?;
        ours.set_read_timeout(Some(POLL))?;
        ours.set_write_timeout(Some(Duration::from_secs(1)))?;
        answer(&mut ours, stop)?;
        Ok(ours)
    }

    /// Run `command` and collect its output. Blocks for at most the collect
    /// timeout once its channel is open.
    pub fn run(&self, command: String, collect: Collect, stop: &AtomicBool) -> Result<Output> {
        let (done, outcome) = mpsc::sync_channel(1);
        self.send(Request::Run {
            command,
            collect,
            done,
        })?;
        wait(&outcome, stop, SETUP_TIMEOUT + collect.timeout)
    }

    fn send(&self, request: Request) -> Result<()> {
        self.requests.send(request).map_err(|_| Error::SshClosed)
    }
}

/// Wait for the runtime thread's answer, at most `limit`.
fn wait<T>(outcome: &mpsc::Receiver<Result<T>>, stop: &AtomicBool, limit: Duration) -> Result<T> {
    let started = Instant::now();
    loop {
        if stop.load(Ordering::Acquire) {
            return Err(Error::Cancelled);
        }
        match outcome.recv_timeout(POLL) {
            Ok(result) => return result,
            Err(mpsc::RecvTimeoutError::Timeout) if started.elapsed() < limit => {}
            Err(mpsc::RecvTimeoutError::Timeout) => return Err(Error::Timeout),
            Err(mpsc::RecvTimeoutError::Disconnected) => return Err(Error::SshClosed),
        }
    }
}

async fn serve(
    route: Route,
    mut requests: tokio::sync::mpsc::UnboundedReceiver<Request>,
    ready: mpsc::SyncSender<Result<()>>,
) {
    let handles = match tokio::time::timeout(SETUP_TIMEOUT, start(route)).await {
        Ok(Ok(handles)) => handles,
        Ok(Err(error)) => {
            let _ = ready.send(Err(error));
            return;
        }
        Err(_) => {
            let _ = ready.send(Err(Error::Timeout));
            return;
        }
    };
    let _ = ready.send(Ok(()));
    let mut pumps = Vec::new();
    while let Some(request) = requests.recv().await {
        let Some(last) = handles.last() else { break };
        match request {
            Request::Bridge {
                command,
                stream,
                opened,
            } => match exec(last, command).await {
                Ok(channel) => pumps.push(tokio::spawn(pump(channel, stream, opened))),
                Err(error) => {
                    let _ = opened.send(Err(error));
                }
            },
            Request::Run {
                command,
                collect,
                done,
            } => {
                let output = match exec(last, command).await {
                    Ok(channel) => tokio::time::timeout(collect.timeout, gather(channel, collect))
                        .await
                        .map_err(|_| Error::CommandTimeout),
                    Err(error) => Err(error),
                };
                let _ = done.send(output);
            }
        }
    }
    for pump in pumps {
        let _ = pump.await;
    }
    // The last hop first: each inner session rides on the one before it.
    for handle in handles.iter().rev() {
        let _ = handle.disconnect(Disconnect::ByApplication, "", "en").await;
    }
}

async fn exec(session: &Session, command: String) -> Result<russh::Channel<client::Msg>> {
    let channel = session.channel_open_session().await?;
    channel.exec(true, command).await?;
    Ok(channel)
}

async fn pump(
    channel: russh::Channel<client::Msg>,
    theirs: UnixStream,
    opened: mpsc::SyncSender<Result<()>>,
) {
    let theirs = theirs
        .set_nonblocking(true)
        .and_then(|()| tokio::net::UnixStream::from_std(theirs));
    let mut theirs = match theirs {
        Ok(theirs) => theirs,
        Err(error) => {
            let _ = opened.send(Err(error.into()));
            return;
        }
    };
    let _ = opened.send(Ok(()));
    let mut channel = channel.into_stream();
    // Ends when either side closes: the worker dropping its end on
    // disconnect, or the remote bridge exiting.
    if let Err(error) = tokio::io::copy_bidirectional(&mut channel, &mut theirs).await {
        tracing::info!(kind = ?error.kind(), "ssh bridge pump ended");
    }
}

async fn gather(mut channel: russh::Channel<client::Msg>, collect: Collect) -> Output {
    let mut tail = Vec::new();
    let mut exit_status = None;
    while let Some(message) = channel.wait().await {
        let data = match message {
            ChannelMsg::Data { data } => data,
            ChannelMsg::ExtendedData { data, .. } if collect.stderr => data,
            ChannelMsg::ExitStatus {
                exit_status: status,
            } => {
                exit_status = Some(status);
                continue;
            }
            _ => continue,
        };
        tail.extend_from_slice(&data);
        if tail.len() > collect.limit {
            tail.drain(..tail.len() - collect.limit);
        }
    }
    Output { exit_status, tail }
}

type Session = Handle<Verifier>;

/// Connect and authenticate every hop.
async fn start(route: Route) -> Result<Vec<Session>> {
    let config = Arc::new(client::Config {
        keepalive_interval: route.keepalive,
        keepalive_max: 4,
        inactivity_timeout: None,
        nodelay: true,
        ..client::Config::default()
    });
    let mut sessions: Vec<Session> = Vec::with_capacity(route.hops.len());
    let mut previous = String::new();
    for (index, hop) in route.hops.into_iter().enumerate() {
        let seen = Arc::new(Mutex::new(None));
        let verifier = Verifier {
            pinned: hop.pinned.clone(),
            seen: seen.clone(),
        };
        let connected = match sessions.last() {
            None => client::connect(config.clone(), (hop.host.as_str(), hop.port), verifier).await,
            Some(jump) => {
                let tunnel = jump
                    .channel_open_direct_tcpip(
                        hop.host.as_str(),
                        u32::from(hop.port),
                        "127.0.0.1",
                        0,
                    )
                    .await
                    .map_err(|source| Error::Jump {
                        jump: previous.clone(),
                        target: hop.address(),
                        source: Box::new(source),
                    })?;
                client::connect_stream(config.clone(), tunnel.into_stream(), verifier).await
            }
        };
        let mut session = match connected {
            Ok(session) => session,
            Err(russh::Error::UnknownKey) => {
                let fingerprint = seen
                    .lock()
                    .ok()
                    .and_then(|seen| seen.clone())
                    .unwrap_or_default();
                return Err(Error::UntrustedHostKey {
                    hop: index,
                    host: hop.address(),
                    fingerprint,
                    changed: hop.pinned.is_some(),
                });
            }
            Err(error) => return Err(error.into()),
        };
        auth::authenticate(&mut session, &hop, index).await?;
        previous = hop.address();
        sessions.push(session);
    }
    if sessions.is_empty() {
        return Err(Error::EmptyRoute);
    }
    Ok(sessions)
}

/// Accepts exactly the pinned key, recording what was offered either way.
struct Verifier {
    pinned: Option<String>,
    seen: Arc<Mutex<Option<String>>>,
}

impl client::Handler for Verifier {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> std::result::Result<bool, Self::Error> {
        let key = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(certificate) => {
                PublicKey::from(certificate.public_key().clone())
            }
        };
        let fingerprint = fingerprint(&key);
        let trusted = self.pinned.as_deref() == Some(fingerprint.as_str());
        if let Ok(mut seen) = self.seen.lock() {
            *seen = Some(fingerprint);
        }
        Ok(trusted)
    }
}

/// OpenSSH's `SHA256:…` form, as `ssh-keygen -lf` prints it.
pub fn fingerprint(key: &PublicKey) -> String {
    key.fingerprint(HashAlg::Sha256).to_string()
}
