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
