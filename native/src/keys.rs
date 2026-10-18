//! SSH identities: keys generated on the phone, keys imported from
//! elsewhere (decrypted once with their passphrase, then kept in Kotlin's
//! Keystore-encrypted storage), and OpenSSH certificates signed for them.
//! Everything Kotlin shows about a key or certificate is read here.

use crate::{Error, Result};
use russh::keys::{
    HashAlg,
    ssh_key::{Certificate, LineEnding, PrivateKey, PublicKey, private::Ed25519Keypair},
};
use serde::Serialize;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use zeroize::Zeroizing;

#[derive(Debug)]
pub struct Identity {
    /// OpenSSH private key text.
    pub private: Zeroizing<String>,
    /// One `authorized_keys` line.
    pub public: String,
}

pub fn generate(comment: &str) -> Result<Identity> {
    let mut seed = Zeroizing::new([0u8; 32]);
    getrandom::fill(seed.as_mut()).map_err(Error::Random)?;
    let mut key = PrivateKey::from(Ed25519Keypair::from_seed(&seed));
    key.set_comment(comment);
    Ok(Identity {
        private: key.to_openssh(LineEnding::LF)?,
        public: key.public_key().to_openssh()?,
    })
}

/// The `authorized_keys` line of a stored private key.
pub fn public_of(private: &str) -> Result<String> {
    let key = PrivateKey::from_openssh(private)?;
    Ok(key.public_key().to_openssh()?)
}

/// What the key list shows about a private key.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct KeyInfo {
    pub algorithm: String,
    pub fingerprint: String,
    pub comment: String,
    pub public: String,
}

fn info(key: &PrivateKey) -> Result<KeyInfo> {
    let public = key.public_key();
    Ok(KeyInfo {
        algorithm: public.algorithm().to_string(),
        fingerprint: public.fingerprint(HashAlg::Sha256).to_string(),
        comment: public.comment().to_string(),
        public: public.to_openssh()?,
    })
}

/// Import pasted or picked key text: decrypt it with `passphrase` if it is
/// protected, and return the unencrypted OpenSSH text to store with its info.
pub fn import(text: &str, passphrase: Option<&str>) -> Result<(Zeroizing<String>, KeyInfo)> {
    let key = PrivateKey::from_openssh(text.trim())?;
    let key = if key.is_encrypted() {
        let passphrase = passphrase
            .filter(|p| !p.is_empty())
            .ok_or(Error::KeyEncrypted)?;
        key.decrypt(passphrase)?
    } else {
        key
    };
    let info = info(&key)?;
    Ok((key.to_openssh(LineEnding::LF)?, info))
}

/// What the key list shows about a certificate, and whether it fits a key.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct CertificateInfo {
    pub key_id: String,
    pub principals: Vec<String>,
    pub ca_fingerprint: String,
    pub valid_after: u64,
    pub valid_before: u64,
    /// Whether the certificate certifies the given key.
    pub matches: bool,
    /// Whether it is valid now.
    pub current: bool,
}

pub fn certificate(text: &str, private: &str) -> Result<CertificateInfo> {
    let certificate = Certificate::from_openssh(text.trim())?;
    let key = PrivateKey::from_openssh(private)?;
    let certified = PublicKey::from(certificate.public_key().clone());
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_secs();
    Ok(CertificateInfo {
        key_id: certificate.key_id().to_owned(),
        principals: certificate.valid_principals().to_vec(),
        ca_fingerprint: certificate
            .signature_key()
            .fingerprint(HashAlg::Sha256)
            .to_string(),
        valid_after: certificate.valid_after(),
        valid_before: certificate.valid_before(),
        matches: certified.key_data() == key.public_key().key_data(),
        current: certificate.valid_after() <= now && now < certificate.valid_before(),
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
