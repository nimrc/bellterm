use super::*;
use std::{fs, os::unix::fs::PermissionsExt, path::Path, process::Command};

fn scrollbars(config: &str) -> Option<bool> {
    let doc: DocumentMut = config.parse().unwrap();
    doc.get("ui")?.get(KEY)?.as_bool()
}

#[test]
fn a_missing_config_gets_the_scrollbar_turned_off() {
    let config = patched("").unwrap();
    assert_eq!(scrollbars(&config), Some(false));
    assert!(config.starts_with("[ui]\n# Set by Bellterm"), "{config}");
}

#[test]
fn an_existing_ui_table_keeps_its_keys_and_comments() {
    let original = "onboarding = false\n\n[ui]\n# mine\ntab_bar_position = \"top\"\n\n[ui.toast]\ndelivery = \"terminal\"\n";
    let config = patched(original).unwrap();
    assert_eq!(scrollbars(&config), Some(false));
    assert!(
        config.contains("# mine\ntab_bar_position = \"top\"\n"),
        "{config}"
    );
    assert!(
        config.contains("[ui.toast]\ndelivery = \"terminal\""),
        "{config}"
    );
    assert!(config.starts_with("onboarding = false\n"), "{config}");
}

#[test]
fn a_config_that_already_decides_is_left_alone() {
    assert_eq!(patched("[ui]\npane_scrollbars = true\n"), None);
    assert_eq!(patched("[ui]\npane_scrollbars = false\n"), None);
    assert_eq!(patched("ui.pane_scrollbars = true\n"), None);
    assert_eq!(patched("ui = { pane_scrollbars = true }\n"), None);
}

#[test]
fn a_ui_subtable_alone_gains_an_explicit_ui_table() {
    let config = patched("[ui.toast]\ndelivery = \"terminal\"\n").unwrap();
    assert_eq!(scrollbars(&config), Some(false));
    assert!(config.contains("[ui]\n"), "{config}");
}

#[test]
fn dotted_and_inline_ui_tables_are_extended_in_place() {
    let dotted = patched("ui.hide_tab_bar_when_single_tab = true\n").unwrap();
    assert_eq!(scrollbars(&dotted), Some(false));
    let inline = patched("ui = { hide_tab_bar_when_single_tab = true }\n").unwrap();
    assert_eq!(scrollbars(&inline), Some(false));
}

#[test]
fn a_config_this_cannot_edit_is_left_alone() {
    assert_eq!(patched("ui = 1\n"), None);
    assert_eq!(patched("[ui\n"), None);
}

fn executable(path: &Path, body: &str) {
    fs::write(path, format!("#!/bin/sh\n{body}\n")).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
}

/// Run `script` in a scratch home with `bin` ahead of the system tools.
fn sh(script: &str, home: &Path, bin: &Path, env: &[(&str, &Path)]) -> (Option<i32>, String) {
    let mut command = Command::new("/bin/sh");
    command
        .args(["-c", script])
        .env_clear()
        .env("HOME", home)
        .env("PATH", format!("{}:/usr/bin:/bin", bin.display()));
    for (key, value) in env {
        command.env(key, value);
    }
    let output = command.output().unwrap();
    (
        output.status.code(),
        String::from_utf8_lossy(&output.stdout).into_owned(),
    )
}

#[test]
fn the_read_script_finds_the_config_where_herdr_does() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join("bin");
    fs::create_dir(&bin).unwrap();
    let home = dir.path().join("home");
    fs::create_dir_all(home.join(".config/herdr")).unwrap();
    fs::write(home.join(".config/herdr/config.toml"), "home = 1\n").unwrap();
    let xdg = dir.path().join("xdg");
    fs::create_dir_all(xdg.join("herdr")).unwrap();
    fs::write(xdg.join("herdr/config.toml"), "xdg = 1\n").unwrap();
    let explicit = dir.path().join("explicit.toml");
    fs::write(&explicit, "explicit = 1\n").unwrap();

    let script = read_script();
    assert_eq!(
        sh(&script, &home, &bin, &[]),
        (Some(0), "home = 1\n".into())
    );
    assert_eq!(
        sh(&script, &home, &bin, &[("XDG_CONFIG_HOME", &xdg)]),
        (Some(0), "xdg = 1\n".into())
    );
    assert_eq!(
        sh(
            &script,
            &home,
            &bin,
            &[("XDG_CONFIG_HOME", &xdg), ("HERDR_CONFIG_PATH", &explicit)]
        ),
        (Some(0), "explicit = 1\n".into())
    );
    let empty = dir.path().join("empty");
    fs::create_dir(&empty).unwrap();
    assert_eq!(sh(&script, &empty, &bin, &[]), (Some(0), String::new()));
}

#[test]
fn the_write_script_writes_through_a_symlink_then_reloads_the_session() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join("bin");
    fs::create_dir(&bin).unwrap();
    executable(&bin.join("herdr"), r#"echo "herdr $*""#);
    let home = dir.path().join("home");
    fs::create_dir_all(home.join(".config/herdr")).unwrap();
    let target = dir.path().join("dotfiles.toml");
    fs::write(&target, "old\n").unwrap();
    std::os::unix::fs::symlink(&target, home.join(".config/herdr/config.toml")).unwrap();
    let config = "[ui]\nname = 'it''s $HOME `x`'\npane_scrollbars = false\n";

    let (status, output) = sh(&write_script(config, "work's"), &home, &bin, &[]);

    assert_eq!(status, Some(0), "{output}");
    assert_eq!(output, "herdr --session work's server reload-config\n");
    assert_eq!(fs::read_to_string(&target).unwrap(), config);
    assert!(
        fs::symlink_metadata(home.join(".config/herdr/config.toml"))
            .unwrap()
            .file_type()
            .is_symlink()
    );
}

#[test]
fn the_write_script_creates_a_missing_config() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join("bin");
    fs::create_dir(&bin).unwrap();
    executable(&bin.join("herdr"), "exit 0");

    let (status, _) = sh(&write_script("[ui]\n", "default"), dir.path(), &bin, &[]);

    assert_eq!(status, Some(0));
    assert_eq!(
        fs::read_to_string(dir.path().join(".config/herdr/config.toml")).unwrap(),
        "[ui]\n"
    );
}
