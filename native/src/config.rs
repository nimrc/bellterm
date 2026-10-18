//! What Kotlin asks to connect to, as one JSON document parsed here at the
//! JNI boundary into domain types. Secrets are held in `Zeroizing` from the
//! moment they are parsed.

use crate::{
    Result,
    session::{self, Config},
    ssh::{Credential, Hop, Route},
    theme::Theme,
};
use serde::Deserialize;
use std::time::Duration;
use zeroize::Zeroizing;

#[derive(Deserialize)]
struct Document {
    session: String,
    hops: Vec<HopDocument>,
    #[serde(default)]
    keepalive_secs: Option<u64>,
    cols: u16,
    rows: u16,
    #[serde(default)]
    cell_width: u32,
    #[serde(default)]
    cell_height: u32,
    theme: Theme,
}

#[derive(Deserialize)]
struct HopDocument {
    host: String,
    port: u16,
    user: String,
    #[serde(default)]
    pinned: Option<String>,
    auth: AuthDocument,
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum AuthDocument {
    Key {
        private: Zeroizing<String>,
        #[serde(default)]
        certificate: Option<String>,
    },
    Password {
        password: Zeroizing<String>,
    },
}

/// Parse a connection request. Validation of hosts and sessions happens where
/// they are used: herdr-client checks the session, the SSH layer the route.
pub fn parse(json: &str) -> Result<Config> {
    // The document holds secrets; keep its text out of reach once parsed.
    let document: Document = serde_json::from_str(json)?;
    let hops = document
        .hops
        .into_iter()
        .map(|hop| Hop {
            host: hop.host,
            port: hop.port,
            user: hop.user,
            pinned: hop.pinned.filter(|p| !p.is_empty()),
            credential: match hop.auth {
                AuthDocument::Key {
                    private,
                    certificate,
                } => Credential::Key {
                    private,
                    certificate: certificate.filter(|c| !c.trim().is_empty()),
                },
                AuthDocument::Password { password } => Credential::Password(password),
            },
        })
        .collect();
    Ok(Config {
        route: Route {
            hops,
            session: document.session,
            keepalive: document
                .keepalive_secs
                .filter(|secs| *secs > 0)
                .map(Duration::from_secs),
        },
        options: session::options(
            document.cols,
            document.rows,
            document.cell_width,
            document.cell_height,
        ),
        theme: document.theme,
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    const THEME: &str = r#"{"dark":true,"foreground":"ffd8dee9","background":"ff14161b","cursor":"ffe8c27a",
        "ansi":["ff000000","ffff0000","ff00ff00","ffffff00","ff0000ff","ffff00ff","ff00ffff","ffffffff",
                "ff111111","ffff1111","ff11ff11","ffffff11","ff1111ff","ffff11ff","ff11ffff","ffeeeeee"]}"#;

    #[test]
    fn parses_a_route_through_a_jump_host() {
        let json = format!(
            r#"{{"session":"work","keepalive_secs":20,"cols":80,"rows":24,"theme":{THEME},
               "hops":[{{"host":"bastion","port":22,"user":"ops","pinned":"SHA256:abc",
                         "auth":{{"type":"key","private":"KEY","certificate":"ssh-ed25519-cert-v01@openssh.com AAAA"}}}},
                        {{"host":"10.0.0.5","port":2222,"user":"me","pinned":"",
                         "auth":{{"type":"password","password":"pw"}}}}]}}"#
        );
        let config = parse(&json).unwrap();
        let hops = &config.route.hops;
        assert_eq!(hops.len(), 2);
        assert_eq!(hops[0].address(), "bastion:22");
        assert_eq!(hops[0].pinned.as_deref(), Some("SHA256:abc"));
        assert!(matches!(
            &hops[0].credential,
            Credential::Key {
                certificate: Some(_),
                ..
            }
        ));
        assert_eq!(hops[1].pinned, None, "an empty pin is no pin");
        assert!(matches!(&hops[1].credential, Credential::Password(p) if p.as_str() == "pw"));
        assert_eq!(config.route.session, "work");
        assert_eq!(config.route.keepalive, Some(Duration::from_secs(20)));
        assert_eq!(config.options.surface_size.cols, 80);
        assert!(config.theme.dark);
        assert_eq!(config.theme.ansi[1].r, 0xff);
    }

    #[test]
    fn rejects_malformed_documents() {
        assert!(parse("{}").is_err());
        assert!(parse("not json").is_err());
    }

    #[test]
    fn debug_output_never_shows_secrets() {
        let json = format!(
            r#"{{"session":"s","cols":1,"rows":1,"theme":{THEME},
               "hops":[{{"host":"h","port":22,"user":"u","auth":{{"type":"password","password":"hunter2"}}}}]}}"#
        );
        let config = parse(&json).unwrap();
        assert!(!format!("{config:?}").contains("hunter2"));
    }
}
