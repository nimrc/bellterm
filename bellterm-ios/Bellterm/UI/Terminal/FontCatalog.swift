import Foundation

/// Terminal fonts: the Nerd Font Mono builds of popular coding fonts, so
/// prompts and TUIs that draw Nerd Font icons show them. JetBrains Mono's
/// regular and bold ship in the app; the rest download on request from the
/// nerd-fonts v3.5.1 release, pinned by size and SHA-256. Mirrors Android's
/// FontCatalog.kt; keep the hashes in step with the tag.
enum FontCatalog {
    static let base = URL(string: "https://raw.githubusercontent.com/ryanoasis/nerd-fonts/v3.5.1/patched-fonts")!

    static let fonts: [CatalogFont] = [
        CatalogFont(
            id: "jetbrains-mono",
            name: "JetBrains Mono",
            files: [
                FontFile(style: .regular, source: .bundled("JetBrainsMonoNerdFontMono-Regular.ttf")),
                FontFile(style: .bold, source: .bundled("JetBrainsMonoNerdFontMono-Bold.ttf")),
                FontFile(style: .italic, source: .download(path: "JetBrainsMono/Ligatures/JetBrainsMonoNerdFontMono-Italic.ttf", sha256: "31efd6ead98746f5b0afa1ee6dba60267ad48db36428360bee327bec10621f97", size: 2575368)),
                FontFile(style: .boldItalic, source: .download(path: "JetBrainsMono/Ligatures/JetBrainsMonoNerdFontMono-BoldItalic.ttf", sha256: "9dba502e00e35209f6ed2a151c7376c051657b067cdebbc6e52d06cb9002cf31", size: 2578280)),
            ]
        ),
        CatalogFont(
            id: "fira-code",
            name: "Fira Code",
            files: [
                FontFile(style: .regular, source: .download(path: "FiraCode/FiraCodeNerdFontMono-Regular.ttf", sha256: "53eebbaaa09c483ebb27fdd5cf21073cc4b760308e5d666728e9db2f41616c00", size: 2757772)),
                FontFile(style: .bold, source: .download(path: "FiraCode/FiraCodeNerdFontMono-Bold.ttf", sha256: "7435c06ecc46dc975d2c356bf9bf1d8bc2226fdb1a877e6e9580193aae429519", size: 2787616)),
            ]
        ),
        CatalogFont(
            id: "cascadia-code",
            name: "Cascadia Code",
            files: [
                FontFile(style: .regular, source: .download(path: "CascadiaCode/CaskaydiaCoveNerdFontMono-Regular.ttf", sha256: "4e1e371b188bca589ec1ee7df494ca7bbb187c1c38228c059e5705f4029a9dc0", size: 2931984)),
                FontFile(style: .bold, source: .download(path: "CascadiaCode/CaskaydiaCoveNerdFontMono-Bold.ttf", sha256: "fadc873712c25c12f1135709a6675f3a929bd624ddb77aecec3adb8db070592c", size: 2932056)),
                FontFile(style: .italic, source: .download(path: "CascadiaCode/CaskaydiaCoveNerdFontMono-Italic.ttf", sha256: "6d7a0d3eaa4d1c54597f04a560ed516e3cbdbc3e02afa63390eb146875d9979e", size: 2796720)),
                FontFile(style: .boldItalic, source: .download(path: "CascadiaCode/CaskaydiaCoveNerdFontMono-BoldItalic.ttf", sha256: "4652b0cf7c7fdd8b8200d9bf54847c31c560652fc95af46ede697fe65e79a629", size: 2796336)),
            ]
        ),
        CatalogFont(
            id: "source-code-pro",
            name: "Source Code Pro",
            files: [
                FontFile(style: .regular, source: .download(path: "SourceCodePro/SauceCodeProNerdFontMono-Regular.ttf", sha256: "cbfe6a91123b9d78a1b593cb2efa10a806a704490e3cea6b894078b86d67c0b1", size: 2495268)),
                FontFile(style: .bold, source: .download(path: "SourceCodePro/SauceCodeProNerdFontMono-Bold.ttf", sha256: "fedd1dcdfc4228621075dc8a61190474aace7617aff61d1faca877d340a40ff6", size: 2491888)),
                FontFile(style: .italic, source: .download(path: "SourceCodePro/SauceCodeProNerdFontMono-Italic.ttf", sha256: "a7f108988ed9c38001ba6e2554c537744d75ed326b7a64be1eacc0cb695969f2", size: 2455436)),
                FontFile(style: .boldItalic, source: .download(path: "SourceCodePro/SauceCodeProNerdFontMono-BoldItalic.ttf", sha256: "bc723ce781926ee93fbfb7585bd13e151f755ee2cdc08310e0e3200a753db8f3", size: 2458196)),
            ]
        ),
        CatalogFont(
            id: "ubuntu-mono",
            name: "Ubuntu Mono",
            files: [
                FontFile(style: .regular, source: .download(path: "UbuntuMono/UbuntuMonoNerdFontMono-Regular.ttf", sha256: "6ac1e06816ef535f4d28f564a855032783784731ff8c7c8ec62955a08bb83932", size: 2474552)),
                FontFile(style: .bold, source: .download(path: "UbuntuMono/UbuntuMonoNerdFontMono-Bold.ttf", sha256: "087dba61f187fc34ff7317de72b305f6c591311ab06f2c8009ad0d590d7d9ef6", size: 2458664)),
                FontFile(style: .italic, source: .download(path: "UbuntuMono/UbuntuMonoNerdFontMono-Italic.ttf", sha256: "e58637b8f4473e031eecdff18f494b727fc5b1544f3b5d53359572f6969f7f43", size: 2480756)),
                FontFile(style: .boldItalic, source: .download(path: "UbuntuMono/UbuntuMonoNerdFontMono-BoldItalic.ttf", sha256: "b186eaaf05c6a6e065c112a1111f8e52b68d5e96cfbc4d146cc7e8b876b5da8a", size: 2484192)),
            ]
        ),
    ]

    /// Draws Nerd Font icons for fonts that lack them.
    static let symbols = "SymbolsNerdFontMono-Regular.ttf"
}

enum FontStyle: String, CaseIterable, Sendable {
    case regular, bold, italic, boldItalic
}

/// One style of a catalogue font: bundled with the app, or downloaded and verified.
struct FontFile: Sendable, Hashable {
    enum Source: Sendable, Hashable {
        case bundled(String)
        case download(path: String, sha256: String, size: Int)
    }

    var style: FontStyle
    var source: Source

    var size: Int {
        if case .download(_, _, let size) = source { size } else { 0 }
    }
}

struct CatalogFont: Sendable, Identifiable, Hashable {
    var id: String
    var name: String
    var files: [FontFile]

    var downloadSize: Int { files.reduce(0) { $0 + $1.size } }
}
