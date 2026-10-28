import Foundation
import Testing
@testable import Bellterm

/// Packs frames the way `render.rs` does.
private struct Packer {
    var bytes: [UInt8] = []

    mutating func u8(_ value: Int) { bytes.append(UInt8(value)) }
    mutating func u16(_ value: Int) { bytes += [UInt8(value & 0xff), UInt8(value >> 8 & 0xff)] }
    mutating func u32(_ value: UInt32) { bytes += (0..<4).map { UInt8(value >> ($0 * 8) & 0xff) } }
    mutating func rect(_ x: Int, _ y: Int, _ w: Int, _ h: Int) { [x, y, w, h].forEach { u16($0) } }
    mutating func str(_ text: String) {
        u16(text.utf8.count)
        bytes += Array(text.utf8)
    }
}

struct RenderFrameTests {
    private func frame(version: UInt32 = 1) -> Data {
        var p = Packer()
        p.u32(version)
        p.u16(80); p.u16(24)
        p.u16(3); p.u16(4); p.u8(1); p.u8(RenderFrame.cursorBar)
        p.u32(0xffd8dee9); p.u32(0xff14161b); p.u32(0xff7fd6d2)
        p.u8(1); p.rect(10, 5, 40, 10)
        p.u16(2)
        p.rect(0, 0, 40, 24); p.u8(1 | 4); p.str("w1:p1")
        p.rect(40, 0, 40, 24); p.u8(2); p.str("w1:p2")
        p.u32(2)
        p.u16(0); p.u16(0); p.u16(5); p.u16(Int(RenderFrame.ascii | RenderFrame.bold))
        p.u32(0xffffffff); p.u32(0xff000000); p.str("hello")
        p.u16(1); p.u16(2); p.u16(2); p.u16(Int(RenderFrame.wide))
        p.u32(0xff00ff00); p.u32(0xff000000); p.str("界")
        return Data(p.bytes)
    }

    @Test func parsesEveryField() throws {
        let f = try #require(RenderFrame(frame()))
        #expect(f.cols == 80 && f.rows == 24)
        #expect(f.cursor == .init(x: 3, y: 4, visible: true, shape: RenderFrame.cursorBar))
        #expect(f.foreground == 0xffd8dee9 && f.background == 0xff14161b && f.cursorColor == 0xff7fd6d2)
        #expect(f.popup == .init(x: 10, y: 5, width: 40, height: 10))
        #expect(f.panes.map(\.id) == ["w1:p1", "w1:p2"])
        #expect(f.panes[0].focused && f.panes[0].alternateScreen && !f.panes[0].mouseReporting)
        #expect(f.panes[1].mouseReporting && !f.panes[1].focused)
        #expect(f.runs[0].text == "hello" && f.runs[0].has(RenderFrame.bold) && f.runs[0].has(RenderFrame.ascii))
        #expect(f.runs[1] == .init(row: 1, col: 2, cells: 2, flags: RenderFrame.wide, fg: 0xff00ff00, bg: 0xff000000, text: "界"))
        #expect(f.paneAt(column: 45, row: 3)?.id == "w1:p2")
    }

    @Test func rejectsOtherVersions() {
        #expect(RenderFrame(frame(version: 2)) == nil)
    }

    @Test func rejectsTruncatedBuffers() {
        let full = frame()
        for length in [0, 3, 20, full.count - 1] {
            #expect(RenderFrame(full.prefix(length)) == nil)
        }
    }
}
