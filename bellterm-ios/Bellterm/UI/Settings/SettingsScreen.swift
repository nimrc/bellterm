import SwiftUI

/// Appearance and terminal preferences, applied live.
struct SettingsScreen: View {
    @Bindable var settings: Settings
    let fonts: FontManager

    var body: some View {
        Form {
            Section("Appearance") {
                Picker("Theme", selection: $settings.themeMode) {
                    Text("System").tag(ThemeMode.system)
                    Text("Light").tag(ThemeMode.light)
                    Text("Dark").tag(ThemeMode.dark)
                }
                Picker("Dark terminal colours", selection: $settings.darkScheme) {
                    ForEach(TerminalSchemes.all.filter(\.dark)) { SchemeLabel(scheme: $0).tag($0.id) }
                }
                Picker("Light terminal colours", selection: $settings.lightScheme) {
                    ForEach(TerminalSchemes.all.filter { !$0.dark }) { SchemeLabel(scheme: $0).tag($0.id) }
                }
            }
            Section("Terminal") {
                NavigationLink {
                    FontsScreen(settings: settings, fonts: fonts)
                } label: {
                    LabeledContent("Font", value: fonts.displayName(settings.font))
                }
                Stepper(value: $settings.fontSize, in: 8...24, step: 1) {
                    LabeledContent("Font size", value: "\(Int(settings.fontSize)) pt")
                }
                Toggle("Haptic on bell", isOn: $settings.hapticOnBell)
                Toggle("Keep screen on", isOn: $settings.keepScreenOn)
            }
            Section {
                Link("Herdr", destination: URL(string: "https://herdr.dev/")!)
            } footer: {
                Text("Bellterm works with Herdr but is not affiliated with, endorsed by, or supported by Herdr or herdr.dev.")
            }
        }
        .navigationTitle("Settings")
    }
}

private struct SchemeLabel: View {
    let scheme: TerminalScheme

    var body: some View {
        HStack {
            HStack(spacing: 0) {
                ForEach([scheme.background, scheme.ansi[1], scheme.ansi[2], scheme.ansi[4]], id: \.self) {
                    Color(rgb: $0).frame(width: 8, height: 16)
                }
            }
            .clipShape(.rect(cornerRadius: 3))
            Text(scheme.name)
        }
    }
}
