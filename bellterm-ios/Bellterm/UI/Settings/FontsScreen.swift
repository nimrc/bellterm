import SwiftUI
import UniformTypeIdentifiers

/// Picks the terminal font: catalogue fonts download on request, the system
/// monospace face is always there, and any TTF/OTF can be imported.
struct FontsScreen: View {
    @Bindable var settings: Settings
    let fonts: FontManager

    @State private var progress: [String: Double] = [:]
    @State private var importing = false
    @State private var failure: String?

    var body: some View {
        List {
            Section {
                ForEach(fonts.choices.filter { $0.catalog != nil }) { choice in
                    if let font = choice.catalog { catalogRow(choice, font) }
                }
            } header: {
                Text("Nerd Fonts")
            } footer: {
                Text("Downloaded from the nerd-fonts v3.5.1 release; each file is checked against a pinned size and SHA-256.")
            }
            Section {
                ForEach(fonts.choices.filter { $0.catalog == nil }) { choice in
                    selectable(choice) {
                        Text(choice.name)
                    }
                    .swipeActions {
                        if choice.id.hasPrefix(FontManager.custom) {
                            Button("Delete", systemImage: "trash", role: .destructive) {
                                if settings.font == choice.id { settings.font = FontCatalog.fonts[0].id }
                                fonts.deleteCustom(choice.id)
                            }
                        }
                    }
                }
                Button("Import font file…", systemImage: "square.and.arrow.down") { importing = true }
            } header: {
                Text("Other fonts")
            } footer: {
                Text("Fonts without Nerd Font icons draw them from the bundled Symbols Nerd Font.")
            }
            if let failure {
                Text(failure).foregroundStyle(.red)
            }
        }
        .navigationTitle("Font")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.font]) { result in
            do {
                settings.font = try fonts.importCustom(from: result.get())
                failure = nil
            } catch {
                failure = error.localizedDescription
            }
        }
    }

    private func catalogRow(_ choice: FontChoice, _ font: CatalogFont) -> some View {
        selectable(choice) {
            VStack(alignment: .leading, spacing: 2) {
                Text(choice.name)
                if let fraction = progress[font.id] {
                    ProgressView(value: fraction)
                } else if !fonts.fullyDownloaded(font) {
                    Text(choice.available
                        ? "Some styles not downloaded"
                        : "\(ByteCountFormatter.string(fromByteCount: Int64(font.downloadSize), countStyle: .file)) to download")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        } trailing: {
            if progress[font.id] == nil, !fonts.fullyDownloaded(font) {
                Button("Download", systemImage: "arrow.down.circle") { download(font) }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
            }
        }
        .swipeActions {
            if fonts.hasDownloads(font) {
                Button("Delete", systemImage: "trash", role: .destructive) {
                    fonts.deleteDownloads(font)
                    if settings.font == font.id, !fonts.available(font) { settings.font = FontCatalog.fonts[0].id }
                }
            }
        }
    }

    /// A row that selects its font when tapped, if the font is usable yet.
    private func selectable(
        _ choice: FontChoice,
        @ViewBuilder label: () -> some View,
        @ViewBuilder trailing: () -> some View = { EmptyView() }
    ) -> some View {
        HStack {
            Button {
                if choice.available { settings.font = choice.id }
            } label: {
                HStack {
                    label()
                    Spacer()
                    if settings.font == choice.id { Image(systemName: "checkmark").foregroundStyle(.tint) }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(!choice.available)
            trailing()
        }
    }

    private func download(_ font: CatalogFont) {
        progress[font.id] = 0
        Task {
            do {
                try await fonts.download(font) { progress[font.id] = $0 }
                failure = nil
                if settings.font != font.id, fonts.available(font) { settings.font = font.id }
            } catch {
                failure = error.localizedDescription
            }
            progress[font.id] = nil
        }
    }
}
