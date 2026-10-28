import Foundation

/// A frame read back from libghostty, as packed by `render.rs`.
struct RenderFrame: Sendable {
    struct Cursor: Sendable, Equatable {
        var x: Int
        var y: Int
        var visible: Bool
        var shape: Int
    }

    struct CellRect: Sendable, Equatable {
        var x: Int
        var y: Int
        var width: Int
        var height: Int

        func contains(column: Int, row: Int) -> Bool {
            column >= x && column < x + width && row >= y && row < y + height
        }
    }

    struct PaneArea: Sendable, Equatable {
        var id: String
        var rect: CellRect
        var focused: Bool
        var mouseReporting: Bool
        var alternateScreen: Bool
    }

    struct Run: Sendable, Equatable {
        var row: Int
        var col: Int
        var cells: Int
        var flags: UInt16
        /// Opaque ARGB.
        var fg: UInt32
        var bg: UInt32
        var text: String

        func has(_ flag: UInt16) -> Bool { flags & flag != 0 }
    }

    static let formatVersion: UInt32 = 1

    // Mirrors `render::flags`.
    static let bold: UInt16 = 1
    static let italic: UInt16 = 2
    static let underline: UInt16 = 4
    static let strikethrough: UInt16 = 8
    static let faint: UInt16 = 16
    static let wide: UInt16 = 32
    static let invisible: UInt16 = 64
    static let background: UInt16 = 128
    static let ascii: UInt16 = 256

    static let cursorBlock = 0
    static let cursorBar = 1
    static let cursorUnderline = 2
    static let cursorHollow = 3

    var cols: Int
    var rows: Int
    var cursor: Cursor
    var foreground: UInt32
    var background: UInt32
    var cursorColor: UInt32
    var popup: CellRect?
    var panes: [PaneArea]
    var runs: [Run]

    func paneAt(column: Int, row: Int) -> PaneArea? {
        panes.first { $0.rect.contains(column: column, row: row) }
    }

    /// Nil when the buffer is truncated or packed by another format version.
    init?(_ data: Data) {
        var reader = Reader(bytes: [UInt8](data))
        guard let parsed = try? Self.parse(&reader) else { return nil }
        self = parsed
    }

    private init(
        cols: Int, rows: Int, cursor: Cursor, foreground: UInt32, background: UInt32,
        cursorColor: UInt32, popup: CellRect?, panes: [PaneArea], runs: [Run]
    ) {
        self.cols = cols
        self.rows = rows
        self.cursor = cursor
        self.foreground = foreground
        self.background = background
        self.cursorColor = cursorColor
        self.popup = popup
        self.panes = panes
        self.runs = runs
    }

    private static func parse(_ r: inout Reader) throws -> RenderFrame {
        guard try r.u32() == formatVersion else { throw Reader.Truncated() }
        let cols = try r.u16()
        let rows = try r.u16()
        let cursor = Cursor(x: try r.u16(), y: try r.u16(), visible: try r.u8() != 0, shape: try r.u8())
        let foreground = try r.u32()
        let background = try r.u32()
        let cursorColor = try r.u32()
        let popup = try r.u8() != 0 ? try r.rect() : nil
        var panes: [PaneArea] = []
        for _ in 0..<(try r.u16()) {
            let rect = try r.rect()
            let bits = try r.u8()
            panes.append(PaneArea(
                id: try r.str(), rect: rect,
                focused: bits & 1 != 0, mouseReporting: bits & 2 != 0, alternateScreen: bits & 4 != 0
            ))
        }
        var runs: [Run] = []
        let count = Int(try r.u32())
        runs.reserveCapacity(min(count, 1 << 16))
        for _ in 0..<count {
            runs.append(Run(
                row: try r.u16(), col: try r.u16(), cells: try r.u16(), flags: UInt16(try r.u16()),
                fg: try r.u32(), bg: try r.u32(), text: try r.str()
            ))
        }
        return RenderFrame(
            cols: cols, rows: rows, cursor: cursor, foreground: foreground, background: background,
            cursorColor: cursorColor, popup: popup, panes: panes, runs: runs
        )
    }
}

/// Little-endian reads that throw instead of trapping on a short buffer.
private struct Reader {
    struct Truncated: Error {}

    let bytes: [UInt8]
    var offset = 0

    mutating func take(_ count: Int) throws -> ArraySlice<UInt8> {
        guard count <= bytes.count - offset else { throw Truncated() }
        defer { offset += count }
        return bytes[offset..<offset + count]
    }

    mutating func u8() throws -> Int { Int(try take(1).first ?? 0) }

    mutating func u16() throws -> Int {
        let b = try take(2)
        return Int(b[b.startIndex]) | Int(b[b.startIndex + 1]) << 8
    }

    mutating func u32() throws -> UInt32 {
        try take(4).reversed().reduce(0) { $0 << 8 | UInt32($1) }
    }

    mutating func rect() throws -> RenderFrame.CellRect {
        RenderFrame.CellRect(x: try u16(), y: try u16(), width: try u16(), height: try u16())
    }

    mutating func str() throws -> String {
        String(decoding: try take(try u16()), as: UTF8.self)
    }
}
