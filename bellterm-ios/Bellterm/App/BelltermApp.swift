import SwiftUI

@main
struct BelltermApp: App {
    init() {
        initLogging()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
