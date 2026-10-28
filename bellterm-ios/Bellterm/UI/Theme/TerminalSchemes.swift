import SwiftUI

/// A terminal colour scheme: default colours and the 16 ANSI colours Herdr's named colours index.
struct TerminalScheme: Sendable, Identifiable, Equatable {
    var id: String
    var name: String
    var dark: Bool
    /// 0xRRGGBB.
    var foreground: UInt32
    var background: UInt32
    var cursor: UInt32
    var ansi: [UInt32]

    /// The document `theme.rs` reads.
    var document: [String: Any] {
        [
            "dark": dark,
            "foreground": Self.hex(foreground),
            "background": Self.hex(background),
            "cursor": Self.hex(cursor),
            "ansi": ansi.map(Self.hex),
        ]
    }

    var json: String {
        String(decoding: (try? JSONSerialization.data(withJSONObject: document)) ?? Data(), as: UTF8.self)
    }

    /// This scheme with its cursor in `rgb`, the session's node colour.
    func withCursor(_ rgb: UInt32) -> TerminalScheme {
        var copy = self
        copy.cursor = rgb & 0xffffff
        return copy
    }

    private static func hex(_ color: UInt32) -> String { String(format: "%06x", color & 0xffffff) }
}

/// Well-known schemes, each in its authors' published colours.
enum TerminalSchemes {
    static let all: [TerminalScheme] = [
        // keep-sorted start
        TerminalScheme(
            id: "bellterm-dark", name: "Bellterm Dark", dark: true, foreground: 0xd8dee9, background: 0x14161b, cursor: 0x7fd6d2,
            ansi: [
                0x1d2027, 0xef6b73, 0x8fc97e, 0xe8c27a, 0x6ab0f3, 0xc595e8, 0x5ec4c8, 0xc8ccd4,
                0x5c6370, 0xff858c, 0xa6e095, 0xf5d491, 0x8cc4ff, 0xd7aef5, 0x7fdade, 0xf2f4f8,
            ]
        ),
        TerminalScheme(
            id: "bellterm-light", name: "Bellterm Light", dark: false, foreground: 0x2b2f36, background: 0xfbfaf7, cursor: 0x00696a,
            ansi: [
                0x2b2f36, 0xc4323d, 0x3d8a2f, 0x9a6b00, 0x1f63c4, 0x8a3fb8, 0x137f86, 0xa0a4ab,
                0x6b7079, 0xdb4652, 0x4c9e3d, 0xb07d0a, 0x2e75d6, 0x9c50cc, 0x1a9199, 0xd8dade,
            ]
        ),
        TerminalScheme(
            id: "catppuccin-latte", name: "Catppuccin Latte", dark: false, foreground: 0x4c4f69, background: 0xeff1f5, cursor: 0xdc8a78,
            ansi: [
                0x5c5f77, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0xea76cb, 0x179299, 0xacb0be,
                0x6c6f85, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0xea76cb, 0x179299, 0xbcc0cc,
            ]
        ),
        TerminalScheme(
            id: "catppuccin-mocha", name: "Catppuccin Mocha", dark: true, foreground: 0xcdd6f4, background: 0x1e1e2e, cursor: 0xf5e0dc,
            ansi: [
                0x45475a, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xf5c2e7, 0x94e2d5, 0xbac2de,
                0x585b70, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xf5c2e7, 0x94e2d5, 0xa6adc8,
            ]
        ),
        TerminalScheme(
            id: "dracula", name: "Dracula", dark: true, foreground: 0xf8f8f2, background: 0x282a36, cursor: 0xf8f8f2,
            ansi: [
                0x21222c, 0xff5555, 0x50fa7b, 0xf1fa8c, 0xbd93f9, 0xff79c6, 0x8be9fd, 0xf8f8f2,
                0x6272a4, 0xff6e6e, 0x69ff94, 0xffffa5, 0xd6acff, 0xff92df, 0xa4ffff, 0xffffff,
            ]
        ),
        TerminalScheme(
            id: "github-light", name: "GitHub Light", dark: false, foreground: 0x1f2328, background: 0xffffff, cursor: 0x0969da,
            ansi: [
                0x24292f, 0xcf222e, 0x116329, 0x4d2d00, 0x0969da, 0x8250df, 0x1b7c83, 0x6e7781,
                0x57606a, 0xa40e26, 0x1a7f37, 0x633c01, 0x218bff, 0xa475f9, 0x3192aa, 0x8c959f,
            ]
        ),
        TerminalScheme(
            id: "gruvbox-dark", name: "Gruvbox Dark", dark: true, foreground: 0xebdbb2, background: 0x282828, cursor: 0xebdbb2,
            ansi: [
                0x282828, 0xcc241d, 0x98971a, 0xd79921, 0x458588, 0xb16286, 0x689d6a, 0xa89984,
                0x928374, 0xfb4934, 0xb8bb26, 0xfabd2f, 0x83a598, 0xd3869b, 0x8ec07c, 0xebdbb2,
            ]
        ),
        TerminalScheme(
            id: "gruvbox-light", name: "Gruvbox Light", dark: false, foreground: 0x3c3836, background: 0xfbf1c7, cursor: 0x3c3836,
            ansi: [
                0xfbf1c7, 0xcc241d, 0x98971a, 0xd79921, 0x458588, 0xb16286, 0x689d6a, 0x7c6f64,
                0x928374, 0x9d0006, 0x79740e, 0xb57614, 0x076678, 0x8f3f71, 0x427b58, 0x3c3836,
            ]
        ),
        TerminalScheme(
            id: "nord", name: "Nord", dark: true, foreground: 0xd8dee9, background: 0x2e3440, cursor: 0xd8dee9,
            ansi: [
                0x3b4252, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x88c0d0, 0xe5e9f0,
                0x4c566a, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x8fbcbb, 0xeceff4,
            ]
        ),
        TerminalScheme(
            id: "one-dark", name: "One Dark", dark: true, foreground: 0xabb2bf, background: 0x282c34, cursor: 0x528bff,
            ansi: [
                0x282c34, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xabb2bf,
                0x5c6370, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xffffff,
            ]
        ),
        TerminalScheme(
            id: "solarized-dark", name: "Solarized Dark", dark: true, foreground: 0x839496, background: 0x002b36, cursor: 0x93a1a1,
            ansi: [
                0x073642, 0xdc322f, 0x859900, 0xb58900, 0x268bd2, 0xd33682, 0x2aa198, 0xeee8d5,
                0x002b36, 0xcb4b16, 0x586e75, 0x657b83, 0x839496, 0x6c71c4, 0x93a1a1, 0xfdf6e3,
            ]
        ),
        TerminalScheme(
            id: "solarized-light", name: "Solarized Light", dark: false, foreground: 0x657b83, background: 0xfdf6e3, cursor: 0x586e75,
            ansi: [
                0x073642, 0xdc322f, 0x859900, 0xb58900, 0x268bd2, 0xd33682, 0x2aa198, 0xeee8d5,
                0x002b36, 0xcb4b16, 0x586e75, 0x657b83, 0x839496, 0x6c71c4, 0x93a1a1, 0xfdf6e3,
            ]
        ),
        TerminalScheme(
            id: "tokyo-night", name: "Tokyo Night", dark: true, foreground: 0xc0caf5, background: 0x1a1b26, cursor: 0xc0caf5,
            ansi: [
                0x15161e, 0xf7768e, 0x9ece6a, 0xe0af68, 0x7aa2f7, 0xbb9af7, 0x7dcfff, 0xa9b1d6,
                0x414868, 0xf7768e, 0x9ece6a, 0xe0af68, 0x7aa2f7, 0xbb9af7, 0x7dcfff, 0xc0caf5,
            ]
        ),
        // keep-sorted end
    ]

    static func byId(_ id: String, dark: Bool) -> TerminalScheme {
        all.first { $0.id == id } ?? all.first { $0.id == (dark ? "bellterm-dark" : "bellterm-light") } ?? all[0]
    }
}
