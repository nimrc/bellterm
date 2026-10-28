import CoreText
import UIKit

/// The faces a font offers, before a size is chosen. Missing styles are
/// synthesised from the regular face.
struct FontFaces {
    var regular: CTFontDescriptor
    var bold: CTFontDescriptor?
    var italic: CTFontDescriptor?
    var boldItalic: CTFontDescriptor?
}

/// The faces the terminal draws with. Every face cascades to the bundled
/// Symbols Nerd Font, so icons in the Private Use Area draw even when the
/// chosen font lacks them.
struct TerminalFonts {
    var regular: CTFont
    var bold: CTFont
    var italic: CTFont
    var boldItalic: CTFont

    private static let slant = CGAffineTransform(a: 1, b: 0, c: 0.2, d: 1, tx: 0, ty: 0)

    init(_ faces: FontFaces, size: CGFloat, symbols: CTFontDescriptor?) {
        let cascade = symbols.map { [CTFontDescriptorCreateCopyWithAttributes($0, [kCTFontSizeAttribute: size] as CFDictionary)] as CFArray }
        func font(_ descriptor: CTFontDescriptor, slanted: Bool = false) -> CTFont {
            let withCascade = cascade.map {
                CTFontDescriptorCreateCopyWithAttributes(descriptor, [kCTFontCascadeListAttribute: $0] as CFDictionary)
            } ?? descriptor
            guard slanted else { return CTFontCreateWithFontDescriptor(withCascade, size, nil) }
            var transform = Self.slant
            return CTFontCreateWithFontDescriptor(withCascade, size, &transform)
        }
        regular = font(faces.regular)
        let boldDescriptor = faces.bold
            ?? CTFontDescriptorCreateCopyWithSymbolicTraits(faces.regular, .traitBold, .traitBold)
        bold = boldDescriptor.map { font($0) } ?? regular
        italic = faces.italic.map { font($0) } ?? font(faces.regular, slanted: true)
        boldItalic = faces.boldItalic.map { font($0) } ?? boldDescriptor.map { font($0, slanted: true) } ?? italic
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
