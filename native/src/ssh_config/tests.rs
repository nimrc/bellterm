use super::*;

const CONFIG: &str = r#"
# Personal machines
Host devbox dev
    HostName 10.0.0.5
    User me
    Port 2222
    ProxyJump bastion
    IdentityFile ~/.ssh/id_ed25519

Host bastion
    HostName=bastion.example.com
    User ops

Host *.internal !secret.internal
    ProxyJump ops@bastion.example.com:2200,inner
    User internal-user

Host gpu.internal
    Port "22"

Match host foo exec "true"
    User nobody

Include ~/.ssh/config.d/*

Host *
    User fallback
    ServerAliveInterval 30
"#;

fn entry<'a>(import: &'a Import, alias: &str) -> &'a Entry {
    import.entries.iter().find(|e| e.alias == alias).unwrap()
}

#[test]
fn concrete_aliases_become_entries_with_first_values_winning() {
    let import = parse(CONFIG);
    let aliases: Vec<_> = import.entries.iter().map(|e| e.alias.as_str()).collect();
    assert_eq!(aliases, ["devbox", "dev", "bastion", "gpu.internal"]);

    let devbox = entry(&import, "devbox");
    assert_eq!(devbox.host_name.as_deref(), Some("10.0.0.5"));
    assert_eq!(devbox.user.as_deref(), Some("me"));
    assert_eq!(devbox.port, Some(2222));
    assert_eq!(devbox.proxy_jump, ["bastion"]);
    assert_eq!(devbox.identity_file.as_deref(), Some("~/.ssh/id_ed25519"));
    assert_eq!(devbox.server_alive_interval, Some(30), "from Host *");
    assert_eq!(
        entry(&import, "dev"),
        &Entry {
            alias: "dev".into(),
            ..devbox.clone()
        }
    );

    let bastion = entry(&import, "bastion");
    assert_eq!(bastion.host_name.as_deref(), Some("bastion.example.com"));
    assert_eq!(bastion.user.as_deref(), Some("ops"));
    assert!(bastion.proxy_jump.is_empty());

    let gpu = entry(&import, "gpu.internal");
    assert_eq!(gpu.port, Some(22));
    assert_eq!(
        gpu.user.as_deref(),
        Some("internal-user"),
        "the wildcard block came first"
    );
    assert_eq!(gpu.proxy_jump, ["ops@bastion.example.com:2200", "inner"]);
}

#[test]
fn match_and_include_are_reported_not_applied() {
    let import = parse(CONFIG);
    assert_eq!(import.skipped.len(), 2);
    assert!(
        import
            .entries
            .iter()
            .all(|e| e.user.as_deref() != Some("nobody"))
    );
}

#[test]
fn negated_patterns_exclude() {
    assert!(matches(
        &["*.internal".into(), "!secret.internal".into()],
        "a.internal"
    ));
    assert!(!matches(
        &["*.internal".into(), "!secret.internal".into()],
        "secret.internal"
    ));
    assert!(glob("ho?t*", "HOST-1"));
    assert!(!glob("a*b", "acd"));
}

#[test]
fn jump_specs_split_into_user_host_and_port() {
    assert_eq!(
        jump("ops@bastion:2200"),
        (Some("ops"), "bastion", Some(2200))
    );
    assert_eq!(jump("bastion"), (None, "bastion", None));
    assert_eq!(jump("me@[::1]:22"), (Some("me"), "::1", Some(22)));
    assert_eq!(jump("::1"), (None, "::1", None));
}

#[test]
fn proxy_jump_none_clears() {
    let import = parse("Host a\n ProxyJump none\nHost *\n ProxyJump b\n");
    assert!(entry(&import, "a").proxy_jump.is_empty());
}
