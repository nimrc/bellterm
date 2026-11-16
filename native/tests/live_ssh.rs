//! End-to-end over real SSH to a real Herdr daemon. Opt-in: it needs an SSH
//! server that accepts the key in `BELLTERM_LIVE_KEY` (with the certificate
//! in `BELLTERM_LIVE_CERT`, if set), or the password in
//! `BELLTERM_LIVE_PASSWORD`, and runs Herdr for `BELLTERM_LIVE_SESSION`,
//! optionally reached through a jump host on `BELLTERM_LIVE_JUMP_PORT`; see
//! README.md. Never point it at a daemon you care about: it types into the
//! focused pane. `installs_herdr_then_attaches` instead needs a host without
//! Herdr, POSIX or Windows OpenSSH, and installs it there.
#![allow(clippy::unwrap_used, clippy::expect_used)]

use bellterm::{
    herdr_setup,
    session::{self, Config, Session, UiEvent},
    ssh::{Credential, Hop, Route},
    theme::Theme,
};
use std::{
    env,
    time::{Duration, Instant},
};
use zeroize::Zeroizing;

fn var(name: &str) -> String {
    env::var(name).unwrap_or_else(|_| panic!("{name} must be set for the live test"))
}

fn key() -> Credential {
    if let Ok(password) = env::var("BELLTERM_LIVE_PASSWORD") {
        return Credential::Password(Zeroizing::new(password));
    }
    Credential::Key {
        private: Zeroizing::new(std::fs::read_to_string(var("BELLTERM_LIVE_KEY")).unwrap()),
        certificate: env::var("BELLTERM_LIVE_CERT")
            .ok()
            .map(|path| std::fs::read_to_string(path).unwrap()),
    }
}

/// The target, reached through `BELLTERM_LIVE_JUMP_PORT` on the same host
/// when that is set.
fn config(pinned: Option<String>, jump_pinned: Option<String>) -> Config {
    let host = var("BELLTERM_LIVE_HOST");
    let user = var("BELLTERM_LIVE_USER");
    let mut hops = Vec::new();
    if let Ok(port) = env::var("BELLTERM_LIVE_JUMP_PORT") {
        hops.push(Hop {
            host: host.clone(),
            port: port.parse().unwrap(),
            user: user.clone(),
            credential: key(),
            pinned: jump_pinned,
        });
    }
    hops.push(Hop {
        host,
        port: var("BELLTERM_LIVE_PORT").parse().unwrap(),
        user,
        credential: key(),
        pinned,
    });
    Config {
        route: Route {
            hops,
            session: env::var("BELLTERM_LIVE_SESSION").unwrap_or_else(|_| "default".into()),
            keepalive: Some(Duration::from_secs(15)),
        },
        options: session::options(100, 30, 9, 18),
        theme: Theme::DARK,
    }
}

fn wait(session: &Session, what: &str, accept: impl FnMut(&UiEvent) -> bool) -> UiEvent {
    wait_for(session, what, Duration::from_secs(30), accept)
}

fn wait_for(
    session: &Session,
    what: &str,
    timeout: Duration,
    mut accept: impl FnMut(&UiEvent) -> bool,
) -> UiEvent {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if let Some(event) = session.next_event(Duration::from_millis(200)) {
            eprintln!(
                "event: {}",
                event.to_json().chars().take(200).collect::<String>()
            );
            if accept(&event) {
                return event;
            }
            if let UiEvent::Disconnected { reason } = &event {
                panic!("disconnected while waiting for {what}: {reason}");
            }
        }
    }
    panic!("timed out waiting for {what}");
}

fn screen_text(session: &Session) -> String {
    session
        .take_frame()
        .map(|frame| {
            frame.text(
                (0, 0),
                (frame.cols.saturating_sub(1), frame.rows.saturating_sub(1)),
            )
        })
        .unwrap_or_default()
}

#[test]
#[ignore = "needs an SSH server running Herdr; see README.md"]
fn attaches_over_ssh_and_round_trips_input() {
    // Every hop that is not pinned is refused with its fingerprint for the
    // trust prompt, first the jump host, then the target behind it.
    let jumps = usize::from(env::var("BELLTERM_LIVE_JUMP_PORT").is_ok());
    let mut pins: Vec<Option<String>> = vec![None; jumps + 1];
    let session = loop {
        let session = Session::connect(config(pins[jumps].clone(), pins[0].clone())).unwrap();
        match wait(&session, "connected or host key prompt", |e| {
            matches!(
                e,
                UiEvent::UntrustedHostKey { .. } | UiEvent::Connected { .. }
            )
        }) {
            UiEvent::UntrustedHostKey {
                hop,
                host,
                fingerprint,
                changed,
            } => {
                assert!(!changed);
                assert!(pins[hop].is_none(), "hop {hop} prompted twice");
                assert!(fingerprint.starts_with("SHA256:"), "{fingerprint}");
                assert!(host.ends_with(&format!(
                    ":{}",
                    if hop < jumps {
                        var("BELLTERM_LIVE_JUMP_PORT")
                    } else {
                        var("BELLTERM_LIVE_PORT")
                    }
                )));
                if let Ok(expected) = env::var("BELLTERM_LIVE_FINGERPRINT") {
                    assert_eq!(fingerprint, expected);
                }
                pins[hop] = Some(fingerprint);
            }
            _ => break session,
        }
    };
    assert!(pins.iter().all(Option::is_some));

    echo_round_trip(&session, "attach");

    // A wrong pin on the target is refused as a changed key.
    let mismatched =
        Session::connect(config(Some("SHA256:not-this-host".into()), pins[0].clone())).unwrap();
    let event = wait(&mismatched, "changed key", |e| {
        matches!(e, UiEvent::UntrustedHostKey { .. })
    });
    assert!(matches!(event, UiEvent::UntrustedHostKey { changed: true, hop, .. } if hop == jumps));
}

/// Type a command into the focused pane and wait for its output. The newline
/// is sent as an Enter key press, which every pane's shell takes.
fn echo_round_trip(session: &Session, round: &str) {
    wait(session, "snapshot", |e| matches!(e, UiEvent::Snapshot));
    let view: serde_json::Value = serde_json::from_str(&session.take_view().unwrap()).unwrap();
    assert!(!view["workspaces"].as_array().unwrap().is_empty());
    wait(session, "frame", |e| matches!(e, UiEvent::Frame));

    // Unique per round: a reattached pane still shows earlier rounds.
    let marker = format!("bellterm-{}-{round}", std::process::id());
    let deadline = Instant::now() + Duration::from_secs(30);
    let mut typed = false;
    loop {
        if !typed
            && session
                .text_input(None, &format!("echo {marker}\n"))
                .is_ok()
        {
            typed = true;
        }
        let text = screen_text(session);
        if text.matches(&marker).count() >= 2 {
            eprintln!("screen:\n{text}");
            break;
        }
        assert!(Instant::now() < deadline, "marker never appeared:\n{text}");
        let _ = session.next_event(Duration::from_millis(200));
    }
}

/// The flow a user without Herdr goes through: the connection offers the
/// install, the install runs over SSH, and the next connection attaches. A
/// second run takes the update path. Windows OpenSSH hosts, whose shell is
/// `cmd.exe` or PowerShell, run the same flow through PowerShell.
#[test]
#[ignore = "needs an SSH server without Herdr; see README.md"]
fn installs_herdr_then_attaches() {
    assert!(
        env::var("BELLTERM_LIVE_JUMP_PORT").is_err(),
        "the install test reaches the host directly"
    );
    let first = Session::connect(config(None, None)).unwrap();
    let pin = match wait(&first, "host key prompt", |e| {
        matches!(e, UiEvent::UntrustedHostKey { .. })
    }) {
        UiEvent::UntrustedHostKey { fingerprint, .. } => fingerprint,
        _ => unreachable!(),
    };

    let missing = Session::connect(config(Some(pin.clone()), None)).unwrap();
    wait_for(&missing, "install prompt", Duration::from_secs(90), |e| {
        matches!(e, UiEvent::HerdrMissing)
    });

    for round in ["install", "update"] {
        let outcome = herdr_setup::run(config(Some(pin.clone()), None).route).unwrap();
        eprintln!("{round} output:\n{}", outcome.output);
        assert_eq!(outcome.exit_status, Some(0), "{round} failed");
        let session = Session::connect(config(Some(pin.clone()), None)).unwrap();
        wait_for(&session, "connected", Duration::from_secs(90), |e| {
            matches!(e, UiEvent::Connected { .. })
        });
        echo_round_trip(&session, round);
    }
}
