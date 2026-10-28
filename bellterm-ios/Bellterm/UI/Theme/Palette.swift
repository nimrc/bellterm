import SwiftUI

extension Color {
    /// From 0xRRGGBB.
    init(rgb: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double(rgb >> 16 & 0xff) / 255,
            green: Double(rgb >> 8 & 0xff) / 255,
            blue: Double(rgb & 0xff) / 255,
            opacity: opacity
        )
    }

    static let brass = Color(rgb: 0xC98A1B)
    static let teal = Color(rgb: 0x2F9E9A)
}

extension AgentStatus {
    /// The daemon's agent status as a colour, the same on every client.
    var color: Color {
        switch self {
        case .working: Color(rgb: 0x4C8DF6)
        case .blocked: Color(rgb: 0xF2A93B)
        case .done: Color(rgb: 0x3FB868)
        case .idle: Color(rgb: 0x8A8F98)
        case .unknown: .clear
        }
    }
}

/// Colour labels a node can wear, shown on its avatar and session bar.
enum NodeColors {
    static let all: [UInt32] = [0x2F9E9A, 0xE5674F, 0xD9467A, 0x8E5BD9, 0x4A6CF0, 0x2C98D6, 0xC98A1B, 0x5DA03A]

    static func rgb(_ index: Int) -> UInt32 {
        all[((index % all.count) + all.count) % all.count]
    }

    static func of(_ index: Int) -> Color { Color(rgb: rgb(index)) }
}
