use super::*;
use std::{fs, os::unix::fs::PermissionsExt, path::Path, process::Command};

fn executable(path: &Path, body: &str) {
    fs::write(path, format!("#!/bin/sh\n{body}\n")).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
}

/// Run `script` with only `bin` ahead of the system tools, in a scratch home.
fn sh(script: &str, home: &Path, bin: &Path) -> (Option<i32>, String) {
    let output = Command::new("/bin/sh")
        .args(["-c", script])
        .env_clear()
        .env("HOME", home)
        .env("PATH", format!("{}:/usr/bin:/bin", bin.display()))
        .output()
        .unwrap();
    let mut text = String::from_utf8_lossy(&output.stdout).into_owned();
    text.push_str(&String::from_utf8_lossy(&output.stderr));
    (output.status.code(), text)
}

#[test]
fn an_installed_herdr_updates_itself_with_handoff() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join("bin");
    fs::create_dir(&bin).unwrap();
    let herdr = bin.join("herdr");
    executable(&herdr, r#"echo "herdr $*""#);

    let (status, output) = sh(&script(), dir.path(), &bin);

    assert_eq!(status, Some(0), "{output}");
    assert!(
        output.contains(&format!("Updating {}", herdr.display())),
        "{output}"
    );
    assert!(output.contains("herdr update --handoff"), "{output}");
    assert!(!output.contains("Installing"), "{output}");
}

#[test]
fn a_host_without_herdr_runs_the_downloaded_installer() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join("bin");
    fs::create_dir(&bin).unwrap();
    // Writes an installer to the file after -o, as `curl -fsSL URL -o FILE` would.
    executable(
        &bin.join("curl"),
        r#"while [ $# -gt 0 ]; do [ "$1" = -o ] && out=$2; shift; done
echo 'echo installer ran' > "$out""#,
    );

    let (status, output) = sh(
        &install_script("https://example.test/install.sh"),
        dir.path(),
        &bin,
    );

    assert_eq!(status, Some(0), "{output}");
    assert!(
        output.contains("Installing Herdr from https://example.test/install.sh"),
        "{output}"
    );
    assert!(output.contains("installer ran"), "{output}");
}

#[test]
fn a_failed_download_is_not_run() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join("bin");
    fs::create_dir(&bin).unwrap();
    executable(
        &bin.join("curl"),
        "echo 'curl: (6) Could not resolve host' >&2; exit 6",
    );

    let (status, output) = sh(
        &install_script("https://example.test/install.sh"),
        dir.path(),
        &bin,
    );

    assert_eq!(status, Some(1), "{output}");
    assert!(output.contains("Could not resolve host"), "{output}");
}

#[test]
fn host_output_is_shown_as_plain_text() {
    let raw = b"\x1b[32mok\x1b[0m done\r\n10%\r55%\r100%\nbell\x07 tab\there\n";
    assert_eq!(plain_text(raw), "ok done\n100%\nbell tab\there");
}

#[test]
fn the_outcome_reaches_the_ui_as_json() {
    let outcome = Outcome {
        exit_status: Some(0),
        output: "done".into(),
    };
    let json: serde_json::Value = serde_json::from_str(&outcome.to_json()).unwrap();
    assert_eq!(json["exit_status"], 0);
    assert_eq!(json["output"], "done");
    let unknown = Outcome {
        exit_status: None,
        output: String::new(),
    };
    let json: serde_json::Value = serde_json::from_str(&unknown.to_json()).unwrap();
    assert!(json["exit_status"].is_null());
}
