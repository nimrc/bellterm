import CoreText
import UIKit

/// The faces the terminal draws with. Every face cascades to the bundled
/// Symbols Nerd Font, so icons in the Private Use Area draw even when the
/// chosen font lacks them.
struct TerminalFonts {
    var regular: CTFont
    var bold: CTFont
    var italic: CTFont
    var boldItalic: CTFont

    private static let regularName = "JetBrainsMonoNFM-Regular"
    private static let boldName = "JetBrainsMonoNFM-Bold"
    private static let symbolsName = "SymbolsNFM"

    /// Registers the bundled fonts for this process; safe to call repeatedly.
    static let registered: Void = {
        guard let folder = Bundle.main.url(forResource: "fonts", withExtension: nil),
              let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
        else { return }
        let fonts = files.filter { ["ttf", "otf"].contains($0.pathExtension.lowercased()) }
        CTFontManagerRegisterFontURLs(fonts as CFArray, .process, true, nil)
    }()

    static func bundled(size: CGFloat) -> TerminalFonts {
        _ = registered
        let cascade = [CTFontDescriptorCreateWithNameAndSize(symbolsName as CFString, size)] as CFArray
        func face(_ name: String, matrix: CGAffineTransform? = nil) -> CTFont {
            let attributes = [kCTFontNameAttribute: name, kCTFontCascadeListAttribute: cascade] as CFDictionary
            let descriptor = CTFontDescriptorCreateWithAttributes(attributes)
            guard var transform = matrix else { return CTFontCreateWithFontDescriptor(descriptor, size, nil) }
            return CTFontCreateWithFontDescriptor(descriptor, size, &transform)
        }
        // The bundled family has no italic; slant the upright faces.
        let slant = CGAffineTransform(a: 1, b: 0, c: 0.2, d: 1, tx: 0, ty: 0)
        return TerminalFonts(
            regular: face(regularName),
            bold: face(boldName),
            italic: face(regularName, matrix: slant),
            boldItalic: face(boldName, matrix: slant)
        )
    }

    func face(bold isBold: Bool, italic isItalic: Bool) -> CTFont {
        switch (isBold, isItalic) {
        case (true, true): boldItalic
        case (true, false): bold
        case (false, true): italic
        case (false, false): regular
        }
    }

    /// One cell: the advance of "M", and the line height with a little air.
    var cell: (width: CGFloat, height: CGFloat, baseline: CGFloat) {
        var glyph = CGGlyph()
        var character: UniChar = 0x4D
        CTFontGetGlyphsForCharacters(regular, &character, &glyph, 1)
        var advance = CGSize.zero
        CTFontGetAdvancesForGlyphs(regular, .horizontal, &glyph, &advance, 1)
        let ascent = CTFontGetAscent(regular)
        let descent = CTFontGetDescent(regular)
        let height = ((ascent + descent) * 1.1).rounded(.up)
        return (advance.width, height, ascent + (height - ascent - descent) / 2)
    }
}
