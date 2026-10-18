//! Android input as Herdr's semantic pane input. Printable text from the
//! soft keyboard or IME arrives committed and is sent as `TextCommit`; keys
//! are only for what text cannot say: editing and navigation keys, function
//! keys, and characters chorded with Ctrl or Alt (from a hardware keyboard or
//! the extra-keys row).

use herdr_client::protocol::{
    ClientKeyCode, ClientKeyKind, ClientMouseButton, ClientMouseKind, ClientMousePosition,
    ClientPaneInputEvent,
};

/// `android.view.KeyEvent` key codes this mapping understands.
mod keycode {
    pub const DPAD_UP: i32 = 19;
    pub const DPAD_DOWN: i32 = 20;
    pub const DPAD_LEFT: i32 = 21;
    pub const DPAD_RIGHT: i32 = 22;
    pub const TAB: i32 = 61;
    pub const SPACE: i32 = 62;
    pub const ENTER: i32 = 66;
    pub const DEL: i32 = 67;
    pub const PAGE_UP: i32 = 92;
    pub const PAGE_DOWN: i32 = 93;
    pub const ESCAPE: i32 = 111;
    pub const FORWARD_DEL: i32 = 112;
    pub const MOVE_HOME: i32 = 122;
    pub const MOVE_END: i32 = 123;
    pub const INSERT: i32 = 124;
    pub const F1: i32 = 131;
    pub const F12: i32 = 142;
    pub const NUMPAD_ENTER: i32 = 160;
}

/// Modifiers as Herdr's bits: shift=1, control=2, alt=4, super=8.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Modifiers(pub u8);

impl Modifiers {
    pub const SHIFT: u8 = 1;
    pub const CTRL: u8 = 2;
    pub const ALT: u8 = 4;
    pub const SUPER: u8 = 8;

    /// From `KeyEvent.getMetaState()`.
    pub fn from_meta_state(meta: i32) -> Self {
        const SHIFT_ON: i32 = 0x1;
        const ALT_ON: i32 = 0x2;
        const CTRL_ON: i32 = 0x1000;
        const META_ON: i32 = 0x10000;
        let mut bits = 0;
        for (mask, bit) in [
            (SHIFT_ON, Self::SHIFT),
            (ALT_ON, Self::ALT),
            (CTRL_ON, Self::CTRL),
            (META_ON, Self::SUPER),
        ] {
            if meta & mask != 0 {
                bits |= bit;
            }
        }
        Self(bits)
    }

    fn has(self, bit: u8) -> bool {
        self.0 & bit != 0
    }
}

/// A key press, or `None` when the key is plain text the keyboard commits on
/// its own (or a key Herdr has no code for).
pub fn key(
    code: i32,
    modifiers: Modifiers,
    unicode: Option<char>,
    repeat: bool,
) -> Option<ClientPaneInputEvent> {
    use ClientKeyCode::*;
    let chorded = modifiers.has(Modifiers::CTRL) || modifiers.has(Modifiers::ALT);
    let code = match code {
        keycode::ENTER | keycode::NUMPAD_ENTER => Enter,
        keycode::DEL => Backspace,
        keycode::FORWARD_DEL => Delete,
        keycode::TAB if modifiers.has(Modifiers::SHIFT) => BackTab,
        keycode::TAB => Tab,
        keycode::ESCAPE => Esc,
        keycode::DPAD_UP => Up,
        keycode::DPAD_DOWN => Down,
        keycode::DPAD_LEFT => Left,
        keycode::DPAD_RIGHT => Right,
        keycode::MOVE_HOME => Home,
        keycode::MOVE_END => End,
        keycode::PAGE_UP => PageUp,
        keycode::PAGE_DOWN => PageDown,
        keycode::INSERT => Insert,
        n @ keycode::F1..=keycode::F12 => F(u8::try_from(n - keycode::F1 + 1).ok()?),
        keycode::SPACE if chorded => Char(' '),
        _ if chorded => Char(chord_char(unicode?, modifiers)?),
        _ => return None,
    };
    Some(press(code, modifiers, repeat))
}

/// The character of a Ctrl or Alt chord. Ctrl chords name the base letter;
/// Alt keeps what Shift made of it, as Herdr's other clients send.
fn chord_char(ch: char, modifiers: Modifiers) -> Option<char> {
    if ch.is_control() {
        return None;
    }
    Some(if modifiers.has(Modifiers::CTRL) {
        ch.to_ascii_lowercase()
    } else {
        ch
    })
}

/// A character typed while the extra-keys row holds Ctrl or Alt.
pub fn chord(ch: char, modifiers: Modifiers) -> Option<ClientPaneInputEvent> {
    Some(press(
        ClientKeyCode::Char(chord_char(ch, modifiers)?),
        modifiers,
        false,
    ))
}

fn press(code: ClientKeyCode, modifiers: Modifiers, repeat: bool) -> ClientPaneInputEvent {
    ClientPaneInputEvent::Key {
        code,
        modifiers: modifiers.0,
        kind: if repeat {
            ClientKeyKind::Repeat
        } else {
            ClientKeyKind::Press
        },
        repeat_count: 1,
        shifted_codepoint: None,
        generated_text: None,
        tracks_release: false,
        physical_key_id: None,
        windows_record: None,
    }
}

/// Committed text without control characters other than tab and newline,
/// which a keyboard can legitimately produce. Newlines become Enter presses
/// so a multi-line commit submits like typing would.
pub fn text(text: &str) -> Vec<ClientPaneInputEvent> {
    let mut events = Vec::new();
    for (index, line) in text.split('\n').enumerate() {
        if index > 0 {
            events.push(press(ClientKeyCode::Enter, Modifiers::default(), false));
        }
        let line: String = line
            .chars()
            .filter(|c| *c == '\t' || !c.is_control())
            .collect();
        if !line.is_empty() {
            events.push(ClientPaneInputEvent::TextCommit(line));
        }
    }
    events
}

/// What a touch does at a pane cell, which Herdr turns into a click, a
/// focus change, or scrolling as the pane's mouse mode dictates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Touch {
    Tap,
    ScrollUp,
    ScrollDown,
}

pub fn touch(kind: Touch, column: u16, row: u16, lines: u16) -> Vec<ClientPaneInputEvent> {
    let mouse = |kind, lines| ClientPaneInputEvent::Mouse {
        kind,
        position: ClientMousePosition::Cell { column, row },
        geometry: None,
        modifiers: 0,
        lines,
    };
    match kind {
        Touch::Tap => vec![
            mouse(ClientMouseKind::Down(ClientMouseButton::Left), 1),
            mouse(ClientMouseKind::Up(ClientMouseButton::Left), 1),
        ],
        Touch::ScrollUp => vec![mouse(ClientMouseKind::ScrollUp, lines.max(1))],
        Touch::ScrollDown => vec![mouse(ClientMouseKind::ScrollDown, lines.max(1))],
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
