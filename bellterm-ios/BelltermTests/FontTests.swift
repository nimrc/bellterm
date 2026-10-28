import CoreText
import Foundation
import Testing
@testable import Bellterm

struct FontCatalogTests {
    @Test func everyDownloadIsPinned() {
        #expect(Set(FontCatalog.fonts.map(\.id)).count == FontCatalog.fonts.count)
        for font in FontCatalog.fonts {
            #expect(font.files.contains { $0.style == .regular }, "\(font.id) has no regular face")
            for file in font.files {
                guard case .download(let path, let sha256, let size) = file.source else { continue }
                #expect(path.hasSuffix(".ttf") && size > 0)
                #expect(sha256.count == 64 && sha256.allSatisfy(\.isHexDigit))
            }
        }
    }
}

@MainActor
struct FontManagerTests {
    private func manager() -> FontManager {
        FontManager(directory: URL.temporaryDirectory.appending(path: "fonts-\(UUID().uuidString)"))
    }

    private func name(_ descriptor: CTFontDescriptor?) -> String? {
        descriptor.flatMap { CTFontDescriptorCopyAttribute($0, kCTFontNameAttribute) as? String }
    }

    @Test func bundledFontLoadsWithItsBoldFace() {
        let faces = manager().faces("jetbrains-mono")
        #expect(name(faces.regular) == "JetBrainsMonoNFM-Regular")
        #expect(name(faces.bold) == "JetBrainsMonoNFM-Bold")
        #expect(name(manager().symbols) == "SymbolsNFM")
    }

    @Test func missingFontsFallBackToTheBundledOne() {
        let fonts = manager()
        #expect(!fonts.available(FontCatalog.fonts[1]))
        #expect(name(fonts.faces("fira-code").regular) == "JetBrainsMonoNFM-Regular")
        #expect(name(fonts.faces("custom:gone.ttf").regular) == "JetBrainsMonoNFM-Regular")
    }

    @Test func importRejectsNonFonts() throws {
        let fonts = manager()
        let file = URL.temporaryDirectory.appending(path: "not-a-font-\(UUID().uuidString).ttf")
        try Data("hello".utf8).write(to: file)
        #expect(throws: Error.self) { try fonts.importCustom(from: file) }
        #expect(!fonts.choices.contains { $0.id.hasPrefix(FontManager.custom) })
    }

    @Test func synthesisedStylesStillDraw() {
        let terminal = TerminalFonts(FontFaces(regular: manager().faces("jetbrains-mono").regular), size: 13, symbols: nil)
        #expect(terminal.cell.width > 0 && terminal.cell.height > terminal.cell.width)
        #expect(CTFontGetSymbolicTraits(terminal.bold).contains(.traitBold) || terminal.bold != terminal.regular)
    }
}
