//! Installing or updating Herdr on a host, only when the user asks for it.
//! An installed Herdr, the first one the bridge would try, updates itself
//! with live handoff so running sessions move to the new version; a host with
//! none gets Herdr's own installer, which puts it in `~/.local/bin`, where the
//! bridge looks.

use crate::{
    Result,
    ssh::{self, Route},
};
use herdr_client::{HERDR_CANDIDATES, shell_quote};
use serde_json::json;
use std::time::Duration;

/// Herdr's installer, as herdr.dev publishes it.
pub const INSTALLER_URL: &str = "https://herdr.dev/install.sh";
/// A download and a handoff over a slow mobile link.
const TIMEOUT: Duration = Duration::from_secs(600);
/// Enough of the end for the user to see why it failed.
const OUTPUT_LIMIT: usize = 16 * 1024;

/// Update the first Herdr the bridge would find, or install one.
pub fn script() -> String {
    format!(
        r#"{HERDR_CANDIDATES}
    if [ -n "$path" ] && [ -x "$path" ]; then
        printf 'Updating %s\n' "$path"
        exec "$path" update --handoff </dev/null
    fi
done
{}"#,
        install_script(INSTALLER_URL)
    )
}

/// Download the installer to a file first, so a failed download is not run
/// as an empty script that succeeds.
pub(crate) fn install_script(url: &str) -> String {
    format!(
        r#"url={url}
printf 'Installing Herdr from %s\n' "$url"
tmp=$(mktemp) || exit 1
trap 'rm -f "$tmp"' EXIT
if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$tmp" || exit 1
elif command -v wget >/dev/null 2>&1; then
    wget -qO "$tmp" "$url" || exit 1
else
    echo 'Neither curl nor wget is installed on this host.' >&2
    exit 127
fi
sh "$tmp" </dev/null"#,
        url = shell_quote(url)
    )
}

/// How the run ended, for the UI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Outcome {
    pub exit_status: Option<u32>,
    /// What the host printed, as plain text.
    pub output: String,
}

impl Outcome {
    fn from_output(output: ssh::Output) -> Self {
        Self {
            exit_status: output.exit_status,
            output: plain_text(&output.tail),
        }
    }

    pub fn to_json(&self) -> String {
        json!({"exit_status": self.exit_status, "output": self.output}).to_string()
    }
}

/// Host output is untrusted: drop escape sequences, and keep what a carriage
/// return left visible on each line, as a progress bar would end up.
pub(crate) fn plain_text(bytes: &[u8]) -> String {
    let text = strip_ansi_escapes::strip_str(String::from_utf8_lossy(bytes));
    text.lines()
        .map(|line| {
            line.trim_end_matches('\r')
                .rsplit('\r')
                .next()
                .unwrap_or_default()
                .chars()
                .filter(|c| *c == '\t' || !c.is_control())
                .collect::<String>()
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Blocks until the host finishes, at most ten minutes: call it from a
/// background thread.
pub fn run(route: Route) -> Result<Outcome> {
    let command = format!("/bin/sh -c {}", shell_quote(&script()));
    ssh::run(route, &command, TIMEOUT, OUTPUT_LIMIT).map(Outcome::from_output)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
