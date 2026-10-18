//! Daemon cells as VT for libghostty. Herdr sends a rendered cell grid, not a
//! byte stream, so each changed row is redrawn with absolute positioning and
//! explicit SGR. Cell symbols are untrusted terminal content: control
//! characters are dropped so a symbol can never smuggle an escape sequence.

use herdr_client::protocol::{CellData, ClientShellPopupSurface, CursorState, FrameData};
use std::{borrow::Cow, fmt::Write as _};

const ESC: &str = "\x1b";

/// Modes the screen relies on: no autowrap, so the last column never
/// scrolls, and grapheme clustering for multi-codepoint symbols.
pub const SETUP: &[u8] = b"\x1b[?7l\x1b[?2027h\x1b[0m";

/// Modifier bits of [`CellData::modifier`], as Herdr encodes them.
mod modifier {
    pub const BOLD: u16 = 1;
    pub const DIM: u16 = 2;
    pub const ITALIC: u16 = 4;
    pub const UNDERLINE: u16 = 8;
    pub const SLOW_BLINK: u16 = 16;
    pub const RAPID_BLINK: u16 = 32;
    pub const REVERSED: u16 = 64;
    pub const HIDDEN: u16 = 128;
    pub const STRIKETHROUGH: u16 = 256;
}

/// The surface as one grid: the tab's frame with any popup drawn centred on
/// top, where Herdr's own clients place it.
pub fn compose<'a>(
    frame: &'a FrameData,
    popup: Option<&ClientShellPopupSurface>,
) -> Cow<'a, FrameData> {
    let Some(popup) = popup else {
        return Cow::Borrowed(frame);
    };
    let (x0, y0) = popup_origin(frame, &popup.frame);
    let mut composed = frame.clone();
    let (w, pw) = (usize::from(frame.width), usize::from(popup.frame.width));
    for (row, cells) in popup.frame.cells.chunks(pw.max(1)).enumerate() {
        let y = usize::from(y0) + row;
        if y >= usize::from(frame.height) {
            break;
        }
        for (col, cell) in cells.iter().enumerate() {
            let x = usize::from(x0) + col;
            if x >= w {
                break;
            }
            if let Some(slot) = composed.cells.get_mut(y * w + x) {
                *slot = CellData {
                    hyperlink: None,
                    ..cell.clone()
                };
            }
        }
    }
    composed.cursor = popup.frame.cursor.as_ref().map(|cursor| CursorState {
        x: cursor.x.saturating_add(x0),
        y: cursor.y.saturating_add(y0),
        ..cursor.clone()
    });
    Cow::Owned(composed)
}

/// Where a popup's top-left cell sits on the frame.
pub fn popup_origin(frame: &FrameData, popup: &FrameData) -> (u16, u16) {
    (
        frame.width.saturating_sub(popup.width) / 2,
        frame.height.saturating_sub(popup.height) / 2,
    )
}

/// Append the VT that turns `prev` into `next`. Rows equal in both are
/// skipped; any change of size, or no previous frame, redraws everything.
pub fn encode(prev: Option<&FrameData>, next: &FrameData, out: &mut Vec<u8>) {
    let width = usize::from(next.width);
    if width == 0 || next.cells.len() != width * usize::from(next.height) {
        return;
    }
    let mut text = String::new();
    let same_size = prev.is_some_and(|prev| {
        prev.width == next.width
            && prev.height == next.height
            && prev.cells.len() == next.cells.len()
    });
    if !same_size {
        text.push_str("\x1b[0m\x1b[2J");
    }
    for (y, row) in next.cells.chunks(width).enumerate() {
        if same_size
            && let Some(prev) = prev
            && prev.cells[y * width..(y + 1) * width] == *row
        {
            continue;
        }
        encode_row(y, row, &mut text);
    }
    encode_cursor(next.cursor.as_ref(), &mut text);
    out.extend_from_slice(text.as_bytes());
}

fn encode_row(y: usize, row: &[CellData], out: &mut String) {
    let _ = write!(out, "{ESC}[{};1H{ESC}[0m", y + 1);
    let mut style = None;
    // Cells up to here belong to a glyph already written.
    let mut covered = 0;
    for (x, cell) in row.iter().enumerate() {
        if cell.skip || x < covered {
            continue;
        }
        let next = (cell.fg, cell.bg, cell.modifier);
        if style != Some(next) {
            sgr(cell, out);
            style = Some(next);
        }
        let start = out.len();
        out.extend(cell.symbol.chars().filter(|c| !c.is_control()));
        if out.len() == start {
            out.push(' ');
        } else if !out[start..].is_ascii() {
            // Herdr measures a glyph by its symbol, and its continuation
            // cell need not be marked `skip`; the glyph covers it either way.
            // Resynchronise in case libghostty measures it differently, so
            // later cells stay put.
            covered = x + if is_wide(&cell.symbol) { 2 } else { 1 };
            covered += row[covered.min(row.len())..]
                .iter()
                .take_while(|c| c.skip)
                .count();
            if covered < row.len() {
                let _ = write!(out, "{ESC}[{}G", covered + 1);
            }
        }
    }
}

/// Whether Herdr gives `symbol` two columns, as its own renderers do: by
/// display width, with a halfwidth katakana plus (semi-)voiced mark as two.
fn is_wide(symbol: &str) -> bool {
    use unicode_width::UnicodeWidthStr;
    let mut chars = symbol.chars();
    symbol.width() > 1
        || matches!(
            (chars.next(), chars.next(), chars.next()),
            (
                Some('\u{ff66}'..='\u{ff9d}'),
                Some('\u{ff9e}' | '\u{ff9f}'),
                None
            )
        )
}

fn sgr(cell: &CellData, out: &mut String) {
    use modifier::*;
    out.push_str("\x1b[0");
    for (bit, code) in [
        (BOLD, "1"),
        (DIM, "2"),
        (ITALIC, "3"),
        (UNDERLINE, "4"),
        (SLOW_BLINK, "5"),
        (RAPID_BLINK, "5"),
        (REVERSED, "7"),
        (HIDDEN, "8"),
        (STRIKETHROUGH, "9"),
    ] {
        if cell.modifier & bit != 0 {
            out.push(';');
            out.push_str(code);
        }
    }
    color(cell.fg, false, out);
    color(cell.bg, true, out);
    out.push('m');
}

/// Herdr's colour encoding: `0x000000NN` is named colour NN-1 (1..=16, else
/// the default), `0x010000XX` is palette index XX, `0x02RRGGBB` is RGB.
fn color(value: u32, background: bool, out: &mut String) {
    let base = if background { 40 } else { 30 };
    match value >> 24 {
        0 => match value & 0xff {
            n @ 1..=8 => {
                let _ = write!(out, ";{}", base + n - 1);
            }
            n @ 9..=16 => {
                let _ = write!(out, ";{}", base + 60 + n - 9);
            }
            _ => {}
        },
        1 => {
            let _ = write!(out, ";{};5;{}", base + 8, value & 0xff);
        }
        2 => {
            let _ = write!(
                out,
                ";{};2;{};{};{}",
                base + 8,
                (value >> 16) & 0xff,
                (value >> 8) & 0xff,
                value & 0xff
            );
        }
        _ => {}
    }
}

fn encode_cursor(cursor: Option<&CursorState>, out: &mut String) {
    let _ = write!(out, "{ESC}[0m");
    match cursor {
        Some(cursor) => {
            let _ = write!(
                out,
                "{ESC}[{};{}H",
                u32::from(cursor.y) + 1,
                u32::from(cursor.x) + 1
            );
            if cursor.shape <= 6 {
                let _ = write!(out, "{ESC}[{} q", cursor.shape);
            }
            out.push_str(if cursor.visible {
                "\x1b[?25h"
            } else {
                "\x1b[?25l"
            });
        }
        None => out.push_str("\x1b[?25l"),
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
pub(crate) mod tests;
