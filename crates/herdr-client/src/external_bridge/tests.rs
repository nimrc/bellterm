use super::*;
use crate::{Error, Stream};
use std::{io::BufRead, thread, time::Duration};

#[test]
fn command_validates_the_session_and_runs_the_bridge() {
    assert!(matches!(
        remote_bridge_command("../escape"),
        Err(Error::InvalidSession)
    ));
    let command = remote_bridge_command("work").unwrap();
    assert!(command.starts_with("/bin/sh -c '"));
    assert!(command.contains("remote-client-bridge"));
    assert!(command.contains("--session"));
}

#[test]
fn answer_skips_incompatible_and_accepts_compatible_candidates() {
    let (mut ours, theirs) = Stream::pair().unwrap();
    ours.set_read_timeout(Some(Duration::from_millis(20)))
        .unwrap();
    let host = thread::spawn(move || {
        let mut writer = theirs.try_clone().unwrap();
        let mut reader = std::io::BufReader::new(theirs);
        let mut choices = Vec::new();
        for status in [
            r#"{"endpoint_protocol_generation":0,"version":"0.8.0"}"#,
            r#"{"endpoint_protocol_generation":1,"endpoint_capabilities":["surface_interest","presentation_effects_fence","health_check"],"remote_bridge_idle_timeout":true}"#,
        ] {
            writeln!(writer, "{status}\n\nherdr-remote-output-ready:1").unwrap();
            let mut choice = String::new();
            reader.read_line(&mut choice).unwrap();
            choices.push(choice);
        }
        choices
    });
    answer_remote_bridge(&mut ours, &AtomicBool::new(false)).unwrap();
    assert_eq!(host.join().unwrap(), ["skip\n", "accept-idle\n"]);
}

#[test]
fn answer_reports_the_refusal_when_the_host_has_no_compatible_herdr() {
    let (mut ours, theirs) = Stream::pair().unwrap();
    ours.set_read_timeout(Some(Duration::from_millis(20)))
        .unwrap();
    let host = thread::spawn(move || {
        let mut writer = theirs.try_clone().unwrap();
        let mut reader = std::io::BufReader::new(theirs);
        writeln!(
            writer,
            r#"{{"endpoint_protocol_generation":0,"version":"0.8.0"}}"#
        )
        .unwrap();
        writeln!(writer, "herdr-remote-output-ready:1").unwrap();
        let mut choice = String::new();
        reader.read_line(&mut choice).unwrap();
        choice
    });
    let result = answer_remote_bridge(&mut ours, &AtomicBool::new(false));
    assert_eq!(host.join().unwrap(), "skip\n");
    assert!(matches!(result, Err(Error::BridgeIncompatible { .. })));
}

const WINDOWS_COMPATIBLE: &str = r#"{"endpoint_protocol_generation":1,"endpoint_capabilities":["surface_interest","presentation_effects_fence","health_check"],"remote_bridge_idle_timeout":true,"remote_host_bridge":true}"#;

/// One Windows probe block, with the CRLF endings PowerShell prints.
fn windows_block(path: &str, client: &str) -> String {
    use base64::{Engine as _, engine::general_purpose::STANDARD};
    format!(
        "herdr-probe:candidate\r\nherdr-windows-path:{}\r\n{client}\r\n\r\n",
        STANDARD.encode(path)
    )
}

#[test]
fn windows_probe_validates_the_session_and_runs_under_powershell() {
    assert!(matches!(
        windows_probe_command("../escape"),
        Err(Error::InvalidSession)
    ));
    let command = windows_probe_command("work").unwrap();
    assert!(
        command.starts_with("powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand ")
    );
}

#[test]
fn an_unfinished_windows_probe_means_the_host_is_not_windows() {
    // A POSIX shell cannot find `powershell.exe` and prints only its error.
    assert!(windows_bridge_command(b"sh: 1: powershell.exe: not found\n", "work").is_none());
}

#[test]
fn a_windows_probe_without_candidates_reports_herdr_missing() {
    let result = windows_bridge_command(b"herdr-probe:done\r\n", "work").unwrap();
    assert!(matches!(
        result,
        Err(Error::SshRefused(crate::SshFailure::HerdrMissing))
    ));
}

#[test]
fn a_windows_probe_with_only_old_herdr_reports_the_refusal() {
    let output = format!(
        "{}herdr-probe:done\r\n",
        windows_block(
            r"C:\old\herdr.exe",
            r#"{"endpoint_protocol_generation":0,"version":"0.8.0"}"#
        )
    );
    let result = windows_bridge_command(output.as_bytes(), "work").unwrap();
    assert!(matches!(result, Err(Error::BridgeIncompatible { .. })));
}

#[test]
fn a_windows_probe_bridges_the_first_compatible_herdr() {
    let output = format!(
        "{}{}herdr-probe:done\r\n",
        windows_block(r"C:\old\herdr.exe", r#"{"endpoint_protocol_generation":0}"#),
        windows_block(r"C:\Users\me\herdr.exe", WINDOWS_COMPATIBLE),
    );
    let command = windows_bridge_command(output.as_bytes(), "work")
        .unwrap()
        .unwrap();
    assert!(
        command.starts_with("powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand ")
    );
    assert!(matches!(
        windows_bridge_command(output.as_bytes(), "../escape"),
        Some(Err(Error::InvalidSession))
    ));
}

#[test]
fn the_windows_bridge_is_ready_after_its_crlf_banner() {
    let (mut ours, theirs) = Stream::pair().unwrap();
    ours.set_read_timeout(Some(Duration::from_millis(20)))
        .unwrap();
    let host = thread::spawn(move || {
        let mut writer = theirs;
        writer
            .write_all(b"\r\nherdr-remote-output-ready:1\r\nwire")
            .unwrap();
        writer
    });
    answer_windows_bridge(&mut ours, &AtomicBool::new(false)).unwrap();
    let _writer = host.join().unwrap();
    let mut rest = [0; 4];
    ours.read_exact(&mut rest).unwrap();
    assert_eq!(&rest, b"wire");
}

/// The words `/bin/sh` splits `command` into.
fn words(command: &str) -> Vec<String> {
    let output = std::process::Command::new("/bin/sh")
        .args([
            "-c",
            &format!("for word in {command}; do printf '%s\\0' \"$word\"; done"),
        ])
        .output()
        .unwrap();
    String::from_utf8(output.stdout)
        .unwrap()
        .split_terminator('\0')
        .map(str::to_owned)
        .collect()
}

#[test]
fn relay_runs_the_command_through_the_hosts_own_ssh() {
    let bridge = remote_bridge_command("work").unwrap();
    let words = words(&relay_command("ssh://me@build:2222", &bridge).unwrap());
    assert_eq!(words[..2], ["ssh", "-T"]);
    assert!(words.windows(2).any(|w| w == ["-o", "BatchMode=yes"]));
    assert!(
        words
            .windows(2)
            .any(|w| w == ["-o", "StrictHostKeyChecking=yes"])
    );
    // The relayed command reaches the target's shell as one word, quotes and all.
    assert_eq!(
        words[words.len() - 3..],
        ["--", "ssh://me@build:2222", bridge.as_str()]
    );
    for target in ["-oProxyCommand=x", "", "a:b@host"] {
        assert!(
            matches!(relay_command(target, "true"), Err(Error::InvalidSshTarget)),
            "{target}"
        );
    }
}
