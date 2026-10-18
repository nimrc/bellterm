package app.bellterm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses the fixture `render.rs` writes, so both sides agree on the layout. */
class RenderFrameTest {
    private fun fixture(): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("frame-v1.bin")).readBytes()

    @Test
    fun parsesTheRustFixture() {
        val frame = RenderFrame.parse(fixture())
        assertNotNull(frame)
        frame!!
        assertEquals(10, frame.cols)
        assertEquals(2, frame.rows)
        assertEquals(RenderFrame.Cursor(1, 1, true, RenderFrame.CURSOR_BAR), frame.cursor)
        assertEquals(0xffeeeeee.toInt(), frame.foreground)
        assertEquals(0xff111111.toInt(), frame.background)
        assertEquals(RenderFrame.CellRect(2, 0, 4, 1), frame.popup)
        val pane = frame.panes.single()
        assertEquals("p1", pane.id)
        assertTrue(pane.focused)
        assertTrue(pane.mouseReporting)
        assertFalse(pane.alternateScreen)
        assertEquals(listOf("hello", "xyz", "ok", "界"), frame.runs.map { it.text })
        val wide = frame.runs.last()
        assertEquals(6, wide.col)
        assertEquals(2, wide.cells)
        assertTrue(wide.has(RenderFrame.WIDE))
        assertTrue(wide.has(RenderFrame.BOLD))
        assertEquals(0xff405060.toInt(), wide.bg)
        assertEquals(pane, frame.paneAt(3, 1))
        assertNull(frame.paneAt(10, 0))
    }

    @Test
    fun rejectsOtherFormatVersions() {
        val bytes = fixture()
        bytes[0] = 99
        assertNull(RenderFrame.parse(bytes))
    }
}
