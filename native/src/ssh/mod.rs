//! SSH without an `ssh` executable, which Android does not have. An
//! in-process client (russh) walks a route of hops: it connects to the first,
//! opens a `direct-tcpip` channel through each jump host to the next, checks
//! every host key against the one the user approved, authenticates each hop,
//! and runs Herdr's remote bridge on the last. The bridge channel is pumped
//! through a Unix socket pair so herdr-client's blocking worker reads it like
//! the bridge it spawns on the desktop. The async runtime is confined to this
//! one thread and owns only the SSH sessions.

mod auth;

use crate::{Error, Result};
pub use auth::Credential;
use herdr_client::{Stream, answer_remote_bridge, remote_bridge_command};
use russh::{
    Disconnect,
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
    if route.hops.is_empty() {
        return Err(Error::EmptyRoute);
    }
    let command = remote_bridge_command(&route.session)?;
    let (mut ours, theirs) = UnixStream::pair()?;
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
            runtime.block_on(run(route, command, theirs, ready));
        })?;
    let started = Instant::now();
    loop {
        if stop.load(Ordering::Acquire) {
            return Err(Error::Cancelled);
        }
        match outcome.recv_timeout(POLL) {
            Ok(result) => break result?,
            Err(mpsc::RecvTimeoutError::Timeout) if started.elapsed() < SETUP_TIMEOUT => {}
            Err(mpsc::RecvTimeoutError::Timeout) => return Err(Error::Timeout),
            Err(mpsc::RecvTimeoutError::Disconnected) => return Err(Error::SshClosed),
        }
    }
    ours.set_read_timeout(Some(POLL))?;
    ours.set_write_timeout(Some(Duration::from_secs(1)))?;
    answer_remote_bridge(&mut ours, stop)?;
    Ok(ours)
}

async fn run(
    route: Route,
    command: String,
    theirs: UnixStream,
    ready: mpsc::SyncSender<Result<()>>,
) {
    let (handles, channel) = match tokio::time::timeout(SETUP_TIMEOUT, start(route, command)).await
    {
        Ok(Ok(started)) => started,
        Ok(Err(error)) => {
            let _ = ready.send(Err(error));
            return;
        }
        Err(_) => {
            let _ = ready.send(Err(Error::Timeout));
            return;
        }
    };
    let pumped = async {
        theirs.set_nonblocking(true)?;
        let mut theirs = tokio::net::UnixStream::from_std(theirs)?;
        let mut channel = channel.into_stream();
        let _ = ready.send(Ok(()));
        // Ends when either side closes: the worker dropping its end on
        // disconnect, or the remote bridge exiting.
        tokio::io::copy_bidirectional(&mut channel, &mut theirs).await
    };
    if let Err(error) = pumped.await {
        tracing::info!(kind = ?error.kind(), "ssh bridge pump ended");
    }
    // The last hop first: each inner session rides on the one before it.
    for handle in handles.iter().rev() {
        let _ = handle.disconnect(Disconnect::ByApplication, "", "en").await;
    }
}

type Session = Handle<Verifier>;

/// Connect and authenticate every hop, then start the bridge on the last.
async fn start(
    route: Route,
    command: String,
) -> Result<(Vec<Session>, russh::Channel<client::Msg>)> {
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
    let last = sessions.last().ok_or(Error::EmptyRoute)?;
    let channel = last.channel_open_session().await?;
    channel.exec(true, command).await?;
    Ok((sessions, channel))
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
