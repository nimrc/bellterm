//! OSC 52 writes the daemon forwards as base64 `ServerMessage::Clipboard`.
//! Only bounded, valid UTF-8 text reaches the Android clipboard; anything
//! else is dropped rather than guessed at.

use base64::{
    Engine,
    engine::{
        DecodePaddingMode,
        general_purpose::{GeneralPurpose, GeneralPurposeConfig},
    },
};

/// The largest decoded write put on the clipboard.
pub const MAX_TEXT_BYTES: usize = 2 * 1024 * 1024;

const CONFIG: GeneralPurposeConfig =
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent);
const STANDARD: GeneralPurpose = GeneralPurpose::new(&base64::alphabet::STANDARD, CONFIG);
const URL_SAFE: GeneralPurpose = GeneralPurpose::new(&base64::alphabet::URL_SAFE, CONFIG);

pub fn decode(data: &str) -> Option<String> {
    if data.len() > MAX_TEXT_BYTES.div_ceil(3) * 4 + 4 {
        return None;
    }
    let bytes = STANDARD
        .decode(data)
        .or_else(|_| URL_SAFE.decode(data))
        .ok()?;
    if bytes.len() > MAX_TEXT_BYTES {
        return None;
    }
    String::from_utf8(bytes).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_text_and_rejects_everything_else() {
        assert_eq!(decode("aGVsbG8=").as_deref(), Some("hello"));
        assert_eq!(decode("aGVsbG8").as_deref(), Some("hello"));
        assert_eq!(decode("not base64!"), None);
        assert_eq!(decode("/w=="), None, "invalid UTF-8");
        assert_eq!(decode(&"A".repeat(MAX_TEXT_BYTES * 2)), None);
    }
}
