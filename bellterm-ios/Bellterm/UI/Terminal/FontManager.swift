import CoreText
import Foundation
import Observation
import UIKit

/// A font the picker can offer.
struct FontChoice: Identifiable, Hashable {
    var id: String
    var name: String
    var catalog: CatalogFont?
    var nerd: Bool
}

/// Terminal fonts: the catalogue bundled with the app, the system monospace
/// face, and TTF/OTF files the user imports.
@MainActor
@Observable
final class FontManager {
    static let system = "system"
    static let custom = "custom:"

    /// Bumped whenever the set of available fonts changes.
    private(set) var revision = 0
    @ObservationIgnored private let customDirectory: URL
    @ObservationIgnored private var registered: Set<URL> = []

    init(directory: URL = AppFiles.directory.appending(path: "fonts")) {
        customDirectory = directory.appending(path: "custom")
        try? FileManager.default.createDirectory(at: customDirectory, withIntermediateDirectories: true)
    }

    var choices: [FontChoice] {
        _ = revision
        return FontCatalog.fonts.map { FontChoice(id: $0.id, name: $0.name, catalog: $0, nerd: true) }
            + [FontChoice(id: Self.system, name: String(localized: "System monospace"), catalog: nil, nerd: false)]
            + customFiles.map {
                FontChoice(id: Self.custom + $0.lastPathComponent, name: $0.deletingPathExtension().lastPathComponent, catalog: nil, nerd: false)
            }
    }

    func displayName(_ id: String) -> String {
        choices.first { $0.id == id }?.name ?? FontCatalog.fonts[0].name
    }

    private var customFiles: [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: customDirectory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { ["ttf", "otf"].contains($0.pathExtension.lowercased()) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private static func bundled(_ name: String) -> URL? {
        Bundle.main.url(forResource: name, withExtension: nil, subdirectory: "fonts")
    }

    struct ImportError: LocalizedError {
        var errorDescription: String? { String(localized: "That file is not a font.") }
    }

    /// Copies a picked font file in; returns its id. Throws when it is not a usable font.
    func importCustom(from source: URL) throws -> String {
        let safe = source.lastPathComponent.filter { $0.isLetter || $0.isNumber || "-_. ".contains($0) }
        let target = customDirectory.appending(path: safe.isEmpty ? "font.ttf" : safe)
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        try? FileManager.default.removeItem(at: target)
        try FileManager.default.copyItem(at: source, to: target)
        guard Self.descriptors(target).first != nil else {
            try? FileManager.default.removeItem(at: target)
            throw ImportError()
        }
        revision += 1
        return Self.custom + target.lastPathComponent
    }

    func deleteCustom(_ id: String) {
        let target = customDirectory.appending(path: String(id.dropFirst(Self.custom.count)))
        unregister(target)
        try? FileManager.default.removeItem(at: target)
        revision += 1
    }

    /// The faces for `id`, falling back to the bundled default for anything missing.
    func faces(_ id: String) -> FontFaces {
        if id == Self.system {
            let size = UIFont.systemFontSize
            return FontFaces(
                regular: CTFontCopyFontDescriptor(UIFont.monospacedSystemFont(ofSize: size, weight: .regular) as CTFont),
                bold: CTFontCopyFontDescriptor(UIFont.monospacedSystemFont(ofSize: size, weight: .bold) as CTFont)
            )
        }
        if id.hasPrefix(Self.custom),
           let regular = register(customDirectory.appending(path: String(id.dropFirst(Self.custom.count)))) {
            return FontFaces(regular: regular)
        }
        let font = FontCatalog.fonts.first { $0.id == id } ?? FontCatalog.fonts[0]
        var faces: [FontStyle: CTFontDescriptor] = [:]
        for (style, name) in font.files {
            if let url = Self.bundled(name), let descriptor = register(url) { faces[style] = descriptor }
        }
        guard let regular = faces[.regular] ?? (font.id == FontCatalog.fonts[0].id ? nil : self.faces(FontCatalog.fonts[0].id).regular) else {
            return FontFaces(regular: CTFontCopyFontDescriptor(UIFont.monospacedSystemFont(ofSize: 13, weight: .regular) as CTFont))
        }
        return FontFaces(regular: regular, bold: faces[.bold], italic: faces[.italic], boldItalic: faces[.boldItalic])
    }

    /// The bundled Symbols Nerd Font every face cascades to.
    var symbols: CTFontDescriptor? {
        Self.bundled(FontCatalog.symbols).flatMap(register)
    }

    /// Registers a font file for this process once and returns its first face.
    private func register(_ url: URL) -> CTFontDescriptor? {
        if !registered.contains(url) {
            CTFontManagerRegisterFontURLs([url] as CFArray, .process, false, nil)
            registered.insert(url)
        }
        return Self.descriptors(url).first
    }

    private func unregister(_ url: URL) {
        guard registered.remove(url) != nil else { return }
        CTFontManagerUnregisterFontURLs([url] as CFArray, .process, nil)
    }

    private static func descriptors(_ url: URL) -> [CTFontDescriptor] {
        (CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor]) ?? []
    }
}
