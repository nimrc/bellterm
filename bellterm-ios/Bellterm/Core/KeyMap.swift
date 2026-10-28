import UIKit

/// iOS keys as the Android `KeyEvent` codes and meta-state bits `input.rs`
/// maps to Herdr's semantic keys.
enum KeyMap {
    // keep-sorted start
    static let dpadDown: Int32 = 20
    static let dpadLeft: Int32 = 21
    static let dpadRight: Int32 = 22
    static let dpadUp: Int32 = 19
    static let enter: Int32 = 66
    static let escape: Int32 = 111
    static let forwardDel: Int32 = 112
    static let insert: Int32 = 124
    static let moveEnd: Int32 = 123
    static let moveHome: Int32 = 122
    static let numpadEnter: Int32 = 160
    static let pageDown: Int32 = 93
    static let pageUp: Int32 = 92
    static let space: Int32 = 62
    static let tab: Int32 = 61
    static let del: Int32 = 67
    // keep-sorted end
    static let f1: Int32 = 131

    static let metaShift: Int32 = 0x1
    static let metaAlt: Int32 = 0x2
    static let metaCtrl: Int32 = 0x1000
    static let metaMeta: Int32 = 0x10000

    /// The Android code for a hardware key Herdr names, or nil for keys that
    /// type text.
    static func code(_ usage: UIKeyboardHIDUsage) -> Int32? {
        switch usage {
        case .keyboardUpArrow: dpadUp
        case .keyboardDownArrow: dpadDown
        case .keyboardLeftArrow: dpadLeft
        case .keyboardRightArrow: dpadRight
        case .keyboardTab: tab
        case .keyboardReturnOrEnter: enter
        case .keypadEnter: numpadEnter
        case .keyboardDeleteOrBackspace: del
        case .keyboardDeleteForward: forwardDel
        case .keyboardEscape: escape
        case .keyboardPageUp: pageUp
        case .keyboardPageDown: pageDown
        case .keyboardHome: moveHome
        case .keyboardEnd: moveEnd
        case .keyboardInsert: insert
        case .keyboardF1, .keyboardF2, .keyboardF3, .keyboardF4, .keyboardF5, .keyboardF6,
             .keyboardF7, .keyboardF8, .keyboardF9, .keyboardF10, .keyboardF11, .keyboardF12:
            f1 + Int32(usage.rawValue - UIKeyboardHIDUsage.keyboardF1.rawValue)
        default: nil
        }
    }

    static func meta(_ flags: UIKeyModifierFlags) -> Int32 {
        var meta: Int32 = 0
        if flags.contains(.shift) { meta |= metaShift }
        if flags.contains(.alternate) { meta |= metaAlt }
        if flags.contains(.control) { meta |= metaCtrl }
        if flags.contains(.command) { meta |= metaMeta }
        return meta
    }

    /// Keys that repeat while held, as the extra-keys row's arrows do.
    static func repeats(_ code: Int32) -> Bool {
        [dpadUp, dpadDown, dpadLeft, dpadRight, del, forwardDel, pageUp, pageDown].contains(code)
    }
}
