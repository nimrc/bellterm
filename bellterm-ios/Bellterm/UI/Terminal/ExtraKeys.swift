import SwiftUI

/// What one extra key does.
enum ExtraKey: Hashable {
    case key(String, Int32, repeats: Bool = false)
    case text(String, String)
    case modifier(String, Int32)

    var label: String {
        switch self {
        case .key(let label, _, _), .text(let label, _), .modifier(let label, _): label
        }
    }

    static let defaults: [ExtraKey] = [
        .key("Esc", KeyMap.escape),
        .key("Tab", KeyMap.tab),
        .modifier("Ctrl", KeyMap.metaCtrl),
        .modifier("Alt", KeyMap.metaAlt),
        .key("←", KeyMap.dpadLeft, repeats: true),
        .key("↓", KeyMap.dpadDown, repeats: true),
        .key("↑", KeyMap.dpadUp, repeats: true),
        .key("→", KeyMap.dpadRight, repeats: true),
        .text("|", "|"),
        .text("-", "-"),
        .text("/", "/"),
        .text("~", "~"),
        .key("Home", KeyMap.moveHome),
        .key("End", KeyMap.moveEnd),
        .key("PgUp", KeyMap.pageUp, repeats: true),
        .key("PgDn", KeyMap.pageDown, repeats: true),
    ]
}

/// Ctrl and Alt latched by the extra-keys row, shared with the terminal view
/// that consumes them.
@MainActor
@Observable
final class ExtraKeysState {
    var latched: Int32 = 0
}

/// The row of keys a phone keyboard lacks, on a strip of Liquid Glass, riding
/// on the keyboard as its accessory. Ctrl
/// and Alt latch for the next key or character; arrows and paging keys repeat
/// while held.
struct ExtraKeysRow: View {
    var keys: [ExtraKey] = ExtraKey.defaults
    @Bindable var state: ExtraKeysState
    let onKey: (Int32, Bool) -> Void
    let onText: (String) -> Void
    let onToggleKeyboard: () -> Void

    @State private var repeatTask: Task<Void, Never>?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            GlassEffectContainer(spacing: 6) {
                HStack(spacing: 6) {
                    keyCap { Image(systemName: "keyboard.chevron.compact.down") }
                        .accessibilityLabel(Text("Keyboard"))
                        .onTapGesture(perform: onToggleKeyboard)
                    ForEach(keys, id: \.self) { key in
                        cap(for: key)
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
            }
        }
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
    }

    /// Every key is the same capsule: one height, one minimum width, one font.
    private func keyCap(active: Bool = false, @ViewBuilder _ content: () -> some View) -> some View {
        content()
            .font(.system(size: 15, weight: active ? .semibold : .regular, design: .monospaced))
            .foregroundStyle(active ? Color.white : Color.primary)
            .frame(minWidth: 30, minHeight: 36)
            .padding(.horizontal, 7)
            .contentShape(.capsule)
            .glassEffect(active ? .regular.tint(.accentColor).interactive() : .regular.interactive(), in: .capsule)
    }

    @ViewBuilder
    private func cap(for key: ExtraKey) -> some View {
        switch key {
        case .key(let label, let code, let repeats):
            let cap = keyCap { Text(label) }
            if repeats {
                cap.onLongPressGesture(minimumDuration: 0.4) {} onPressingChanged: { pressing in
                    pressing ? startRepeat(code) : stopRepeat()
                }
            } else {
                cap.onTapGesture { onKey(code, false) }
            }
        case .text(let label, let text):
            keyCap { Text(label) }.onTapGesture { onText(text) }
        case .modifier(let label, let meta):
            keyCap(active: state.latched & meta != 0) { Text(label) }
                .onTapGesture {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    state.latched ^= meta
                }
        }
    }

    private func startRepeat(_ code: Int32) {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        onKey(code, false)
        repeatTask?.cancel()
        repeatTask = Task {
            try? await Task.sleep(for: .milliseconds(400))
            while !Task.isCancelled {
                onKey(code, true)
                try? await Task.sleep(for: .milliseconds(60))
            }
        }
    }

    private func stopRepeat() {
        repeatTask?.cancel()
        repeatTask = nil
    }
}
