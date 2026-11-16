//! The SSH machines Herdr on a host has saved with `herdr machine add`. Herdr's
//! own client switches between them; the app reaches one through that host,
//! as a route with a [`Route::relay`](crate::ssh::Route::relay), so the
//! host's SSH configuration and keys sign in to it.

use crate::{
    Error, Result,
    ssh::{Collect, Remote, Route},
};
use herdr_client::{HERDR_CANDIDATES, shell_quote};
use serde::{Deserialize, Serialize};
use std::{sync::atomic::AtomicBool, time::Duration};

/// One saved machine, as `herdr machine list --json` prints it.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct Machine {
    pub id: String,
    pub label: String,
    /// What the host passes to `ssh`: a config alias, `user@host`, or an
    /// `ssh://` URL.
    pub target: String,
    pub session: String,
    pub enabled: bool,
}

const TIMEOUT: Duration = Duration::from_secs(30);
const OUTPUT_LIMIT: usize = 256 * 1024;

/// Ask the first Herdr the bridge would find for its saved machines.
pub fn script() -> String {
    format!(
        r#"{HERDR_CANDIDATES}
    if [ -n "$path" ] && [ -x "$path" ]; then
        exec "$path" machine list --json </dev/null
    fi
done
exit 127"#
    )
}

/// The enabled machines saved on the route's last host. Blocks for up to
/// half a minute once connected: call it from a background thread.
pub fn list(route: Route) -> Result<Vec<Machine>> {
    let never = AtomicBool::new(false);
    let remote = Remote::connect(route, &never)?;
    let collect = Collect {
        timeout: TIMEOUT,
        limit: OUTPUT_LIMIT,
        stderr: false,
    };
    let output = remote.run(
        format!("/bin/sh -c {}", shell_quote(&script())),
        collect,
        &never,
    )?;
    parse(output.exit_status, &output.tail)
}

/// A Herdr too old for `machine list`, none at all, or a host without
/// `/bin/sh` all exit without a list.
pub(crate) fn parse(exit_status: Option<u32>, stdout: &[u8]) -> Result<Vec<Machine>> {
    if exit_status != Some(0) {
        return Err(Error::NoMachineList);
    }
    let machines: Vec<Machine> = serde_json::from_slice(stdout)?;
    Ok(machines
        .into_iter()
        .filter(|machine| {
            machine.enabled && herdr_client::relay_command(&machine.target, "").is_ok()
        })
        .collect())
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
