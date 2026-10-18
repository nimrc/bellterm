//! Terminal colours. Kotlin owns the catalogue of colour schemes and sends the
//! active one as ARGB hex; Herdr's named colours index its ANSI palette, and
//! the same colours are reported to Herdr as the host terminal's theme.

use crate::{Error, Result};
use libghostty_vt::{
    Terminal,
    style::{Palette, PaletteIndex, RgbColor},
};
use serde::Deserialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(try_from = "ThemeDocument")]
pub struct Theme {
    pub dark: bool,
    pub foreground: RgbColor,
    pub background: RgbColor,
    pub cursor: RgbColor,
    pub ansi: [RgbColor; 16],
}

#[derive(Deserialize)]
struct ThemeDocument {
    dark: bool,
    foreground: String,
    background: String,
    cursor: String,
    ansi: [String; 16],
}

impl TryFrom<ThemeDocument> for Theme {
    type Error = Error;

    fn try_from(document: ThemeDocument) -> Result<Self> {
        let mut ansi = [RgbColor::default(); 16];
        for (slot, value) in ansi.iter_mut().zip(&document.ansi) {
            *slot = parse(value)?;
        }
        Ok(Self {
            dark: document.dark,
            foreground: parse(&document.foreground)?,
            background: parse(&document.background)?,
            cursor: parse(&document.cursor)?,
            ansi,
        })
    }
}

/// `AARRGGBB` or `RRGGBB` hex; alpha is ignored.
fn parse(value: &str) -> Result<RgbColor> {
    let digits = value.trim_start_matches('#');
    let rgb = match digits.len() {
        6 | 8 => u32::from_str_radix(&digits[digits.len() - 6..], 16).ok(),
        _ => None,
    }
    .ok_or_else(|| Error::Colour(value.to_owned()))?;
    Ok(rgb_of(rgb))
}

const fn rgb_of(value: u32) -> RgbColor {
    RgbColor {
        r: (value >> 16) as u8,
        g: (value >> 8) as u8,
        b: value as u8,
    }
}

const fn ansi(values: [u32; 16]) -> [RgbColor; 16] {
    let mut out = [RgbColor { r: 0, g: 0, b: 0 }; 16];
    let mut i = 0;
    while i < 16 {
        out[i] = rgb_of(values[i]);
        i += 1;
    }
    out
}

impl Theme {
    /// Used until Kotlin sends a scheme, and by tests.
    pub const DARK: Self = Self {
        dark: true,
        foreground: rgb_of(0xd8dee9),
        background: rgb_of(0x14161b),
        cursor: rgb_of(0x7fd6d2),
        ansi: ansi([
            0x1d2027, 0xef6b73, 0x8fc97e, 0xe8c27a, 0x6ab0f3, 0xc595e8, 0x5ec4c8, 0xc8ccd4,
            0x5c6370, 0xff858c, 0xa6e095, 0xf5d491, 0x8cc4ff, 0xd7aef5, 0x7fdade, 0xf2f4f8,
        ]),
    };

    pub const LIGHT: Self = Self {
        dark: false,
        foreground: rgb_of(0x2b2f36),
        background: rgb_of(0xfbfaf7),
        cursor: rgb_of(0x00696a),
        ansi: ansi([
            0x2b2f36, 0xc4323d, 0x3d8a2f, 0x9a6b00, 0x1f63c4, 0x8a3fb8, 0x137f86, 0xa0a4ab,
            0x6b7079, 0xdb4652, 0x4c9e3d, 0xb07d0a, 0x2e75d6, 0x9c50cc, 0x1a9199, 0xd8dade,
        ]),
    };

    fn palette(&self) -> Palette {
        let mut palette = Palette::default();
        for (index, color) in (0u8..).zip(self.ansi) {
            palette.set(PaletteIndex(index), color);
        }
        palette
    }

    pub fn apply(&self, terminal: &mut Terminal<'static, 'static>) -> Result<()> {
        terminal
            .set_default_fg_color(Some(self.foreground))?
            .set_default_bg_color(Some(self.background))?
            .set_default_cursor_color(Some(self.cursor))?
            .set_default_color_palette(Some(self.palette()))?;
        Ok(())
    }

    /// The colours pane applications get when they ask the host terminal,
    /// and the appearance Herdr picks its own light or dark override from.
    pub fn host(&self) -> herdr_client::HostTheme {
        use herdr_client::protocol::{ClientHostAppearance, ClientHostColor};
        let color = |c: RgbColor| ClientHostColor {
            r: c.r,
            g: c.g,
            b: c.b,
        };
        herdr_client::HostTheme {
            foreground: color(self.foreground),
            background: color(self.background),
            palette: self.palette().0.map(color),
            appearance: if self.dark {
                ClientHostAppearance::Dark
            } else {
                ClientHostAppearance::Light
            },
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn colours_parse_with_or_without_alpha() {
        assert_eq!(parse("ff102030").unwrap(), rgb_of(0x102030));
        assert_eq!(parse("#102030").unwrap(), rgb_of(0x102030));
        assert!(matches!(parse("12345"), Err(Error::Colour(_))));
        assert!(matches!(parse("zzzzzz"), Err(Error::Colour(_))));
    }

    #[test]
    fn host_theme_carries_the_scheme() {
        let host = Theme::LIGHT.host();
        assert_eq!(host.background.r, 0xfb);
        assert_eq!(host.palette[1].r, 0xc4);
        assert_eq!(
            host.appearance,
            herdr_client::protocol::ClientHostAppearance::Light
        );
    }
}
