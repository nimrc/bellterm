use super::*;
use serde_json::Value;

fn json(event: UiEvent) -> Value {
    serde_json::from_str(&event.to_json()).unwrap()
}

#[test]
fn an_outdated_host_install_refused_by_the_bridge_is_a_version_mismatch() {
    let error = Error::Client(herdr_client::Error::BridgeIncompatible {
        generation: None,
        version: Some("0.8.0".into()),
    });
    assert_eq!(
        connector_mismatch(&error),
        Some(VersionMismatch::DaemonOutdated {
            server_version: Some("0.8.0".into())
        })
    );
}

#[test]
fn other_connector_failures_are_not_version_mismatches() {
    assert_eq!(connector_mismatch(&Error::Timeout), None);
    assert_eq!(
        connector_mismatch(&Error::Client(herdr_client::Error::SshTimeout)),
        None
    );
}

#[test]
fn version_mismatches_name_the_side_to_update() {
    let daemon = json(
        VersionMismatch::DaemonOutdated {
            server_version: Some("0.8.0".into()),
        }
        .into(),
    );
    assert_eq!(daemon["type"], "version_mismatch");
    assert_eq!(daemon["outdated"], "daemon");
    assert_eq!(daemon["server_version"], "0.8.0");
    assert_eq!(daemon["min_version"], MIN_HERDR_VERSION);

    let app = json(
        VersionMismatch::ClientOutdated {
            server_version: None,
        }
        .into(),
    );
    assert_eq!(app["outdated"], "app");
    assert!(app["server_version"].is_null());
}

#[test]
fn a_rejected_sign_in_names_the_hop_to_retry() {
    let error = Error::AuthRejected {
        hop: 1,
        host: "box:22".into(),
    };
    let event = json(connector_refusal(&error).unwrap());
    assert_eq!(event["type"], "auth_rejected");
    assert_eq!(event["hop"], 1);
    assert_eq!(event["host"], "box:22");
}

#[test]
fn an_untrusted_host_key_is_a_refusal_and_a_timeout_is_not() {
    let untrusted = Error::UntrustedHostKey {
        hop: 0,
        host: "box:22".into(),
        fingerprint: "SHA256:x".into(),
        changed: false,
    };
    assert_eq!(
        json(connector_refusal(&untrusted).unwrap())["type"],
        "host_key"
    );
    assert!(connector_refusal(&Error::Timeout).is_none());
}
