//! The remote bridge over a stream the application connected itself, for a
//! platform without an `ssh` executable to spawn, such as Android with an
//! in-process SSH library. The application runs [`remote_bridge_command`] on
//! the host, pipes that channel through one end of a [`Stream::pair`], answers
//! discovery with [`answer_remote_bridge`] on the other end, then hands that
//! end to [`crate::connect_with_connector`] as [`crate::Transport::Remote`].
//! Host keys, credentials, and the channel itself stay with the application.
//!
//! A Windows OpenSSH host runs `cmd.exe` or PowerShell, so the POSIX bridge
//! closes before its banner. The application then runs
//! [`windows_probe_command`], picks the bridge with
//! [`windows_bridge_command`], runs that over a new channel, and waits with
//! [`answer_windows_bridge`]. PowerShell buffers the stdin it reads, so
//! discovery and the bridge cannot share one channel there.
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

/// The remote command that lists the `herdr.exe` candidates of a Windows host
/// for `session`. Collect its stdout and hand it to [`windows_bridge_command`].
#[cfg(unix)]
pub fn windows_probe_command(session: &str) -> Result<String> {
    session_socket(Path::new(""), session)?;
    Ok(ssh::powershell_probe(session))
}

/// The bridge for the first compatible candidate in `probe_output`. `None`
/// when the probe never finished, so the host is not one PowerShell serves;
/// an error when it found no Herdr or only incompatible ones.
#[cfg(unix)]
pub fn windows_bridge_command(probe_output: &[u8], session: &str) -> Option<Result<String>> {
    if let Err(error) = session_socket(Path::new(""), session) {
        return Some(Err(error));
    }
    ssh::powershell_bridge(probe_output, session)
}

/// Wait on `stream` for the ready banner of [`windows_bridge_command`]'s
/// bridge. Give `stream` a read timeout so `stop` is observed.
#[cfg(unix)]
pub fn answer_windows_bridge(stream: &mut (impl Read + Write), stop: &AtomicBool) -> Result<()> {
    ssh::answer_powershell(stream, stop)
}

/// `command` run on `target` by the `ssh` of the host the result runs on, for
/// an application that reaches Herdr through another host's saved machine:
/// that host's OpenSSH configuration, keys, and known hosts decide whether
/// `target` is reached. Wrap every remote command, the bridges included.
#[cfg(unix)]
pub fn relay_command(target: &str, command: &str) -> Result<String> {
    ssh::relay_command(target, command)
}

/// `script` as a remote command that PowerShell runs whether the Windows
/// host's shell is `cmd.exe` or PowerShell.
#[cfg(unix)]
pub fn powershell_command(script: &str) -> String {
    ssh::powershell_command(script)
}

#[cfg(all(test, unix))]
#[allow(clippy::unwrap_used)] // Test fixtures only.
mod tests;
