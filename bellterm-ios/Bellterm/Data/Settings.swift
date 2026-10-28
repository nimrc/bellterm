import Foundation
import Observation

enum ThemeMode: String, CaseIterable, Sendable {
    case system, light, dark
}

/// App preferences, observed by the UI and applied live.
@MainActor
@Observable
final class Settings {
    @ObservationIgnored private let defaults: UserDefaults

    var themeMode: ThemeMode { didSet { defaults.set(themeMode.rawValue, forKey: "theme_mode") } }
    var darkScheme: String { didSet { defaults.set(darkScheme, forKey: "dark_scheme") } }
    var lightScheme: String { didSet { defaults.set(lightScheme, forKey: "light_scheme") } }
    var font: String { didSet { defaults.set(font, forKey: "font") } }
    var fontSize: Double { didSet { defaults.set(fontSize, forKey: "font_size") } }
    var hapticOnBell: Bool { didSet { defaults.set(hapticOnBell, forKey: "haptic_on_bell") } }
    var keepScreenOn: Bool { didSet { defaults.set(keepScreenOn, forKey: "keep_screen_on") } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        themeMode = defaults.string(forKey: "theme_mode").flatMap(ThemeMode.init) ?? .system
        darkScheme = defaults.string(forKey: "dark_scheme") ?? "bellterm-dark"
        lightScheme = defaults.string(forKey: "light_scheme") ?? "bellterm-light"
        font = defaults.string(forKey: "font") ?? "jetbrains-mono"
        fontSize = defaults.object(forKey: "font_size") as? Double ?? 13
        hapticOnBell = defaults.object(forKey: "haptic_on_bell") as? Bool ?? true
        keepScreenOn = defaults.bool(forKey: "keep_screen_on")
    }

    func scheme(dark: Bool) -> TerminalScheme {
        TerminalSchemes.byId(dark ? darkScheme : lightScheme, dark: dark)
    }

    /// The terminal size last laid out, so a new connection starts close to it.
    var lastGrid: Grid {
        get {
            Grid(
                cols: Int32(defaults.object(forKey: "cols") as? Int ?? 80),
                rows: Int32(defaults.object(forKey: "rows") as? Int ?? 24),
                cellWidth: Int32(defaults.integer(forKey: "cw")),
                cellHeight: Int32(defaults.integer(forKey: "ch"))
            )
        }
        set {
            defaults.set(Int(newValue.cols), forKey: "cols")
            defaults.set(Int(newValue.rows), forKey: "rows")
            defaults.set(Int(newValue.cellWidth), forKey: "cw")
            defaults.set(Int(newValue.cellHeight), forKey: "ch")
        }
    }
}

/// A terminal grid in cells, with the cell size in pixels.
struct Grid: Equatable, Sendable {
    var cols: Int32
    var rows: Int32
    var cellWidth: Int32
    var cellHeight: Int32
}
