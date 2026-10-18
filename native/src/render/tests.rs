use super::*;

fn run(row: u16, col: u16, text: &str) -> Run {
    Run {
        row,
        col,
        cells: text.chars().count() as u16,
        flags: flags::ASCII,
        fg: 1,
        bg: 2,
        text: text.into(),
    }
}

fn sample() -> RenderFrame {
    RenderFrame {
        cols: 10,
        rows: 2,
        cursor: Cursor {
            x: 1,
            y: 1,
            visible: true,
            shape: CursorShape::Bar,
        },
        foreground: 0xffeeeeee,
        background: 0xff111111,
        cursor_color: 0xffaaaaaa,
        panes: vec![Pane {
            id: "p1".into(),
            rect: Rect {
                x: 0,
                y: 0,
                width: 10,
                height: 2,
            },
            focused: true,
            mouse_reporting: true,
            alternate_screen: false,
        }],
        popup: None,
        runs: vec![run(0, 0, "hello"), run(0, 7, "xyz"), run(1, 2, "ok")],
    }
}

#[test]
fn text_spans_rows_and_fills_gaps() {
    let frame = sample();
    assert_eq!(frame.text((0, 0), (9, 0)), "hello  xyz");
    assert_eq!(frame.text((1, 0), (3, 0)), "ell");
    assert_eq!(frame.text((7, 0), (3, 1)), "xyz\n  ok");
    assert_eq!(frame.text((3, 1), (7, 0)), "xyz\n  ok", "reversed ends");
}

#[test]
fn packing_has_the_documented_layout() {
    let packed = sample().pack();
    assert_eq!(
        u32::from_le_bytes(packed[0..4].try_into().unwrap()),
        FORMAT_VERSION
    );
    assert_eq!(u16::from_le_bytes([packed[4], packed[5]]), 10);
    assert_eq!(u16::from_le_bytes([packed[6], packed[7]]), 2);
    assert_eq!(packed[12], 1, "cursor visible");
    assert_eq!(packed[13], CursorShape::Bar as u8);
    // version, size, cursor, three colours, no popup.
    let panes = 4 + 4 + 6 + 12 + 1;
    assert_eq!(packed[panes - 1], 0);
    assert_eq!(u16::from_le_bytes([packed[panes], packed[panes + 1]]), 1);
    let pane_flags = packed[panes + 2 + 8];
    assert_eq!(pane_flags, 0b011);
    let runs = panes + 2 + 8 + 1 + 2 + 2;
    assert_eq!(
        u32::from_le_bytes(packed[runs..runs + 4].try_into().unwrap()),
        3
    );
    assert!(packed.ends_with(b"\x02\x00ok"));
}

#[test]
fn long_strings_are_cut_at_a_character_boundary() {
    let mut out = Vec::new();
    let text = "é".repeat(40_000);
    put_str(&mut out, &text);
    let len = usize::from(u16::from_le_bytes([out[0], out[1]]));
    assert!(len <= usize::from(u16::MAX));
    assert!(std::str::from_utf8(&out[2..]).is_ok());
}

/// The packed layout is a contract with `RenderFrame.kt`, whose unit test
/// parses this same fixture. Set `BELLTERM_BLESS=1` to rewrite it after
/// a deliberate format change, and bump `FORMAT_VERSION` with it.
#[test]
fn packed_frame_matches_the_kotlin_fixture() {
    let mut frame = sample();
    frame.popup = Some(Rect {
        x: 2,
        y: 0,
        width: 4,
        height: 1,
    });
    frame.runs.push(Run {
        row: 1,
        col: 6,
        cells: 2,
        flags: flags::WIDE | flags::BOLD | flags::BACKGROUND,
        fg: 0xff102030,
        bg: 0xff405060,
        text: "界".into(),
    });
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../app/src/test/resources/frame-v1.bin");
    if std::env::var_os("BELLTERM_BLESS").is_some() {
        std::fs::write(&path, frame.pack()).unwrap();
    }
    assert_eq!(std::fs::read(&path).unwrap(), frame.pack());
}
