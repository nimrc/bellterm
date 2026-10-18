package app.bellterm.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A frame read back from libghostty, as packed by `render.rs`. */
class RenderFrame(
    val cols: Int,
    val rows: Int,
    val cursor: Cursor,
    val foreground: Int,
    val background: Int,
    val cursorColor: Int,
    val popup: CellRect?,
    val panes: List<PaneArea>,
    val runs: List<Run>,
) {
    data class Cursor(val x: Int, val y: Int, val visible: Boolean, val shape: Int)

    data class CellRect(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(column: Int, row: Int) =
            column >= x && column < x + width && row >= y && row < y + height
    }

    data class PaneArea(
        val id: String,
        val rect: CellRect,
        val focused: Boolean,
        val mouseReporting: Boolean,
        val alternateScreen: Boolean,
    )

    class Run(
        val row: Int,
        val col: Int,
        val cells: Int,
        val flags: Int,
        val fg: Int,
        val bg: Int,
        val text: String,
    ) {
        fun has(flag: Int) = flags and flag != 0
    }

    fun paneAt(column: Int, row: Int): PaneArea? = panes.firstOrNull { it.rect.contains(column, row) }

    companion object {
        const val FORMAT_VERSION = 1

        const val BOLD = 1
        const val ITALIC = 2
        const val UNDERLINE = 4
        const val STRIKETHROUGH = 8
        const val FAINT = 16
        const val WIDE = 32
        const val INVISIBLE = 64
        const val BACKGROUND = 128
        const val ASCII = 256

        const val CURSOR_BLOCK = 0
        const val CURSOR_BAR = 1
        const val CURSOR_UNDERLINE = 2
        const val CURSOR_HOLLOW = 3

        fun parse(bytes: ByteArray): RenderFrame? {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int != FORMAT_VERSION) return null
            val cols = buf.u16()
            val rows = buf.u16()
            val cursor = Cursor(buf.u16(), buf.u16(), buf.get().toInt() != 0, buf.get().toInt())
            val foreground = buf.int
            val background = buf.int
            val cursorColor = buf.int
            val popup = if (buf.get().toInt() != 0) buf.rect() else null
            val panes = List(buf.u16()) {
                val rect = buf.rect()
                val bits = buf.get().toInt()
                PaneArea(buf.str(), rect, bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            }
            val runs = List(buf.int) {
                Run(buf.u16(), buf.u16(), buf.u16(), buf.u16(), buf.int, buf.int, buf.str())
            }
            return RenderFrame(cols, rows, cursor, foreground, background, cursorColor, popup, panes, runs)
        }

        private fun ByteBuffer.u16() = short.toInt() and 0xffff

        private fun ByteBuffer.rect() = CellRect(u16(), u16(), u16(), u16())

        private fun ByteBuffer.str(): String {
            val bytes = ByteArray(u16())
            get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
