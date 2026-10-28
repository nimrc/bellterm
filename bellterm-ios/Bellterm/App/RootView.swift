import SwiftUI

/// The node list, with the attached session over it full screen. Leaving a
/// session keeps it attached; the backgrounded app closes attachments and
/// reopens them on return.
struct RootView: View {
    @State private var app = AppModel()
    @State private var session: Node?
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var systemScheme

    private var preferred: ColorScheme? {
        switch app.settings.themeMode {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    var body: some View {
        NodesScreen(app: app, session: $session)
            .fullScreenCover(item: $session) { node in
                SessionScreen(app: app, node: node) { session = nil }
                    .preferredColorScheme(preferred)
            }
            .preferredColorScheme(preferred)
            .onChange(of: scenePhase) { _, phase in
                switch phase {
                case .background: app.enterBackground()
                case .active: app.enterForeground(dark: (preferred ?? systemScheme) == .dark)
                default: break
                }
            }
            .onChange(of: session == nil || !app.settings.keepScreenOn, initial: true) { _, idle in
                UIApplication.shared.isIdleTimerDisabled = !idle
            }
    }
}
