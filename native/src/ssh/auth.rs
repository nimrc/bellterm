//! Signing in to one hop: a private key (with its OpenSSH certificate when
//! the host trusts a user CA), or a password, which also answers
//! keyboard-interactive prompts for hosts that offer only that.

use super::{Hop, Verifier};
use crate::{Error, Result};
use russh::{
    client::{Handle, KeyboardInteractiveAuthResponse},
    keys::{Certificate, PrivateKeyWithHashAlg, decode_secret_key},
};
use std::sync::Arc;
use zeroize::Zeroizing;

pub enum Credential {
    Key {
        /// OpenSSH private key text, already decrypted.
        private: Zeroizing<String>,
        /// An OpenSSH certificate for that key (`*-cert.pub`).
        certificate: Option<String>,
    },
    Password(Zeroizing<String>),
}

impl std::fmt::Debug for Credential {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Key {
                certificate: None, ..
            } => "Credential::Key(..)",
            Self::Key {
                certificate: Some(_),
                ..
            } => "Credential::Certificate(..)",
            Self::Password(_) => "Credential::Password(..)",
        })
    }
}

/// Keyboard-interactive rounds a password may answer before giving up.
const MAX_ROUNDS: usize = 4;

/// Sign in to `hop`, the route's hop number `index`.
pub(super) async fn authenticate(
    session: &mut Handle<Verifier>,
    hop: &Hop,
    index: usize,
) -> Result<()> {
    let user = hop.user.clone();
    let accepted = match &hop.credential {
        Credential::Key {
            private,
            certificate,
        } => {
            let key = Arc::new(decode_secret_key(private, None)?);
            match certificate {
                Some(certificate) => {
                    let certificate = Certificate::from_openssh(certificate)?;
                    session
                        .authenticate_openssh_cert(user, key, certificate)
                        .await?
                        .success()
                }
                None => {
                    let hash = session.best_supported_rsa_hash().await?.flatten();
                    session
                        .authenticate_publickey(user, PrivateKeyWithHashAlg::new(key, hash))
                        .await?
                        .success()
                }
            }
        }
        Credential::Password(password) => {
            session
                .authenticate_password(user.clone(), password.as_str())
                .await?
                .success()
                || keyboard_interactive(session, user, password).await?
        }
    };
    if accepted {
        Ok(())
    } else {
        Err(Error::AuthRejected {
            hop: index,
            host: hop.address(),
        })
    }
}

/// Answer every prompt with the password, as `ssh` does for a host whose PAM
/// stack asks for it interactively.
async fn keyboard_interactive(
    session: &mut Handle<Verifier>,
    user: String,
    password: &Zeroizing<String>,
) -> Result<bool> {
    let mut response = session
        .authenticate_keyboard_interactive_start(user, None::<String>)
        .await?;
    for _ in 0..MAX_ROUNDS {
        match response {
            KeyboardInteractiveAuthResponse::Success => return Ok(true),
            KeyboardInteractiveAuthResponse::Failure { .. } => return Ok(false),
            KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                let answers = prompts.iter().map(|_| password.to_string()).collect();
                response = session
                    .authenticate_keyboard_interactive_respond(answers)
                    .await?;
            }
        }
    }
    Ok(false)
}
