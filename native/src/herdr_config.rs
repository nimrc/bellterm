//! Herdr's pane scrollbar takes a column only while the pane has scrollback.
//! A program that clears its scrollback when resized, such as omp, then
//! loops: the column appears, the pane narrows, the program clears and
//! redraws, the column goes, the pane widens, and so on several times a
//! second, which the user sees as a flickering screen. On each POSIX host it
//! connects to, Bellterm turns the scrollbar off in Herdr's config and has
//! the server reload it, unless the config already sets `pane_scrollbars`.

use crate::{
    Result,
    ssh::{Collect, Remote},
};
use herdr_client::{HERDR_CANDIDATES, shell_quote};
use std::{sync::atomic::AtomicBool, thread, time::Duration};
use toml_edit::{DocumentMut, Item, table, value};

/// Where Herdr reads its config, as it resolves the path itself.
const PATH: &str = r#"f=${HERDR_CONFIG_PATH:-${XDG_CONFIG_HOME:-$HOME/.config}/herdr/config.toml}"#;
const KEY: &str = "pane_scrollbars";
const COMMENT: &str = "# Set by Bellterm: a scrollbar column that comes and goes with scrollback makes\n\
     # programs that clear scrollback on resize (omp) redraw in a loop.\n";
/// The rewritten config travels as one argument to `/bin/sh -c`, which Linux
/// caps at 128 KiB; a config near that is left alone.
const LIMIT: usize = 64 * 1024;
const TIMEOUT: Duration = Duration::from_secs(30);

/// Check and fix the config in the background, over `remote`'s connection.
pub fn spawn(remote: Remote, session: String) {
    let spawned = thread::Builder::new()
        .name("bellterm-herdr-config".into())
        .spawn(move || match ensure(&remote, &session) {
            Ok(true) => tracing::info!("turned off Herdr pane scrollbars"),
            Ok(false) => {}
            Err(error) => tracing::warn!(%error, "could not check Herdr's config"),
        });
    if let Err(error) = spawned {
        tracing::warn!(%error, "could not start the Herdr config check");
    }
}

/// Whether the config was changed.
fn ensure(remote: &Remote, session: &str) -> Result<bool> {
    let never = AtomicBool::new(false);
    let collect = Collect {
        timeout: TIMEOUT,
        limit: LIMIT + 1,
        stderr: false,
    };
    let read = remote.run(sh(&read_script()), collect, &never)?;
    if read.exit_status != Some(0) || read.tail.len() > LIMIT {
        return Ok(false);
    }
    let Ok(config) = String::from_utf8(read.tail) else {
        return Ok(false);
    };
    let Some(patched) = patched(&config) else {
        return Ok(false);
    };
    if patched.len() > LIMIT {
        return Ok(false);
    }
    let written = remote.run(sh(&write_script(&patched, session)), collect, &never)?;
    Ok(written.exit_status == Some(0))
}

fn sh(script: &str) -> String {
    format!("/bin/sh -c {}", shell_quote(script))
}

/// Print the config; a missing one prints nothing, like an empty one.
pub(crate) fn read_script() -> String {
    format!("{PATH}\n[ -f \"$f\" ] || exit 0\ncat \"$f\"")
}

/// Write `config` in place, so a symlinked config stays a symlink, then have
/// the session's server reload it with the first Herdr the bridge would try.
pub(crate) fn write_script(config: &str, session: &str) -> String {
    format!(
        r#"{PATH}
mkdir -p "$(dirname "$f")" && printf '%s' {config} > "$f" || exit 1
{HERDR_CANDIDATES}
    if [ -n "$path" ] && [ -x "$path" ]; then
        exec "$path" --session {session} server reload-config </dev/null
    fi
done
exit 1"#,
        config = shell_quote(config),
        session = shell_quote(session),
    )
}

/// `config` with the scrollbar turned off, or `None` when it already sets
/// `pane_scrollbars` either way or is not a config this can edit safely.
pub(crate) fn patched(config: &str) -> Option<String> {
    let mut doc: DocumentMut = config.parse().ok()?;
    let ui = doc.entry("ui").or_insert_with(table);
    if let Item::Table(ui) = ui {
        if ui.contains_key(KEY) {
            return None;
        }
        // A `[ui.*]` table alone leaves `ui` implicit, which is not written.
        ui.set_implicit(false);
        ui.insert(KEY, value(false));
        if let Some(mut key) = ui.key_mut(KEY) {
            key.leaf_decor_mut().set_prefix(COMMENT);
        }
    } else {
        let ui = ui.as_table_like_mut()?;
        if ui.contains_key(KEY) {
            return None;
        }
        ui.insert(KEY, value(false));
    }
    Some(doc.to_string())
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
