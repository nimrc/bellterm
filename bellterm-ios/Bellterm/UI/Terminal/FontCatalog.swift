import Foundation

/// Terminal fonts: the Nerd Font Mono builds of popular coding fonts, so
/// prompts and TUIs that draw Nerd Font icons show them. Every file ships in
/// the app, gathered and checksummed at build time by
/// `scripts/fetch-fonts.sh`, so nothing is downloaded on the device.
enum FontCatalog {
    static let fonts: [CatalogFont] = [
        CatalogFont(id: "jetbrains-mono", name: "JetBrains Mono", files: [
            .regular: "JetBrainsMonoNerdFontMono-Regular.ttf",
            .bold: "JetBrainsMonoNerdFontMono-Bold.ttf",
            .italic: "JetBrainsMonoNerdFontMono-Italic.ttf",
            .boldItalic: "JetBrainsMonoNerdFontMono-BoldItalic.ttf",
        ]),
        CatalogFont(id: "source-code-pro", name: "Source Code Pro", files: [
            .regular: "SauceCodeProNerdFontMono-Regular.ttf",
            .bold: "SauceCodeProNerdFontMono-Bold.ttf",
            .italic: "SauceCodeProNerdFontMono-Italic.ttf",
            .boldItalic: "SauceCodeProNerdFontMono-BoldItalic.ttf",
        ]),
        CatalogFont(id: "ubuntu-mono", name: "Ubuntu Mono", files: [
            .regular: "UbuntuMonoNerdFontMono-Regular.ttf",
            .bold: "UbuntuMonoNerdFontMono-Bold.ttf",
            .italic: "UbuntuMonoNerdFontMono-Italic.ttf",
            .boldItalic: "UbuntuMonoNerdFontMono-BoldItalic.ttf",
        ]),
    ]

    /// Draws Nerd Font icons for fonts that lack them.
    static let symbols = "SymbolsNerdFontMono-Regular.ttf"
}

enum FontStyle: String, CaseIterable, Sendable {
    case regular, bold, italic, boldItalic
}

struct CatalogFont: Sendable, Identifiable, Hashable {
    var id: String
    var name: String
    /// File names in the app's `fonts` folder.
    var files: [FontStyle: String]
}
