import SwiftUI
import UIKit

/// Hands the latest frame to `TerminalUIView` and its input to `connection`.
struct TerminalView: UIViewRepresentable {
    let connection: Connection
    let settings: Settings
    let fonts: FontManager
    /// The pane keys and text go to; nil lets Herdr pick the focused one.
    let pane: String?
    let keys: ExtraKeySender

    func makeUIView(context: Context) -> TerminalUIView {
        let view = TerminalUIView()
        view.delegate = context.coordinator
        view.fontSize = settings.fontSize
        view.faces = (fonts.faces(settings.font), fonts.symbols)
        context.coordinator.font = "\(settings.font)#\(fonts.revision)"
        keys.view = view
        return view
    }

    func updateUIView(_ view: TerminalUIView, context: Context) {
        context.coordinator.parent = self
        view.frameToDraw = connection.frame
        if view.fontSize != settings.fontSize { view.fontSize = settings.fontSize }
        let font = "\(settings.font)#\(fonts.revision)"
        if context.coordinator.font != font {
            context.coordinator.font = font
            view.faces = (fonts.faces(settings.font), fonts.symbols)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    @MainActor
    final class Coordinator: TerminalViewDelegate {
        var parent: TerminalView
        /// The font id and font-set revision the view was last given.
        var font: String?

        init(_ parent: TerminalView) { self.parent = parent }

        private var connection: Connection { parent.connection }

        func terminal(grid: Grid) {
            parent.settings.lastGrid = grid
            connection.resize(grid)
        }

        func terminal(focus pane: RenderFrame.PaneArea) { connection.focusPane(pane.id) }

        func terminal(tap pane: RenderFrame.PaneArea?, column: Int, row: Int) {
            connection.touch(pane: pane?.id ?? "", kind: Connection.touchTap, column: Int32(column), row: Int32(row))
        }

        func terminal(scroll pane: RenderFrame.PaneArea?, column: Int, row: Int, up: Bool, lines: Int) {
            connection.touch(
                pane: pane?.id ?? "", kind: up ? Connection.touchScrollUp : Connection.touchScrollDown,
                column: Int32(column), row: Int32(row), lines: Int32(lines)
            )
        }

        func terminal(key code: Int32, meta: Int32, unicode: UInt32, repeat: Bool) -> Bool {
            connection.key(pane: parent.pane, keyCode: code, meta: meta, unicode: unicode, repeat: `repeat`)
        }

        func terminal(text: String) { connection.text(pane: parent.pane, text) }

        func terminal(chord codepoint: UInt32, meta: Int32) {
            connection.chord(pane: parent.pane, codepoint: codepoint, meta: meta)
        }

        func terminal(copyFrom start: (column: Int, row: Int), to end: (column: Int, row: Int)) {
            let text = connection.selectionText(
                start: (Int32(start.column), Int32(start.row)), end: (Int32(end.column), Int32(end.row))
            )
            if !text.isEmpty { UIPasteboard.general.string = text }
        }

        func terminalPaste() {
            if let text = UIPasteboard.general.string { connection.paste(pane: parent.pane, text) }
        }

        func terminal(fontSize: CGFloat) {
            parent.settings.fontSize = min(max(fontSize, 8), 24)
        }
    }
}

/// Lets SwiftUI raise the keyboard on the terminal view.
@MainActor
final class ExtraKeySender {
    fileprivate weak var view: TerminalUIView?

    func showKeyboard() { view?.becomeFirstResponder() }
}
