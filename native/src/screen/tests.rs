use super::*;
use crate::vt::tests::frame;
use herdr_client::protocol::{CursorState, PaneSurfacePane};

fn surface(frame: FrameData) -> PaneSurfaceFrame {
    PaneSurfaceFrame {
        boot_id: "boot".into(),
        projection_revision: 1,
        surface_revision: 1,
        panes: vec![PaneSurfacePane {
            pane_id: "p1".into(),
            content_revision: 1,
            rect: SurfaceRect {
                x: 0,
                y: 0,
                width: frame.width,
                height: frame.height,
            },
            inner_rect: SurfaceRect {
                x: 0,
                y: 0,
                width: frame.width,
                height: frame.height,
            },
            scrollbar_rect: None,
            scroll: None,
            focused: true,
            mouse_reporting: false,
            sgr_pixel_mouse: false,
            alternate_screen_active: false,
            pixel_width: 0,
            pixel_height: 0,
        }],
        frame,
        splits: vec![],
        popup: None,
        graphics: Default::default(),
    }
}

fn row_text(frame: &RenderFrame, row: u16) -> String {
    frame.text((0, row), (frame.cols - 1, row))
}

#[test]
fn surfaces_round_trip_through_ghostty() {
    let mut screen = Screen::new(Theme::DARK).unwrap();
    let mut grid = frame(&["hello world", "second line"]);
    grid.cursor = Some(CursorState {
        x: 3,
        y: 1,
        visible: true,
        shape: 6,
    });
    let shown = screen.show(&surface(grid)).unwrap();
    assert_eq!((shown.cols, shown.rows), (11, 2));
    assert_eq!(row_text(&shown, 0), "hello world");
    assert_eq!(row_text(&shown, 1), "second line");
    assert_eq!(
        (shown.cursor.x, shown.cursor.y, shown.cursor.visible),
        (3, 1, true)
    );
    assert_eq!(shown.cursor.shape, CursorShape::Bar);
    assert_eq!(shown.background, argb(Theme::DARK.background));
    assert_eq!(shown.panes.len(), 1);
    assert!(shown.panes[0].focused);
}

#[test]
fn later_frames_patch_changed_rows_only() {
    let mut screen = Screen::new(Theme::DARK).unwrap();
    screen.show(&surface(frame(&["aaaa", "bbbb"]))).unwrap();
    let shown = screen.show(&surface(frame(&["aaaa", "bcdb"]))).unwrap();
    assert_eq!(row_text(&shown, 0), "aaaa");
    assert_eq!(row_text(&shown, 1), "bcdb");
}

#[test]
fn resizing_redraws_at_the_new_size() {
    let mut screen = Screen::new(Theme::DARK).unwrap();
    screen.show(&surface(frame(&["abcdef", "ghijkl"]))).unwrap();
    let shown = screen.show(&surface(frame(&["xy", "zw", "uv"]))).unwrap();
    assert_eq!((shown.cols, shown.rows), (2, 3));
    assert_eq!(row_text(&shown, 2), "uv");
}

#[test]
fn wide_characters_occupy_two_cells_and_keep_later_columns() {
    let mut screen = Screen::new(Theme::DARK).unwrap();
    let mut grid = frame(&["xxxx"]);
    grid.cells[0].symbol = "界".into();
    grid.cells[1].skip = true;
    grid.cells[2].symbol = "a".into();
    grid.cells[3].symbol = "b".into();
    let shown = screen.show(&surface(grid)).unwrap();
    let wide = shown.runs.iter().find(|run| run.text == "界").unwrap();
    assert_eq!((wide.col, wide.cells), (0, 2));
    assert_ne!(wide.flags & flags::WIDE, 0);
    let ascii = shown.runs.iter().find(|run| run.text == "ab").unwrap();
    assert_eq!(ascii.col, 2);
    assert_eq!(row_text(&shown, 0), "界ab");
}

#[test]
fn styles_resolve_through_the_theme_palette() {
    let mut screen = Screen::new(Theme::LIGHT).unwrap();
    let mut grid = frame(&["ab"]);
    grid.cells[0].fg = 2; // named red
    grid.cells[0].modifier = 1; // bold
    grid.cells[1].modifier = 64; // reversed
    let shown = screen.show(&surface(grid)).unwrap();
    let red = shown.runs.iter().find(|run| run.text == "a").unwrap();
    assert_eq!(red.fg, argb(Theme::LIGHT.ansi[1]));
    assert_ne!(red.flags & flags::BOLD, 0);
    let reversed = shown.runs.iter().find(|run| run.text == "b").unwrap();
    assert_eq!(reversed.bg, argb(Theme::LIGHT.foreground));
    assert_ne!(reversed.flags & flags::BACKGROUND, 0);
}

#[test]
fn a_theme_change_recolours_the_default_background() {
    let mut screen = Screen::new(Theme::DARK).unwrap();
    screen.set_theme(Theme::LIGHT).unwrap();
    let shown = screen.show(&surface(frame(&["a"]))).unwrap();
    assert_eq!(shown.background, argb(Theme::LIGHT.background));
    assert_eq!(screen.theme(), Theme::LIGHT);
}

#[test]
fn blank_default_cells_produce_no_runs() {
    let mut screen = Screen::new(Theme::DARK).unwrap();
    let shown = screen.show(&surface(frame(&["a   b"]))).unwrap();
    let texts: Vec<_> = shown.runs.iter().map(|run| run.text.as_str()).collect();
    assert_eq!(texts, ["a", "b"]);
}

#[test]
fn wide_glyphs_survive_unmarked_continuation_cells() {
    let mut screen = Screen::new(Theme::DARK).unwrap();
    let mut grid = frame(&["xxxxx"]);
    grid.cells[0].symbol = "你".into();
    grid.cells[1].symbol = " ".into();
    grid.cells[2].symbol = "好".into();
    grid.cells[3].symbol = " ".into();
    grid.cells[4].symbol = "!".into();
    let shown = screen.show(&surface(grid)).unwrap();
    assert_eq!(row_text(&shown, 0), "你好!");
}
