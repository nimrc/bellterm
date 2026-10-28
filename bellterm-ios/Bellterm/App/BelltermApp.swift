import SwiftUI

@main
struct BelltermApp: App {
    init() {
        // A Rust binary ignores SIGPIPE before main; linked into an app, the
        // core would instead be killed by a write to a socket the host closed.
        signal(SIGPIPE, SIG_IGN)
        initLogging()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
