//! The libghostty-vt terminal that holds a connection's surface. Daemon frames
//! are written into it as VT (see `vt`), and frames for the view are read back
//! from its render state, so grapheme clustering, wide characters, colour
//! resolution, and cursor state all come from Ghostty. libghostty types are
//! `!Send`: a `Screen` lives and dies on the thread that created it.

use crate::{
    Result,
    render::{Cursor, CursorShape, Pane, Rect, RenderFrame, Run, flags},
    theme::Theme,
    vt,
};
use herdr_client::protocol::{FrameData, PaneSurfaceFrame, SurfaceRect};
use libghostty_vt::{
    RenderState, Terminal,
    render::{CellIterator, CursorVisualStyle, RowIterator},
    screen::CellWide,
    style::RgbColor,
    terminal::Options,
};

pub struct Screen {
    terminal: Terminal<'static, 'static>,
    state: RenderState<'static>,
    rows: RowIterator<'static>,
    cells: CellIterator<'static>,
    /// The grid last written, popup included, which the next frame is
    /// diffed against.
    shown: Option<FrameData>,
    vt: Vec<u8>,
    theme: Theme,
}

impl Screen {
    pub fn new(theme: Theme) -> Result<Self> {
        let mut terminal = Terminal::new(Options {
            cols: 80,
            rows: 24,
            // The daemon keeps scrollback; this grid is only the visible surface.
            max_scrollback: 0,
        })?;
        theme.apply(&mut terminal)?;
        terminal.vt_write(vt::SETUP);
        Ok(Self {
            terminal,
            state: RenderState::new()?,
            rows: RowIterator::new()?,
            cells: CellIterator::new()?,
            shown: None,
            vt: Vec::new(),
            theme,
        })
    }

    pub fn set_theme(&mut self, theme: Theme) -> Result<()> {
        theme.apply(&mut self.terminal)?;
        self.theme = theme;
        Ok(())
    }

    pub fn theme(&self) -> Theme {
        self.theme
    }

    /// Bring the terminal up to `surface`, then read it back for drawing.
    pub fn show(&mut self, surface: &PaneSurfaceFrame) -> Result<RenderFrame> {
        let grid = vt::compose(&surface.frame, surface.popup.as_deref());
        let resized = self
            .shown
            .as_ref()
            .is_none_or(|shown| (shown.width, shown.height) != (grid.width, grid.height));
        if resized && grid.width > 0 && grid.height > 0 {
            self.terminal.resize(grid.width, grid.height, 0, 0)?;
            self.shown = None;
        }
        self.vt.clear();
        vt::encode(self.shown.as_ref(), &grid, &mut self.vt);
        self.terminal.vt_write(&self.vt);
        self.shown = Some(grid.into_owned());
        let mut frame = self.read()?;
        frame.panes = surface
            .panes
            .iter()
            .map(|pane| Pane {
                id: pane.pane_id.clone(),
                rect: rect(pane.inner_rect),
                focused: pane.focused,
                mouse_reporting: pane.mouse_reporting,
                alternate_screen: pane.alternate_screen_active,
            })
            .collect();
        frame.popup = surface.popup.as_ref().map(|popup| {
            let (x, y) = vt::popup_origin(&surface.frame, &popup.frame);
            Rect {
                x,
                y,
                width: popup.frame.width,
                height: popup.frame.height,
            }
        });
        Ok(frame)
    }

    /// Read the whole grid back from libghostty's render state as style runs.
    fn read(&mut self) -> Result<RenderFrame> {
        let snapshot = self.state.update(&self.terminal)?;
        let colors = snapshot.colors()?;
        let mut frame = RenderFrame {
            cols: snapshot.cols()?,
            rows: snapshot.rows()?,
            foreground: argb(colors.foreground),
            background: argb(colors.background),
            cursor_color: argb(colors.cursor.unwrap_or(colors.foreground)),
            ..RenderFrame::default()
        };
        if let Some(viewport) = snapshot.cursor_viewport()? {
            frame.cursor = Cursor {
                x: viewport.x,
                y: viewport.y,
                visible: snapshot.cursor_visible()?,
                shape: match snapshot.cursor_visual_style()? {
                    CursorVisualStyle::Bar => CursorShape::Bar,
                    CursorVisualStyle::Underline => CursorShape::Underline,
                    CursorVisualStyle::BlockHollow => CursorShape::Hollow,
                    _ => CursorShape::Block,
                },
            };
        }
        let mut rows = self.rows.update(&snapshot)?;
        let mut text = String::new();
        let mut y = 0u16;
        while let Some(row) = rows.next() {
            let mut cells = self.cells.update(row)?;
            let mut x = 0u16;
            let mut open: Option<Run> = None;
            while let Some(cell) = cells.next() {
                let column = x;
                x += 1;
                let width = match cell.raw_cell()?.wide()? {
                    CellWide::SpacerTail | CellWide::SpacerHead => continue,
                    CellWide::Wide => 2,
                    CellWide::Narrow => 1,
                };
                text.clear();
                if cell.graphemes_len()? > 0 {
                    cell.graphemes_utf8(&mut text)?;
                }
                let style = cell.style()?;
                let bg = cell.bg_color()?;
                let mut fg = cell.fg_color()?.unwrap_or(colors.foreground);
                let mut back = bg.unwrap_or(colors.background);
                let mut bits = 0;
                if bg.is_some() {
                    bits |= flags::BACKGROUND;
                }
                if style.inverse {
                    std::mem::swap(&mut fg, &mut back);
                    bits |= flags::BACKGROUND;
                }
                for (on, bit) in [
                    (style.bold, flags::BOLD),
                    (style.italic, flags::ITALIC),
                    (
                        style.underline != libghostty_vt::style::Underline::None,
                        flags::UNDERLINE,
                    ),
                    (style.strikethrough, flags::STRIKETHROUGH),
                    (style.faint, flags::FAINT),
                    (style.invisible, flags::INVISIBLE),
                ] {
                    if on {
                        bits |= bit;
                    }
                }
                let blank = text.is_empty() || text == " ";
                if blank
                    && bits & (flags::BACKGROUND | flags::UNDERLINE | flags::STRIKETHROUGH) == 0
                {
                    // Nothing to paint over the default background.
                    if let Some(run) = open.take() {
                        frame.runs.push(run);
                    }
                    continue;
                }
                if blank {
                    text.clear();
                    text.push(' ');
                }
                let ascii = width == 1 && text.len() == 1 && text.as_bytes()[0].is_ascii_graphic()
                    || text == " ";
                if width == 2 {
                    bits |= flags::WIDE;
                }
                if ascii {
                    bits |= flags::ASCII;
                }
                let (fg, back) = (argb(fg), argb(back));
                if let Some(run) = open.as_mut()
                    && ascii
                    && run.flags == bits
                    && run.fg == fg
                    && run.bg == back
                    && run.col + run.cells == column
                {
                    run.cells += 1;
                    run.text.push_str(&text);
                    continue;
                }
                if let Some(run) = open.take() {
                    frame.runs.push(run);
                }
                let run = Run {
                    row: y,
                    col: column,
                    cells: width,
                    flags: bits,
                    fg,
                    bg: back,
                    text: text.clone(),
                };
                if ascii {
                    open = Some(run);
                } else {
                    frame.runs.push(run);
                }
            }
            if let Some(run) = open.take() {
                frame.runs.push(run);
            }
            y += 1;
        }
        Ok(frame)
    }
}

fn rect(rect: SurfaceRect) -> Rect {
    Rect {
        x: rect.x,
        y: rect.y,
        width: rect.width,
        height: rect.height,
    }
}

pub fn argb(color: RgbColor) -> u32 {
    0xff00_0000 | (u32::from(color.r) << 16) | (u32::from(color.g) << 8) | u32::from(color.b)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
