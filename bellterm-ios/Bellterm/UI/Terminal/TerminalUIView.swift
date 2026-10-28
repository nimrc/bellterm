import CoreText
import SwiftUI
import UIKit

@MainActor
protocol TerminalViewDelegate: AnyObject {
    /// The grid that fits the view, with cell sizes in pixels.
    func terminal(grid: Grid)
    func terminal(focus pane: RenderFrame.PaneArea)
    /// A tap at a cell, relative to the pane or the open popup.
    func terminal(tap pane: RenderFrame.PaneArea?, column: Int, row: Int)
    func terminal(scroll pane: RenderFrame.PaneArea?, column: Int, row: Int, up: Bool, lines: Int)
    func terminal(key code: Int32, meta: Int32, unicode: UInt32, repeat: Bool) -> Bool
    func terminal(text: String)
    func terminal(chord codepoint: UInt32, meta: Int32)
    func terminal(copyFrom start: (column: Int, row: Int), to end: (column: Int, row: Int))
    func terminalPaste()
    func terminal(fontSize: CGFloat)
}

/// Draws the frames libghostty produced and turns touch and keyboard input
/// into Herdr input. Drawing reads only the latest `frame`; nothing here talks
/// to the daemon except through `delegate`.
final class TerminalUIView: UIView {
    weak var delegate: TerminalViewDelegate?

    var frameToDraw: RenderFrame? {
        didSet { setNeedsDisplay() }
    }

    let extraKeys = ExtraKeysState()

    /// Modifiers latched by the extra-keys row, as `KeyMap` meta bits.
    private var latchedMeta: Int32 {
        get { extraKeys.latched }
        set { extraKeys.latched = newValue }
    }

    /// The extra-keys row, attached to the keyboard so it comes and goes with it.
    private lazy var accessory: UIView = {
        let row = ExtraKeysRow(
            state: extraKeys,
            onKey: { [weak self] code, repeats in self?.sendKey(code, repeat: repeats) },
            onText: { [weak self] in self?.insertText($0) },
            onToggleKeyboard: { [weak self] in _ = self?.resignFirstResponder() }
        )
        let host = UIHostingController(rootView: row)
        host.view.backgroundColor = .clear
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        let container = UIView(frame: CGRect(x: 0, y: 0, width: 0, height: 52))
        container.autoresizingMask = .flexibleWidth
        host.view.frame = container.bounds
        container.addSubview(host.view)
        accessoryHost = host
        return container
    }()

    private var accessoryHost: UIHostingController<ExtraKeysRow>?

    override var inputAccessoryView: UIView? { accessory }

    static let fontSizes: ClosedRange<CGFloat> = 7...32

    var fontSize: CGFloat = 13 {
        didSet {
            fontSize = min(max(fontSize, Self.fontSizes.lowerBound), Self.fontSizes.upperBound)
            guard fontSize != oldValue else { return }
            loadFonts()
        }
    }

    /// The chosen font's faces and the icon font behind them; nil until set.
    var faces: (font: FontFaces, symbols: CTFontDescriptor?)? {
        didSet { loadFonts() }
    }

    private var fonts: TerminalFonts?
    private var cellWidth: CGFloat = 8
    private var cellHeight: CGFloat = 16
    private var baseline: CGFloat = 12
    private var lastGrid: Grid?

    /// Text the keyboard is still composing, drawn at the cursor.
    private var marked = ""
    private var selection: (start: (column: Int, row: Int), end: (column: Int, row: Int))?
    private var selecting = false

    private var scrollRemainder: CGFloat = 0
    private var fling: (link: CADisplayLink, velocity: CGFloat, anchor: CGPoint)?
    private var keyRepeat: Timer?

    // UITextInput state.
    weak var inputDelegate: UITextInputDelegate?
    lazy var tokenizer: UITextInputTokenizer = UITextInputStringTokenizer(textInput: self)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = true
        contentMode = .redraw
        isMultipleTouchEnabled = true
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned))
        pan.maximumNumberOfTouches = 1
        addGestureRecognizer(pan)
        addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(pinched)))
        addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(pressed)))
        addInteraction(editMenu)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    private func loadFonts() {
        guard let faces else { return }
        let fonts = TerminalFonts(faces.font, size: fontSize, symbols: faces.symbols)
        self.fonts = fonts
        let cell = fonts.cell
        cellWidth = cell.width
        cellHeight = cell.height
        baseline = cell.baseline
        reportGrid()
        setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        reportGrid()
    }

    private func reportGrid() {
        guard bounds.width > 0, bounds.height > 0, cellWidth > 0 else { return }
        let scale = traitCollection.displayScale
        let grid = Grid(
            cols: Int32(max(1, (bounds.width / cellWidth).rounded(.down))),
            rows: Int32(max(1, (bounds.height / cellHeight).rounded(.down))),
            cellWidth: Int32(cellWidth * scale),
            cellHeight: Int32(cellHeight * scale)
        )
        guard grid != lastGrid else { return }
        lastGrid = grid
        delegate?.terminal(grid: grid)
    }

    // MARK: Drawing

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        guard let frame = frameToDraw else {
            context.setFillColor(UIColor.black.cgColor)
            context.fill(bounds)
            return
        }
        context.setFillColor(Self.color(frame.background))
        context.fill(bounds)
        for run in frame.runs where run.has(RenderFrame.background) {
            context.setFillColor(Self.color(run.bg))
            context.fill(cells(column: run.col, row: run.row, count: run.cells))
        }
        drawSelection(context, frame)
        // UIKit's context is flipped; flip glyphs back upright.
        context.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        for run in frame.runs { draw(run, color: run.fg, in: context) }
        drawCursor(context, frame)
    }

    private func cells(column: Int, row: Int, count: Int) -> CGRect {
        CGRect(x: CGFloat(column) * cellWidth, y: CGFloat(row) * cellHeight, width: CGFloat(count) * cellWidth, height: cellHeight)
    }

    private static func color(_ argb: UInt32) -> CGColor {
        CGColor(
            srgbRed: CGFloat(argb >> 16 & 0xff) / 255, green: CGFloat(argb >> 8 & 0xff) / 255,
            blue: CGFloat(argb & 0xff) / 255, alpha: CGFloat(argb >> 24 & 0xff) / 255
        )
    }

    private func draw(_ run: RenderFrame.Run, color: UInt32, in context: CGContext) {
        guard !run.has(RenderFrame.invisible) else { return }
        let ink = run.has(RenderFrame.faint) ? (color & 0x00ff_ffff) | 0x9900_0000 : color
        let rect = cells(column: run.col, row: run.row, count: run.cells)
        if !run.text.allSatisfy(\.isWhitespace), let fonts {
            let font = fonts.face(bold: run.has(RenderFrame.bold), italic: run.has(RenderFrame.italic))
            let attributes = [kCTFontAttributeName: font, kCTForegroundColorAttributeName: Self.color(ink)] as CFDictionary
            let line = CTLineCreateWithAttributedString(CFAttributedStringCreate(nil, run.text as CFString, attributes))
            context.saveGState()
            if run.has(RenderFrame.ascii) {
                context.textPosition = CGPoint(x: rect.minX, y: rect.minY + baseline)
            } else {
                // A cluster gets exactly its cells: centred, and narrowed if a
                // fallback font draws it wider than the grid allows.
                let natural = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
                let squeeze = natural > rect.width && natural > 0 ? rect.width / natural : 1
                let drawn = natural * squeeze
                context.translateBy(x: rect.minX + (rect.width - drawn) / 2, y: rect.minY + baseline)
                context.scaleBy(x: squeeze, y: 1)
                context.textPosition = .zero
            }
            CTLineDraw(line, context)
            context.restoreGState()
        }
        let thickness = max(1, cellHeight / 16)
        context.setFillColor(Self.color(ink))
        if run.has(RenderFrame.underline) {
            context.fill(CGRect(x: rect.minX, y: rect.maxY - max(1, cellHeight / 14), width: rect.width, height: thickness))
        }
        if run.has(RenderFrame.strikethrough) {
            context.fill(CGRect(x: rect.minX, y: rect.midY, width: rect.width, height: thickness))
        }
    }

    private func drawCursor(_ context: CGContext, _ frame: RenderFrame) {
        if !marked.isEmpty {
            drawMarked(context, frame)
            return
        }
        let cursor = frame.cursor
        guard cursor.visible, cursor.x < frame.cols, cursor.y < frame.rows else { return }
        let cell = cells(column: cursor.x, row: cursor.y, count: 1)
        let stroke = max(2, cellWidth / 6)
        context.setFillColor(Self.color(frame.cursorColor))
        switch cursor.shape {
        case RenderFrame.cursorBar:
            context.fill(CGRect(x: cell.minX, y: cell.minY, width: stroke, height: cellHeight))
        case RenderFrame.cursorUnderline:
            context.fill(CGRect(x: cell.minX, y: cell.maxY - stroke, width: cellWidth, height: stroke))
        case RenderFrame.cursorHollow:
            context.setStrokeColor(Self.color(frame.cursorColor))
            context.setLineWidth(stroke / 2)
            context.stroke(cell.insetBy(dx: stroke / 4, dy: stroke / 4))
        default:
            let under = frame.runs.first { $0.row == cursor.y && cursor.x >= $0.col && cursor.x < $0.col + $0.cells }
            let wide = under?.has(RenderFrame.wide) == true
            let column = wide ? (under?.col ?? cursor.x) : cursor.x
            context.fill(cells(column: column, row: cursor.y, count: wide ? 2 : 1))
            if let under {
                let text = under.has(RenderFrame.ascii)
                    ? String(Array(under.text)[safe: cursor.x - under.col] ?? " ")
                    : under.text
                let glyph = RenderFrame.Run(
                    row: cursor.y, col: column, cells: wide ? 2 : 1,
                    flags: under.flags & ~RenderFrame.background, fg: 0, bg: 0, text: text
                )
                draw(glyph, color: frame.background, in: context)
            }
        }
    }

    /// Keyboard composition, drawn at the cursor until the keyboard commits it.
    private func drawMarked(_ context: CGContext, _ frame: RenderFrame) {
        guard let fonts else { return }
        let attributes = [kCTFontAttributeName: fonts.regular, kCTForegroundColorAttributeName: Self.color(frame.foreground)] as CFDictionary
        let line = CTLineCreateWithAttributedString(CFAttributedStringCreate(nil, marked as CFString, attributes))
        let width = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        let left = min(CGFloat(frame.cursor.x) * cellWidth, max(0, bounds.width - width))
        let top = CGFloat(frame.cursor.y) * cellHeight
        context.setFillColor(Self.color(frame.background))
        context.fill(CGRect(x: left, y: top, width: width, height: cellHeight))
        context.textPosition = CGPoint(x: left, y: top + baseline)
        CTLineDraw(line, context)
        context.setFillColor(Self.color(frame.foreground))
        context.fill(CGRect(x: left, y: top + cellHeight - 2, width: width, height: 2))
    }

    private func drawSelection(_ context: CGContext, _ frame: RenderFrame) {
        guard let (start, end) = orderedSelection else { return }
        context.setFillColor(UIColor.systemBlue.withAlphaComponent(0.4).cgColor)
        for row in start.row...end.row {
            let from = row == start.row ? start.column : 0
            let to = row == end.row ? end.column : frame.cols - 1
            context.fill(cells(column: from, row: row, count: to - from + 1))
        }
    }

    private var orderedSelection: ((column: Int, row: Int), (column: Int, row: Int))? {
        guard let (a, b) = selection else { return nil }
        return (a.row, a.column) <= (b.row, b.column) ? (a, b) : (b, a)
    }

    // MARK: Touch

    private func cell(at point: CGPoint) -> (column: Int, row: Int)? {
        guard let frame = frameToDraw else { return nil }
        let column = Int((point.x / cellWidth).rounded(.down))
        let row = Int((point.y / cellHeight).rounded(.down))
        guard column >= 0, row >= 0, column < frame.cols, row < frame.rows else { return nil }
        return (column, row)
    }

    /// The pane under a cell and the cell relative to it, or to the popup.
    private func target(_ cell: (column: Int, row: Int)) -> (pane: RenderFrame.PaneArea?, column: Int, row: Int)? {
        guard let frame = frameToDraw else { return nil }
        if let popup = frame.popup {
            guard popup.contains(column: cell.column, row: cell.row) else { return nil }
            return (nil, cell.column - popup.x, cell.row - popup.y)
        }
        guard let pane = frame.paneAt(column: cell.column, row: cell.row) else { return nil }
        return (pane, cell.column - pane.rect.x, cell.row - pane.rect.y)
    }

    @objc private func tapped(_ gesture: UITapGestureRecognizer) {
        stopFling()
        if selection != nil {
            clearSelection()
            return
        }
        if let cell = cell(at: gesture.location(in: self)), let target = target(cell) {
            if let pane = target.pane, !pane.focused {
                delegate?.terminal(focus: pane)
            } else if target.pane == nil || target.pane?.mouseReporting == true {
                delegate?.terminal(tap: target.pane, column: target.column, row: target.row)
            }
        }
        becomeFirstResponder()
    }

    @objc private func panned(_ gesture: UIPanGestureRecognizer) {
        guard selection == nil else { return }
        let location = gesture.location(in: self)
        switch gesture.state {
        case .began:
            stopFling()
            scrollRemainder = 0
        case .changed:
            scroll(by: -gesture.translation(in: self).y, at: location)
            gesture.setTranslation(.zero, in: self)
        case .ended:
            let velocity = -gesture.velocity(in: self).y
            if abs(velocity) > 200 { startFling(velocity: velocity, at: location) }
        default:
            break
        }
    }

    private func scroll(by distance: CGFloat, at point: CGPoint) {
        scrollRemainder += distance
        let lines = Int(scrollRemainder / cellHeight)
        guard lines != 0 else { return }
        scrollRemainder -= CGFloat(lines) * cellHeight
        guard let cell = cell(at: point), let target = target(cell) else { return }
        // Dragging the content up reveals what is below it.
        delegate?.terminal(scroll: target.pane, column: target.column, row: target.row, up: lines < 0, lines: abs(lines))
    }

    private func startFling(velocity: CGFloat, at point: CGPoint) {
        let link = CADisplayLink(target: self, selector: #selector(stepFling))
        link.add(to: .main, forMode: .common)
        fling = (link, velocity, point)
    }

    @objc private func stepFling(_ link: CADisplayLink) {
        guard var current = fling else { return }
        let elapsed = link.targetTimestamp - link.timestamp
        scroll(by: current.velocity * elapsed, at: current.anchor)
        current.velocity *= pow(0.998, elapsed * 1000)
        fling = current
        if abs(current.velocity) < 30 { stopFling() }
    }

    private func stopFling() {
        fling?.link.invalidate()
        fling = nil
    }

    @objc private func pinched(_ gesture: UIPinchGestureRecognizer) {
        guard gesture.state == .changed else { return }
        fontSize *= gesture.scale
        gesture.scale = 1
        delegate?.terminal(fontSize: fontSize)
    }

    @objc private func pressed(_ gesture: UILongPressGestureRecognizer) {
        let location = gesture.location(in: self)
        switch gesture.state {
        case .began:
            guard let cell = cell(at: location) else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            selection = word(at: cell) ?? (cell, cell)
            selecting = true
            setNeedsDisplay()
        case .changed:
            guard selecting, let cell = cell(at: location), let start = selection?.start else { return }
            selection = (start, cell)
            setNeedsDisplay()
        case .ended:
            selecting = false
            editMenu.presentEditMenu(with: UIEditMenuConfiguration(identifier: nil, sourcePoint: location))
        default:
            selecting = false
        }
    }

    /// The run of non-blank characters around a cell, as a double tap picks.
    private func word(at cell: (column: Int, row: Int)) -> ((column: Int, row: Int), (column: Int, row: Int))? {
        guard let frame = frameToDraw else { return nil }
        var chars = [Character](repeating: " ", count: frame.cols)
        for run in frame.runs where run.row == cell.row && run.has(RenderFrame.ascii) {
            for (offset, character) in run.text.enumerated() where run.col + offset < chars.count {
                chars[run.col + offset] = character
            }
        }
        guard !chars[cell.column].isWhitespace else { return nil }
        var start = cell.column
        var end = cell.column
        while start > 0, !chars[start - 1].isWhitespace { start -= 1 }
        while end < chars.count - 1, !chars[end + 1].isWhitespace { end += 1 }
        return ((start, cell.row), (end, cell.row))
    }

    func clearSelection() {
        selection = nil
        editMenu.dismissMenu()
        setNeedsDisplay()
    }

    private lazy var editMenu = UIEditMenuInteraction(delegate: self)

    // MARK: Keyboard

    override var canBecomeFirstResponder: Bool { true }

    /// Sends a key from the extra-keys row with any latched modifiers.
    func sendKey(_ code: Int32, repeat: Bool = false) {
        commitMarked()
        _ = delegate?.terminal(key: code, meta: latchedMeta, unicode: 0, repeat: `repeat`)
        consumeLatch()
    }

    private func send(text: String) {
        guard !text.isEmpty else { return }
        switch text {
        case "\n", "\r":
            _ = delegate?.terminal(key: KeyMap.enter, meta: latchedMeta, unicode: 0, repeat: false)
            consumeLatch()
            return
        case "\t":
            _ = delegate?.terminal(key: KeyMap.tab, meta: latchedMeta, unicode: 0, repeat: false)
            consumeLatch()
            return
        default:
            break
        }
        let scalars = text.unicodeScalars
        if latchedMeta & (KeyMap.metaCtrl | KeyMap.metaAlt) != 0, scalars.count == 1, let scalar = scalars.first {
            delegate?.terminal(chord: scalar.value, meta: latchedMeta)
            consumeLatch()
            return
        }
        delegate?.terminal(text: text)
    }

    private func consumeLatch() {
        if latchedMeta != 0 { latchedMeta = 0 }
    }

    /// Sends text still being composed before a key that bypasses the
    /// keyboard, so the key lands after it.
    private func commitMarked() {
        guard !marked.isEmpty else { return }
        let text = marked
        inputDelegate?.textWillChange(self)
        marked = ""
        inputDelegate?.textDidChange(self)
        send(text: text)
        setNeedsDisplay()
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var unhandled = Set<UIPress>()
        for press in presses {
            guard let key = press.key, handle(key) else {
                unhandled.insert(press)
                continue
            }
        }
        if !unhandled.isEmpty { super.pressesBegan(unhandled, with: event) }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        keyRepeat?.invalidate()
        keyRepeat = nil
        super.pressesEnded(presses, with: event)
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        keyRepeat?.invalidate()
        keyRepeat = nil
        super.pressesCancelled(presses, with: event)
    }

    /// A hardware key Herdr names, or a character chorded with Ctrl or Alt.
    /// Other keys fall through to text input.
    private func handle(_ key: UIKey) -> Bool {
        let meta = KeyMap.meta(key.modifierFlags) | latchedMeta
        if let code = KeyMap.code(key.keyCode) {
            commitMarked()
            let sent = delegate?.terminal(key: code, meta: meta, unicode: 0, repeat: false) ?? false
            consumeLatch()
            if sent, KeyMap.repeats(code) {
                keyRepeat?.invalidate()
                keyRepeat = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in
                    MainActor.assumeIsolated { self?.repeatKey(code, meta: meta) }
                }
            }
            return sent
        }
        let chorded = meta & (KeyMap.metaCtrl | KeyMap.metaAlt) != 0
        let scalars = key.charactersIgnoringModifiers.unicodeScalars
        guard chorded, scalars.count == 1, let scalar = scalars.first else { return false }
        commitMarked()
        delegate?.terminal(chord: scalar.value, meta: meta)
        consumeLatch()
        return true
    }

    private func repeatKey(_ code: Int32, meta: Int32) {
        keyRepeat = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { _ = self?.delegate?.terminal(key: code, meta: meta, unicode: 0, repeat: true) }
        }
    }

    // No suggestions or autocorrect: keystrokes go straight to the pane. CJK
    // keyboards still compose, shown at the cursor until committed.
    var autocorrectionType: UITextAutocorrectionType = .no
    var autocapitalizationType: UITextAutocapitalizationType = .none
    var spellCheckingType: UITextSpellCheckingType = .no
    var smartQuotesType: UITextSmartQuotesType = .no
    var smartDashesType: UITextSmartDashesType = .no
    var smartInsertDeleteType: UITextSmartInsertDeleteType = .no
    var inlinePredictionType: UITextInlinePredictionType = .no
    var keyboardType: UIKeyboardType = .default
    var returnKeyType: UIReturnKeyType = .default
}

extension TerminalUIView: @MainActor UIEditMenuInteractionDelegate {
    func editMenuInteraction(
        _ interaction: UIEditMenuInteraction, menuFor configuration: UIEditMenuConfiguration, suggestedActions: [UIMenuElement]
    ) -> UIMenu? {
        var actions: [UIAction] = []
        if let (start, end) = orderedSelection {
            actions.append(UIAction(title: String(localized: "Copy"), image: UIImage(systemName: "doc.on.doc")) { [weak self] _ in
                self?.delegate?.terminal(copyFrom: start, to: end)
                self?.clearSelection()
            })
        }
        actions.append(UIAction(title: String(localized: "Paste"), image: UIImage(systemName: "doc.on.clipboard")) { [weak self] _ in
            self?.delegate?.terminalPaste()
            self?.clearSelection()
        })
        return UIMenu(children: actions)
    }

    func editMenuInteraction(
        _ interaction: UIEditMenuInteraction, targetRectFor configuration: UIEditMenuConfiguration
    ) -> CGRect {
        guard let (start, end) = orderedSelection else { return .null }
        return CGRect(
            x: CGFloat(start.column) * cellWidth, y: CGFloat(start.row) * cellHeight,
            width: CGFloat(end.column - start.column + 1) * cellWidth,
            height: CGFloat(end.row - start.row + 1) * cellHeight
        ).standardized
    }

    func editMenuInteraction(
        _ interaction: UIEditMenuInteraction, willDismissMenuFor configuration: UIEditMenuConfiguration,
        animator: any UIEditMenuInteractionAnimating
    ) {
        animator.addCompletion { [weak self] in
            guard let self, !self.selecting else { return }
            self.selection = nil
            self.setNeedsDisplay()
        }
    }
}

// MARK: UITextInput
//
// The document is only the text being composed: everything committed goes to
// the pane at once, so there is nothing else to edit.

private final class MarkedPosition: UITextPosition {
    let offset: Int
    init(_ offset: Int) { self.offset = offset }
}

private final class MarkedRange: UITextRange {
    let lower: Int
    let upper: Int
    init(_ lower: Int, _ upper: Int) {
        self.lower = min(lower, upper)
        self.upper = max(lower, upper)
    }
    override var start: UITextPosition { MarkedPosition(lower) }
    override var end: UITextPosition { MarkedPosition(upper) }
    override var isEmpty: Bool { lower == upper }
}

extension TerminalUIView: @MainActor UITextInput {
    private var length: Int { (marked as NSString).length }

    var hasText: Bool { true }

    func insertText(_ text: String) {
        let hadMarked = !marked.isEmpty
        if hadMarked {
            inputDelegate?.textWillChange(self)
            marked = ""
            inputDelegate?.textDidChange(self)
        }
        send(text: text)
        setNeedsDisplay()
    }

    func deleteBackward() {
        if !marked.isEmpty {
            inputDelegate?.textWillChange(self)
            marked.removeLast()
            inputDelegate?.textDidChange(self)
            setNeedsDisplay()
            return
        }
        _ = delegate?.terminal(key: KeyMap.del, meta: latchedMeta, unicode: 0, repeat: false)
        consumeLatch()
    }

    func text(in range: UITextRange) -> String? {
        guard let range = range as? MarkedRange else { return nil }
        let ns = marked as NSString
        let lower = min(range.lower, ns.length)
        return ns.substring(with: NSRange(location: lower, length: min(range.upper, ns.length) - lower))
    }

    func replace(_ range: UITextRange, withText text: String) {
        guard let range = range as? MarkedRange else { return }
        let ns = marked as NSString
        let lower = min(range.lower, ns.length)
        marked = ns.replacingCharacters(in: NSRange(location: lower, length: min(range.upper, ns.length) - lower), with: text)
        setNeedsDisplay()
    }

    var selectedTextRange: UITextRange? {
        get { MarkedRange(length, length) }
        set {}
    }

    var markedTextRange: UITextRange? { marked.isEmpty ? nil : MarkedRange(0, length) }

    var markedTextStyle: [NSAttributedString.Key: Any]? {
        get { nil }
        set {}
    }

    func setMarkedText(_ markedText: String?, selectedRange: NSRange) {
        marked = markedText ?? ""
        setNeedsDisplay()
    }

    func unmarkText() {
        let text = marked
        marked = ""
        send(text: text)
        setNeedsDisplay()
    }

    var beginningOfDocument: UITextPosition { MarkedPosition(0) }
    var endOfDocument: UITextPosition { MarkedPosition(length) }

    func textRange(from fromPosition: UITextPosition, to toPosition: UITextPosition) -> UITextRange? {
        guard let from = fromPosition as? MarkedPosition, let to = toPosition as? MarkedPosition else { return nil }
        return MarkedRange(from.offset, to.offset)
    }

    func position(from position: UITextPosition, offset: Int) -> UITextPosition? {
        guard let position = position as? MarkedPosition else { return nil }
        let target = position.offset + offset
        return (0...length).contains(target) ? MarkedPosition(target) : nil
    }

    func position(from position: UITextPosition, in direction: UITextLayoutDirection, offset: Int) -> UITextPosition? {
        switch direction {
        case .left: self.position(from: position, offset: -offset)
        case .right: self.position(from: position, offset: offset)
        default: position
        }
    }

    func compare(_ position: UITextPosition, to other: UITextPosition) -> ComparisonResult {
        let a = (position as? MarkedPosition)?.offset ?? 0
        let b = (other as? MarkedPosition)?.offset ?? 0
        return a < b ? .orderedAscending : a > b ? .orderedDescending : .orderedSame
    }

    func offset(from: UITextPosition, to toPosition: UITextPosition) -> Int {
        ((toPosition as? MarkedPosition)?.offset ?? 0) - ((from as? MarkedPosition)?.offset ?? 0)
    }

    func position(within range: UITextRange, farthestIn direction: UITextLayoutDirection) -> UITextPosition? {
        switch direction {
        case .left, .up: range.start
        default: range.end
        }
    }

    func characterRange(byExtending position: UITextPosition, in direction: UITextLayoutDirection) -> UITextRange? {
        guard let position = position as? MarkedPosition else { return nil }
        switch direction {
        case .left, .up: return MarkedRange(0, position.offset)
        default: return MarkedRange(position.offset, length)
        }
    }

    func baseWritingDirection(for position: UITextPosition, in direction: UITextStorageDirection) -> NSWritingDirection {
        .leftToRight
    }

    func setBaseWritingDirection(_ writingDirection: NSWritingDirection, for range: UITextRange) {}

    /// Where the keyboard anchors its candidate bar: the terminal cursor.
    private var cursorRect: CGRect {
        guard let cursor = frameToDraw?.cursor else { return .zero }
        return cells(column: cursor.x, row: cursor.y, count: 1)
    }

    func firstRect(for range: UITextRange) -> CGRect { cursorRect }

    func caretRect(for position: UITextPosition) -> CGRect {
        CGRect(x: cursorRect.minX, y: cursorRect.minY, width: 2, height: cellHeight)
    }

    func selectionRects(for range: UITextRange) -> [UITextSelectionRect] { [] }

    func closestPosition(to point: CGPoint) -> UITextPosition? { MarkedPosition(length) }

    func closestPosition(to point: CGPoint, within range: UITextRange) -> UITextPosition? { range.end }

    func characterRange(at point: CGPoint) -> UITextRange? { nil }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
