//! The remote bridge over a stream the application connected itself, for a
//! platform without an `ssh` executable to spawn, such as Android with an
//! in-process SSH library. The application runs [`remote_bridge_command`] on
//! the host, pipes that channel through one end of a [`Stream::pair`], answers
//! discovery with [`answer_remote_bridge`] on the other end, then hands that
//! end to [`crate::connect_with_connector`] as [`crate::Transport::Remote`].
//! Host keys, credentials, and the channel itself stay with the application.
//! Only POSIX hosts are supported: there is no PowerShell fallback here.
//!
//! [`Stream::pair`]: crate::Stream

use crate::{
    Result,
    discovery::session_socket,
    ssh::{self, quote},
};
use std::{
    io::{Read, Write},
    path::Path,
    sync::atomic::AtomicBool,
};

/// The remote command that discovers a compatible `herdr` and, once
/// [`answer_remote_bridge`] accepts it, execs its `remote-client-bridge` for
/// `session`. Run it as an SSH exec request; it needs `/bin/sh` on the host.
pub fn remote_bridge_command(session: &str) -> Result<String> {
    session_socket(Path::new(""), session)?;
    Ok(format!(
        "/bin/sh -c {}",
        quote(&ssh::bridge_script(session))
    ))
}

/// Answer the discovery banner of [`remote_bridge_command`] on `stream`,
/// skipping incompatible candidates and accepting the first compatible one,
/// exactly as the built-in SSH bridge does. Give `stream` a read timeout so
/// `stop` is observed while the host is silent.
pub fn answer_remote_bridge(stream: &mut (impl Read + Write), stop: &AtomicBool) -> Result<()> {
    ssh::handshake(stream, stop)
}

#[cfg(all(test, unix))]
#[allow(clippy::unwrap_used)] // Test fixtures only.
mod tests;
