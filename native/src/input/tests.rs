use super::*;

fn code(event: &ClientPaneInputEvent) -> (&ClientKeyCode, u8) {
    match event {
        ClientPaneInputEvent::Key {
            code, modifiers, ..
        } => (code, *modifiers),
        other => panic!("not a key: {other:?}"),
    }
}

#[test]
fn editing_and_navigation_keys_map_to_herdr_codes() {
    use ClientKeyCode::*;
    let none = Modifiers::default();
    for (android, herdr) in [
        (keycode::ENTER, Enter),
        (keycode::NUMPAD_ENTER, Enter),
        (keycode::DEL, Backspace),
        (keycode::FORWARD_DEL, Delete),
        (keycode::TAB, Tab),
        (keycode::ESCAPE, Esc),
        (keycode::DPAD_UP, Up),
        (keycode::MOVE_END, End),
        (keycode::PAGE_DOWN, PageDown),
        (keycode::F1, F(1)),
        (keycode::F12, F(12)),
    ] {
        let event = key(android, none, None, false).unwrap();
        assert_eq!(code(&event).0, &herdr, "{android}");
    }
    let shifted = key(keycode::TAB, Modifiers(Modifiers::SHIFT), None, false).unwrap();
    assert_eq!(code(&shifted).0, &BackTab);
}

#[test]
fn plain_characters_are_left_to_text_commit() {
    assert_eq!(key(29, Modifiers::default(), Some('a'), false), None);
    assert_eq!(key(62, Modifiers::default(), Some(' '), false), None);
}

#[test]
fn ctrl_and_alt_chords_name_the_character() {
    let ctrl = key(
        29,
        Modifiers::from_meta_state(0x1000 | 0x1),
        Some('A'),
        false,
    )
    .unwrap();
    assert_eq!(
        code(&ctrl),
        (
            &ClientKeyCode::Char('a'),
            Modifiers::CTRL | Modifiers::SHIFT
        )
    );
    let alt = key(29, Modifiers::from_meta_state(0x2), Some('B'), false).unwrap();
    assert_eq!(code(&alt), (&ClientKeyCode::Char('B'), Modifiers::ALT));
    let space = key(62, Modifiers(Modifiers::CTRL), Some(' '), false).unwrap();
    assert_eq!(code(&space).0, &ClientKeyCode::Char(' '));
    assert!(chord('\u{1b}', Modifiers(Modifiers::CTRL)).is_none());
    assert_eq!(
        code(&chord('C', Modifiers(Modifiers::CTRL)).unwrap()).0,
        &ClientKeyCode::Char('c')
    );
}

#[test]
fn meta_state_maps_every_modifier() {
    assert_eq!(
        Modifiers::from_meta_state(0x1 | 0x2 | 0x1000 | 0x10000).0,
        0b1111
    );
    assert_eq!(Modifiers::from_meta_state(0).0, 0);
}

#[test]
fn repeats_are_marked() {
    let event = key(keycode::DPAD_LEFT, Modifiers::default(), None, true).unwrap();
    assert!(matches!(
        event,
        ClientPaneInputEvent::Key {
            kind: ClientKeyKind::Repeat,
            ..
        }
    ));
}

#[test]
fn committed_text_splits_lines_into_enter_and_drops_controls() {
    let events = text("ls\x1b[2J\nnext\n");
    assert_eq!(
        events,
        vec![
            ClientPaneInputEvent::TextCommit("ls[2J".into()),
            press(ClientKeyCode::Enter, Modifiers::default(), false),
            ClientPaneInputEvent::TextCommit("next".into()),
            press(ClientKeyCode::Enter, Modifiers::default(), false),
        ]
    );
    assert!(text("").is_empty());
    assert_eq!(
        text("a\tb"),
        vec![ClientPaneInputEvent::TextCommit("a\tb".into())]
    );
}

#[test]
fn touches_become_clicks_and_wheel_steps() {
    let tap = touch(Touch::Tap, 3, 4, 0);
    assert_eq!(tap.len(), 2);
    assert!(matches!(
        tap[0],
        ClientPaneInputEvent::Mouse {
            kind: ClientMouseKind::Down(ClientMouseButton::Left),
            position: ClientMousePosition::Cell { column: 3, row: 4 },
            ..
        }
    ));
    let scroll = touch(Touch::ScrollUp, 0, 0, 0);
    assert!(matches!(
        scroll[..],
        [ClientPaneInputEvent::Mouse {
            kind: ClientMouseKind::ScrollUp,
            lines: 1,
            ..
        }]
    ));
}
