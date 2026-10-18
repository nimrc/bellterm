use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    // keep-sorted start
    /// `hop` is the rejecting host's place in the route.
    #[error("{host} rejected the sign-in")]
    AuthRejected { hop: usize, host: String },
    #[error("connection setup was cancelled")]
    Cancelled,
    #[error(transparent)]
    Client(#[from] herdr_client::Error),
    #[error("{0} is not an ARGB colour")]
    Colour(String),
    #[error("the route has no hosts")]
    EmptyRoute,
    #[error(transparent)]
    Ghostty(#[from] libghostty_vt::error::Error),
    #[error(transparent)]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Jni(#[from] jni::errors::Error),
    #[error(transparent)]
    Json(#[from] serde_json::Error),
    #[error("jump host {jump} could not reach {target}: {source}")]
    Jump {
        jump: String,
        target: String,
        source: Box<russh::Error>,
    },
    #[error(transparent)]
    Key(#[from] russh::keys::ssh_key::Error),
    #[error("the key is protected by a passphrase")]
    KeyEncrypted,
    #[error("no such session")]
    NoSession,
    #[error("not connected to a daemon yet")]
    NotReady,
    #[error("system random source failed: {0}")]
    Random(getrandom::Error),
    #[error(transparent)]
    Ssh(#[from] russh::Error),
    #[error("SSH closed before the bridge started")]
    SshClosed,
    #[error(transparent)]
    SshKey(#[from] russh::keys::Error),
    #[error("timed out connecting")]
    Timeout,
    #[error("unknown endpoint method `{0}`")]
    UnknownMethod(String),
    #[error("host key {fingerprint} of {host} is not trusted")]
    UntrustedHostKey {
        hop: usize,
        host: String,
        fingerprint: String,
        changed: bool,
    },
    // keep-sorted end
}

pub type Result<T> = std::result::Result<T, Error>;
