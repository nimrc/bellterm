use super::*;

const ENCRYPTED: &str = include_str!("../../tests/fixtures/encrypted_ed25519");
const RSA: &str = include_str!("../../tests/fixtures/rsa");
const USER: &str = include_str!("../../tests/fixtures/user");
const USER_PUB: &str = include_str!("../../tests/fixtures/user.pub");
const CERT: &str = include_str!("../../tests/fixtures/user-cert.pub");
const EXPIRED: &str = include_str!("../../tests/fixtures/user-cert-expired.pub");

#[test]
fn generated_keys_round_trip_and_differ() {
    let first = generate("bellterm").unwrap();
    let second = generate("bellterm").unwrap();
    assert!(first.public.starts_with("ssh-ed25519 "));
    assert!(first.public.ends_with(" bellterm"));
    assert_eq!(public_of(&first.private).unwrap(), first.public);
    assert_ne!(first.public, second.public);
}

#[test]
fn malformed_private_keys_are_rejected() {
    assert!(matches!(public_of("not a key"), Err(Error::Key(_))));
    assert!(matches!(import("not a key", None), Err(Error::Key(_))));
}

#[test]
fn encrypted_keys_need_their_passphrase_and_are_stored_decrypted() {
    assert!(matches!(import(ENCRYPTED, None), Err(Error::KeyEncrypted)));
    assert!(matches!(
        import(ENCRYPTED, Some("")),
        Err(Error::KeyEncrypted)
    ));
    assert!(import(ENCRYPTED, Some("wrong")).is_err());
    let (private, info) = import(ENCRYPTED, Some("correct horse")).unwrap();
    assert!(!PrivateKey::from_openssh(&*private).unwrap().is_encrypted());
    assert_eq!(info.algorithm, "ssh-ed25519");
    assert_eq!(info.comment, "fixture-encrypted");
    assert!(info.fingerprint.starts_with("SHA256:"));
}

#[test]
fn rsa_keys_import() {
    let (_, info) = import(RSA, None).unwrap();
    assert_eq!(info.algorithm, "ssh-rsa");
    assert!(info.public.starts_with("ssh-rsa "));
}

#[test]
fn certificates_report_their_key_principals_and_validity() {
    let cert = certificate(CERT, USER).unwrap();
    assert_eq!(cert.key_id, "fixture-id");
    assert_eq!(cert.principals, ["herdr", "ops"]);
    assert!(cert.matches);
    assert!(cert.current);
    assert!(cert.ca_fingerprint.starts_with("SHA256:"));
    assert_eq!(
        import(USER, None).unwrap().1.public.split(' ').nth(1),
        USER_PUB.split(' ').nth(1)
    );

    let expired = certificate(EXPIRED, USER).unwrap();
    assert!(expired.matches);
    assert!(!expired.current);

    let other = generate("other").unwrap();
    assert!(!certificate(CERT, &other.private).unwrap().matches);
    assert!(certificate("ssh-ed25519 AAAA nope", USER).is_err());
}
