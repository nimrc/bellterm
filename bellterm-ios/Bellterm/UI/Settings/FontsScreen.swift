import SwiftUI
import UniformTypeIdentifiers

/// Picks the terminal font: the bundled Nerd Fonts, the system monospace
/// face, or any imported TTF/OTF.
struct FontsScreen: View {
    @Bindable var settings: Settings
    let fonts: FontManager

    @State private var importing = false
    @State private var failure: String?

    var body: some View {
        List {
            Section("Nerd Fonts") {
                ForEach(fonts.choices.filter { $0.catalog != nil }) { row($0) }
            }
            Section {
                ForEach(fonts.choices.filter { $0.catalog == nil }) { choice in
                    row(choice)
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

    private func row(_ choice: FontChoice) -> some View {
        Button {
            settings.font = choice.id
        } label: {
            HStack {
                Text(choice.name)
                Spacer()
                if settings.font == choice.id { Image(systemName: "checkmark").foregroundStyle(.tint) }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
