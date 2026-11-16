use super::*;
use std::{fs, os::unix::fs::PermissionsExt, process::Command};

#[test]
fn parses_the_enabled_machines_herdr_lists() {
    let listed = br#"[
  {"id": "a1", "label": "build", "target": "build", "session": "default", "enabled": true, "selected": true},
  {"id": "b2", "label": "old", "target": "me@old:2222", "session": "work", "enabled": false, "selected": false},
  {"id": "c3", "label": "odd", "target": "-oProxyCommand=x", "session": "default", "enabled": true, "selected": false},
  {"id": "d4", "label": "url", "target": "ssh://me@box:2200", "session": "agents", "enabled": true, "selected": false}
]"#;
    let machines = parse(Some(0), listed).unwrap();
    let targets = machines
        .iter()
        .map(|m| m.target.as_str())
        .collect::<Vec<_>>();
    assert_eq!(targets, ["build", "ssh://me@box:2200"]);
    assert_eq!(machines[1].session, "agents");
    assert_eq!(parse(Some(0), b"[]").unwrap(), []);
}

#[test]
fn a_host_that_cannot_list_machines_says_so() {
    // No Herdr, or one from before `machine list --json`.
    for status in [Some(127), Some(2), None] {
        assert!(
            matches!(parse(status, b""), Err(Error::NoMachineList)),
            "{status:?}"
        );
    }
    assert!(matches!(
        parse(Some(0), b"No saved SSH machines."),
        Err(Error::Json(_))
    ));
}

#[test]
fn the_script_runs_the_first_herdr_it_finds() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join(".local/bin");
    fs::create_dir_all(&bin).unwrap();
    let herdr = bin.join("herdr");
    fs::write(&herdr, "#!/bin/sh\necho \"listed: $*\"\n").unwrap();
    fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap();
    let output = Command::new("/bin/sh")
        .args(["-c", &script()])
        .env_clear()
        .env("HOME", dir.path())
        .env("PATH", "/usr/bin:/bin")
        .output()
        .unwrap();
    assert_eq!(
        String::from_utf8_lossy(&output.stdout),
        "listed: machine list --json\n"
    );

    fs::remove_file(&herdr).unwrap();
    let output = Command::new("/bin/sh")
        .args(["-c", &script()])
        .env_clear()
        .env("HOME", dir.path())
        .env("PATH", "/usr/bin:/bin")
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(127));
}
