use super::*;
use herdr_client::protocol::{ClientShellPopupSurface, FrameData};

pub(crate) fn cell(symbol: &str) -> CellData {
    CellData {
        symbol: symbol.into(),
        fg: 0,
        bg: 0,
        modifier: 0,
        skip: false,
        hyperlink: None,
    }
}

pub(crate) fn frame(rows: &[&str]) -> FrameData {
    let width = rows.iter().map(|r| r.chars().count()).max().unwrap_or(0);
    let mut cells = Vec::new();
    for row in rows {
        let mut line: Vec<CellData> = row.chars().map(|c| cell(&c.to_string())).collect();
        line.resize(width, cell(" "));
        cells.extend(line);
    }
    FrameData {
        cells,
        width: width as u16,
        height: rows.len() as u16,
        cursor: Some(CursorState {
            x: 0,
            y: 0,
            visible: true,
            shape: 0,
        }),
        hyperlinks: vec![],
        graphics: vec![],
    }
}

fn encoded(prev: Option<&FrameData>, next: &FrameData) -> String {
    let mut out = Vec::new();
    encode(prev, next, &mut out);
    String::from_utf8(out).unwrap()
}

#[test]
fn first_frame_clears_and_draws_every_row() {
    let out = encoded(None, &frame(&["ab", "cd"]));
    assert!(out.starts_with("\x1b[0m\x1b[2J"));
    assert!(out.contains("\x1b[1;1H\x1b[0m\x1b[0mab"));
    assert!(out.contains("\x1b[2;1H\x1b[0m\x1b[0mcd"));
    assert!(out.ends_with("\x1b[1;1H\x1b[0 q\x1b[?25h"));
}

#[test]
fn unchanged_rows_are_skipped() {
    let before = frame(&["ab", "cd"]);
    let after = frame(&["ab", "xy"]);
    let out = encoded(Some(&before), &after);
    assert!(!out.contains("\x1b[2J"));
    assert!(!out.contains("ab"));
    assert!(out.contains("\x1b[2;1H"));
    assert!(out.contains("xy"));
}

#[test]
fn a_size_change_redraws_everything() {
    let out = encoded(Some(&frame(&["ab"])), &frame(&["ab", "cd"]));
    assert!(out.contains("\x1b[2J"));
    assert!(out.contains("ab"));
}

#[test]
fn control_characters_in_symbols_never_reach_the_terminal() {
    let mut hostile = frame(&["ab"]);
    hostile.cells[0].symbol = "\x1b]52;c;aGk=\x07".into();
    hostile.cells[1].symbol = "\u{9b}2J".into();
    let out = encoded(None, &hostile);
    assert!(!out.contains("\x1b]"));
    assert!(!out.contains('\x07'));
    assert!(!out.contains('\u{9b}'));
    assert!(out.contains("]52;c;aGk="));
}

#[test]
fn empty_symbols_paint_a_space() {
    let mut blank = frame(&["a"]);
    blank.cells[0].symbol.clear();
    assert!(encoded(None, &blank).contains("\x1b[0m "));
}

#[test]
fn wide_symbols_skip_their_continuation_and_resync_the_column() {
    let mut wide = frame(&["xxx"]);
    wide.cells[0].symbol = "界".into();
    wide.cells[1].skip = true;
    wide.cells[2].symbol = "z".into();
    let out = encoded(None, &wide);
    assert!(out.contains("界\x1b[3Gz"));
}

#[test]
fn colours_and_modifiers_become_sgr() {
    let mut styled = frame(&["abcd"]);
    styled.cells[0].fg = 2; // named red
    styled.cells[1].fg = 12; // named bright yellow
    styled.cells[1].bg = 0x0100_00c8;
    styled.cells[2].fg = 0x0211_2233;
    styled.cells[2].modifier = modifier::BOLD | modifier::UNDERLINE;
    styled.cells[3].modifier = modifier::REVERSED | modifier::STRIKETHROUGH;
    let out = encoded(None, &styled);
    assert!(out.contains("\x1b[0;31ma"));
    assert!(out.contains("\x1b[0;93;48;5;200mb"));
    assert!(out.contains("\x1b[0;1;4;38;2;17;34;51mc"));
    assert!(out.contains("\x1b[0;7;9md"));
}

#[test]
fn hidden_or_missing_cursors_are_hidden() {
    let mut hidden = frame(&["a"]);
    hidden.cursor = None;
    assert!(encoded(None, &hidden).ends_with("\x1b[?25l"));
}

#[test]
fn malformed_frames_encode_nothing() {
    let mut broken = frame(&["ab"]);
    broken.cells.pop();
    assert!(encoded(None, &broken).is_empty());
}

#[test]
fn popups_are_composed_centred_with_their_own_cursor() {
    let base = frame(&["......", "......", "......"]);
    let mut inner = frame(&["PP"]);
    inner.cursor = Some(CursorState {
        x: 1,
        y: 0,
        visible: true,
        shape: 0,
    });
    let popup = ClientShellPopupSurface {
        terminal_id: "t".into(),
        title: String::new(),
        width: None,
        height: None,
        frame: inner,
        mouse_reporting: false,
        sgr_pixel_mouse: false,
        pixel_width: 0,
        pixel_height: 0,
    };
    let composed = compose(&base, Some(&popup));
    let row: String = composed.cells[6..12]
        .iter()
        .map(|c| c.symbol.as_str())
        .collect();
    assert_eq!(row, "..PP..");
    let cursor = composed.cursor.as_ref().unwrap();
    assert_eq!((cursor.x, cursor.y), (3, 1));
    assert!(matches!(compose(&base, None), Cow::Borrowed(_)));
}

#[test]
fn wide_symbols_cover_an_unmarked_continuation_cell() {
    // Herdr's continuation cell after a wide glyph is a plain blank cell.
    let mut wide = frame(&["xxxx"]);
    wide.cells[0].symbol = "你".into();
    wide.cells[1].symbol = " ".into();
    wide.cells[2].symbol = "🐏".into();
    wide.cells[3].symbol = String::new();
    let out = encoded(None, &wide);
    assert!(out.contains("你\x1b[3G🐏"), "{out:?}");
    assert!(!out.contains("你\x1b[2G"));
}
