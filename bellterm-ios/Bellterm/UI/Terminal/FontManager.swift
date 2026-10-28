import CoreText
import CryptoKit
import Foundation
import Observation
import UIKit

/// A font the picker can offer.
struct FontChoice: Identifiable, Hashable {
    var id: String
    var name: String
    var catalog: CatalogFont?
    var available: Bool
    var nerd: Bool
}

/// Terminal fonts: the bundled default, catalogue fonts downloaded on request
/// (verified against pinned sizes and SHA-256 hashes before they are kept),
/// the system monospace face, and TTF/OTF files the user imports.
@MainActor
@Observable
final class FontManager {
    static let system = "system"
    static let custom = "custom:"

    /// Bumped whenever the set of available fonts changes.
    private(set) var revision = 0
    @ObservationIgnored private let directory: URL
    @ObservationIgnored private let customDirectory: URL
    @ObservationIgnored private var registered: Set<URL> = []

    init(directory: URL = AppFiles.directory.appending(path: "fonts")) {
        self.directory = directory
        customDirectory = directory.appending(path: "custom")
        try? FileManager.default.createDirectory(at: customDirectory, withIntermediateDirectories: true)
    }

    var choices: [FontChoice] {
        _ = revision
        return FontCatalog.fonts.map { FontChoice(id: $0.id, name: $0.name, catalog: $0, available: available($0), nerd: true) }
            + [FontChoice(id: Self.system, name: String(localized: "System monospace"), catalog: nil, available: true, nerd: false)]
            + customFiles.map {
                FontChoice(id: Self.custom + $0.lastPathComponent, name: $0.deletingPathExtension().lastPathComponent, catalog: nil, available: true, nerd: false)
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

    private func url(_ font: CatalogFont, _ file: FontFile) -> URL? {
        switch file.source {
        case .bundled(let name): Bundle.main.url(forResource: name, withExtension: nil, subdirectory: "fonts")
        case .download: directory.appending(path: "\(font.id)-\(file.style.rawValue).ttf")
        }
    }

    private func present(_ font: CatalogFont, _ file: FontFile) -> Bool {
        url(font, file).map { FileManager.default.fileExists(atPath: $0.path()) } ?? false
    }

    /// Usable once its regular face is bundled or downloaded.
    func available(_ font: CatalogFont) -> Bool {
        font.files.first { $0.style == .regular }.map { present(font, $0) } ?? false
    }

    func fullyDownloaded(_ font: CatalogFont) -> Bool {
        font.files.allSatisfy { present(font, $0) }
    }

    func hasDownloads(_ font: CatalogFont) -> Bool {
        font.files.contains { if case .download = $0.source { present(font, $0) } else { false } }
    }

    struct DownloadError: LocalizedError {
        var errorDescription: String?
    }

    /// Downloads every style not yet on the device, verifying each before
    /// keeping it. `progress` runs on the main actor, from 0 to 1.
    func download(_ font: CatalogFont, progress: (Double) -> Void) async throws {
        let missing = font.files.filter { !present(font, $0) }
        let total = max(1, missing.reduce(0) { $0 + $1.size })
        var done = 0
        for file in missing {
            guard case .download(let path, let sha256, let size) = file.source, let target = url(font, file) else { continue }
            let (temporary, response) = try await URLSession.shared.download(from: FontCatalog.base.appending(path: path))
            defer { try? FileManager.default.removeItem(at: temporary) }
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                throw DownloadError(errorDescription: String(localized: "The download failed."))
            }
            let data = try Data(contentsOf: temporary, options: .mappedIfSafe)
            let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard data.count == size, hash == sha256 else {
                throw DownloadError(errorDescription: String(localized: "The downloaded font did not match its checksum."))
            }
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: temporary, to: target)
            done += size
            progress(Double(done) / Double(total))
        }
        revision += 1
    }

    func deleteDownloads(_ font: CatalogFont) {
        for file in font.files {
            if case .download = file.source, let target = url(font, file) {
                unregister(target)
                try? FileManager.default.removeItem(at: target)
            }
        }
        revision += 1
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
            throw DownloadError(errorDescription: String(localized: "That file is not a font."))
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
        let font = FontCatalog.fonts.first { $0.id == id && available($0) } ?? FontCatalog.fonts[0]
        var faces: [FontStyle: CTFontDescriptor] = [:]
        for file in font.files where present(font, file) {
            if let url = url(font, file), let descriptor = register(url) { faces[file.style] = descriptor }
        }
        guard let regular = faces[.regular] ?? (font.id == FontCatalog.fonts[0].id ? nil : self.faces(FontCatalog.fonts[0].id).regular) else {
            return FontFaces(regular: CTFontCopyFontDescriptor(UIFont.monospacedSystemFont(ofSize: 13, weight: .regular) as CTFont))
        }
        return FontFaces(regular: regular, bold: faces[.bold], italic: faces[.italic], boldItalic: faces[.boldItalic])
    }

    /// The bundled Symbols Nerd Font every face cascades to.
    var symbols: CTFontDescriptor? {
        Bundle.main.url(forResource: FontCatalog.symbols, withExtension: nil, subdirectory: "fonts").flatMap(register)
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
