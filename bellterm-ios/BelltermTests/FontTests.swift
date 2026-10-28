import CoreText
import Foundation
import Testing
@testable import Bellterm

struct FontCatalogTests {
    @Test func everyCatalogueFileShipsInTheApp() {
        #expect(Set(FontCatalog.fonts.map(\.id)).count == FontCatalog.fonts.count)
        for font in FontCatalog.fonts {
            #expect(Set(font.files.keys) == Set(FontStyle.allCases), "\(font.id) lacks a style")
            for name in font.files.values {
                #expect(Bundle.main.url(forResource: name, withExtension: nil, subdirectory: "fonts") != nil, "\(name) not bundled")
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

    @Test func catalogueFontsLoadEveryStyle() {
        let faces = manager().faces("ubuntu-mono")
        #expect(name(faces.regular) == "UbuntuMonoNFM")
        #expect(faces.bold != nil && faces.italic != nil && faces.boldItalic != nil)
        #expect(name(manager().faces("jetbrains-mono").boldItalic) == "JetBrainsMonoNFM-BoldItalic")
        #expect(name(manager().symbols) == "SymbolsNFM")
    }

    @Test func unknownFontsFallBackToTheDefault() {
        let fonts = manager()
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
