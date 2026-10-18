//! What the Kotlin view draws: style runs read back from libghostty's render
//! state, plus the pane geometry that hit testing needs, packed into one
//! little-endian buffer so a frame crosses JNI as a single byte array.

/// Bits of [`Run::flags`]. Kotlin mirrors these in `RenderFrame.kt`.
pub mod flags {
    pub const BOLD: u16 = 1;
    pub const ITALIC: u16 = 2;
    pub const UNDERLINE: u16 = 4;
    pub const STRIKETHROUGH: u16 = 8;
    pub const FAINT: u16 = 16;
    /// A single character two cells wide.
    pub const WIDE: u16 = 32;
    pub const INVISIBLE: u16 = 64;
    /// The run paints its own background rather than the default.
    pub const BACKGROUND: u16 = 128;
    /// Printable ASCII only, one character per cell: drawable as one string.
    pub const ASCII: u16 = 256;
}

/// Bumped whenever the packed layout changes.
pub const FORMAT_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Run {
    pub row: u16,
    pub col: u16,
    pub cells: u16,
    pub flags: u16,
    /// Resolved colours as opaque ARGB.
    pub fg: u32,
    pub bg: u32,
    pub text: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CursorShape {
    #[default]
    Block,
    Bar,
    Underline,
    Hollow,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Cursor {
    pub x: u16,
    pub y: u16,
    pub visible: bool,
    pub shape: CursorShape,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rect {
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}

/// A pane's content area in surface cells.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pane {
    pub id: String,
    pub rect: Rect,
    pub focused: bool,
    pub mouse_reporting: bool,
    pub alternate_screen: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RenderFrame {
    pub cols: u16,
    pub rows: u16,
    pub cursor: Cursor,
    pub foreground: u32,
    pub background: u32,
    pub cursor_color: u32,
    pub panes: Vec<Pane>,
    /// The popup's area, when one is open; input goes to it, not a pane.
    pub popup: Option<Rect>,
    pub runs: Vec<Run>,
}

impl RenderFrame {
    /// Plain text of the cells from `start` to `end` inclusive, in reading
    /// order, with trailing blanks of each row trimmed.
    pub fn text(&self, start: (u16, u16), end: (u16, u16)) -> String {
        let (start, end) = if (start.1, start.0) <= (end.1, end.0) {
            (start, end)
        } else {
            (end, start)
        };
        let mut lines = Vec::new();
        for row in start.1..=end.1.min(self.rows.saturating_sub(1)) {
            let from = if row == start.1 { start.0 } else { 0 };
            let to = if row == end.1 {
                end.0
            } else {
                self.cols.saturating_sub(1)
            };
            let mut line = String::new();
            let mut col = from;
            for run in self.runs.iter().filter(|run| run.row == row) {
                if run.col + run.cells <= from || run.col > to {
                    continue;
                }
                while col < run.col {
                    line.push(' ');
                    col += 1;
                }
                if run.flags & flags::ASCII != 0 {
                    let skip = usize::from(from.saturating_sub(run.col));
                    let take = usize::from(to.min(run.col + run.cells - 1) + 1 - run.col.max(from));
                    line.extend(run.text.chars().skip(skip).take(take));
                } else {
                    line.push_str(&run.text);
                }
                col = run.col + run.cells;
            }
            lines.push(line.trim_end().to_owned());
        }
        lines.join("\n")
    }

    pub fn pack(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(64 + self.runs.len() * 24);
        put32(&mut out, FORMAT_VERSION);
        put16(&mut out, self.cols);
        put16(&mut out, self.rows);
        put16(&mut out, self.cursor.x);
        put16(&mut out, self.cursor.y);
        out.push(u8::from(self.cursor.visible));
        out.push(self.cursor.shape as u8);
        put32(&mut out, self.foreground);
        put32(&mut out, self.background);
        put32(&mut out, self.cursor_color);
        match self.popup {
            Some(rect) => {
                out.push(1);
                put_rect(&mut out, rect);
            }
            None => out.push(0),
        }
        put16(
            &mut out,
            u16::try_from(self.panes.len()).unwrap_or(u16::MAX),
        );
        for pane in self.panes.iter().take(usize::from(u16::MAX)) {
            put_rect(&mut out, pane.rect);
            out.push(
                u8::from(pane.focused)
                    | (u8::from(pane.mouse_reporting) << 1)
                    | (u8::from(pane.alternate_screen) << 2),
            );
            put_str(&mut out, &pane.id);
        }
        put32(&mut out, u32::try_from(self.runs.len()).unwrap_or(u32::MAX));
        for run in &self.runs {
            put16(&mut out, run.row);
            put16(&mut out, run.col);
            put16(&mut out, run.cells);
            put16(&mut out, run.flags);
            put32(&mut out, run.fg);
            put32(&mut out, run.bg);
            put_str(&mut out, &run.text);
        }
        out
    }
}

fn put16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn put32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn put_rect(out: &mut Vec<u8>, rect: Rect) {
    for value in [rect.x, rect.y, rect.width, rect.height] {
        put16(out, value);
    }
}

/// A length-prefixed UTF-8 string, cut at a character boundary if longer
/// than a `u16` length can say.
fn put_str(out: &mut Vec<u8>, text: &str) {
    let mut end = text.len().min(usize::from(u16::MAX));
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    put16(out, end as u16);
    out.extend_from_slice(&text.as_bytes()[..end]);
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
