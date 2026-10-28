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

/// The row of keys a phone keyboard lacks, on a strip of Liquid Glass. Ctrl
/// and Alt latch for the next key or character; arrows and paging keys repeat
/// while held.
struct ExtraKeysRow: View {
    var keys: [ExtraKey] = ExtraKey.defaults
    @Binding var latched: Int32
    let onKey: (Int32, Bool) -> Void
    let onText: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            GlassEffectContainer(spacing: 6) {
                HStack(spacing: 6) {
                    ForEach(keys, id: \.self) { key in
                        button(for: key)
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
            }
        }
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
    }

    @ViewBuilder
    private func button(for key: ExtraKey) -> some View {
        let active = if case .modifier(_, let meta) = key { latched & meta != 0 } else { false }
        let label = Text(key.label)
            .font(.system(size: 14, weight: active ? .bold : .medium, design: .monospaced))
            .frame(minWidth: 28, minHeight: 28)
            .padding(.horizontal, 6)
        switch key {
        case .key(_, let code, let repeats) where repeats:
            label
                .glassEffect(.regular.interactive(), in: .capsule)
                .onLongPressGesture(minimumDuration: 0.4) {} onPressingChanged: { pressing in
                    pressing ? startRepeat(code) : stopRepeat()
                }
        case .key(_, let code, _):
            Button { onKey(code, false) } label: { label }
                .buttonStyle(.glass)
        case .text(_, let text):
            Button { onText(text) } label: { label }
                .buttonStyle(.glass)
        case .modifier(_, let meta):
            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                latched ^= meta
            } label: { label }
                .buttonStyle(active ? AnyPrimitiveButtonStyle(.glassProminent) : AnyPrimitiveButtonStyle(.glass))
        }
    }

    @State private var repeatTask: Task<Void, Never>?

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

/// Lets one call site pick between two primitive button styles.
struct AnyPrimitiveButtonStyle: PrimitiveButtonStyle {
    private let make: (Configuration) -> AnyView

    init<S: PrimitiveButtonStyle>(_ style: S) {
        make = { AnyView(style.makeBody(configuration: $0)) }
    }

    func makeBody(configuration: Configuration) -> some View { make(configuration) }
}
