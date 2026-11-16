//! Installing or updating Herdr on a host, only when the user asks for it.
//! An installed Herdr, the first one the bridge would try, updates itself
//! with live handoff so running sessions move to the new version; a host with
//! none gets Herdr's own installer, which puts it in `~/.local/bin`, where the
//! bridge looks. A Windows host, whose shell cannot run `/bin/sh`, gets the
//! same through PowerShell and Herdr's `install.ps1`.

use crate::{
    Result,
    ssh::{self, Collect, Remote, Route},
};
use herdr_client::{HERDR_CANDIDATES, powershell_command, shell_quote};
use serde_json::json;
use std::{sync::atomic::AtomicBool, time::Duration};

/// Herdr's installers, as herdr.dev publishes them.
pub const INSTALLER_URL: &str = "https://herdr.dev/install.sh";
pub const WINDOWS_INSTALLER_URL: &str = "https://herdr.dev/install.ps1";
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

/// [`script`] for PowerShell: the `herdr.exe` the Windows probe finds first on
/// `PATH` or in the installer's own location, or else `install.ps1`, run as
/// herdr.dev documents it. Windows PowerShell 5.1 may default to TLS 1.0, and
/// with stderr redirected it writes `Write-Host` there as CLIXML, so the
/// information stream joins stdout as plain text.
pub fn windows_script() -> String {
    format!(
        r#"$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'; $herdr = Get-Command herdr.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source; if ($null -eq $herdr) {{ $herdrHome = if ([string]::IsNullOrWhiteSpace($env:HERDR_HOME)) {{ Join-Path $env:USERPROFILE '.herdr' }} else {{ $env:HERDR_HOME }}; $current = Join-Path $herdrHome 'packages\standalone\current\herdr.exe'; if (Test-Path -LiteralPath $current -PathType Leaf) {{ $herdr = $current }} }}; if ($null -ne $herdr) {{ [Console]::Out.WriteLine("Updating $herdr"); & $herdr update --handoff; exit $LASTEXITCODE }}; $url = '{WINDOWS_INSTALLER_URL}'; [Console]::Out.WriteLine("Installing Herdr from $url"); [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; Invoke-RestMethod -Uri $url | Invoke-Expression 6>&1; exit 0"#,
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

/// Host output is untrusted: keep what a carriage return left visible on each
/// line, as a progress bar ends up, then drop escape sequences and controls,
/// which the stripper also does to tabs unless they are spaces first.
pub(crate) fn plain_text(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes)
        .lines()
        .map(|line| {
            let visible = line
                .trim_end_matches('\r')
                .rsplit('\r')
                .next()
                .unwrap_or_default();
            strip_ansi_escapes::strip_str(visible.replace('\t', "    "))
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Blocks until the host finishes, at most ten minutes: call it from a
/// background thread.
pub fn run(route: Route) -> Result<Outcome> {
    let never = AtomicBool::new(false);
    let remote = Remote::connect(route, &never)?;
    let command = if is_posix(&remote, &never)? {
        format!("/bin/sh -c {}", shell_quote(&script()))
    } else {
        powershell_command(&windows_script())
    };
    let collect = Collect {
        timeout: TIMEOUT,
        limit: OUTPUT_LIMIT,
        stderr: true,
    };
    remote
        .run(command, collect, &never)
        .map(Outcome::from_output)
}

/// Whether the host runs `/bin/sh`. Windows OpenSSH starts `cmd.exe` or
/// PowerShell, which cannot find it; the setup script's own failures share
/// their exit codes, so this asks before running it.
fn is_posix(remote: &Remote, stop: &AtomicBool) -> Result<bool> {
    let collect = Collect {
        timeout: Duration::from_secs(30),
        limit: 0,
        stderr: false,
    };
    let output = remote.run(
        format!("/bin/sh -c {}", shell_quote("exit 0")),
        collect,
        stop,
    )?;
    Ok(output.exit_status == Some(0))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
