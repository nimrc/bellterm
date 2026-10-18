package app.bellterm.ui.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import app.bellterm.core.RenderFrame
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the frames libghostty produced and turns touch and keyboard input into
 * Herdr input. Rendering reads only the latest prepared [frame]; nothing here
 * talks to the daemon except through [listener].
 */
class TerminalView(context: Context) : View(context) {
    interface Listener {
        /** The grid that fits the view, in cells and cell pixels. */
        fun onGrid(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int)
        fun onFocusPane(pane: RenderFrame.PaneArea)
        /** A tap at a cell, relative to the pane or the open popup. */
        fun onTap(pane: RenderFrame.PaneArea?, column: Int, row: Int)
        fun onScroll(pane: RenderFrame.PaneArea?, column: Int, row: Int, up: Boolean, lines: Int)
        fun onKey(keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean): Boolean
        fun onText(text: String)
        fun onChord(codepoint: Int, metaState: Int)
        fun onCopy(startColumn: Int, startRow: Int, endColumn: Int, endRow: Int)
        fun onPaste()
        fun onFontSize(sp: Float)
        /** Ctrl or Alt latched by the extra-keys row was used up. */
        fun onModifiersConsumed()
    }

    var listener: Listener? = null

    var frame: RenderFrame? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Modifiers latched by the extra-keys row, as KeyEvent meta-state bits. */
    var latchedMeta = 0

    var fontSizeSp = 13f
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (clamped == field && cellWidth > 0f) return
            field = clamped
            measureCells()
            reportGrid()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val fill = Paint()
    private var regular: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private var bold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private var italic: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private var boldItalic: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    private var symbols: Typeface? = null
    private var typefaces: TerminalTypefaces? = null

    /** The font to draw with; the grid is re-measured to its cell size. */
    fun setTypefaces(faces: TerminalTypefaces) {
        if (faces == typefaces) return
        typefaces = faces
        regular = faces.regular
        bold = faces.bold
        italic = faces.italic
        boldItalic = faces.boldItalic
        symbols = faces.symbols
        measureCells()
        reportGrid()
        invalidate()
    }

    private var cellWidth = 0f
    private var cellHeight = 0f
    private var baseline = 0f
    private var lastGrid = IntArray(4)

    private var composing = ""
    private var selection: Pair<IntArray, IntArray>? = null
    private var actionMode: ActionMode? = null

    private val scroller = OverScroller(context)
    private var scrollRemainder = 0f
    private var flingLast = 0
    private var flingAnchor: Pair<Float, Float>? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCells()
    }

    private fun measureCells() {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        paint.typeface = regular
        cellWidth = paint.measureText("M")
        val metrics = paint.fontMetrics
        cellHeight = (metrics.descent - metrics.ascent).let { it + it * 0.1f }
        baseline = -metrics.ascent + (cellHeight - (metrics.descent - metrics.ascent)) / 2
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportGrid()
    }

    private fun reportGrid() {
        if (width == 0 || height == 0 || cellWidth <= 0f) return
        val grid = intArrayOf(
            max(1, floor(width / cellWidth).toInt()),
            max(1, floor(height / cellHeight).toInt()),
            cellWidth.toInt(),
            cellHeight.toInt(),
        )
        if (grid.contentEquals(lastGrid)) return
        lastGrid = grid
        listener?.onGrid(grid[0], grid[1], grid[2], grid[3])
    }

    // Drawing

    override fun onDraw(canvas: Canvas) {
        val frame = frame
        if (frame == null) {
            canvas.drawColor(0xff14161b.toInt())
            return
        }
        canvas.drawColor(frame.background)
        for (run in frame.runs) {
            if (run.has(RenderFrame.BACKGROUND)) {
                fill.color = run.bg
                canvas.drawRect(x(run.col), y(run.row), x(run.col + run.cells), y(run.row + 1), fill)
            }
        }
        drawSelection(canvas)
        for (run in frame.runs) drawRun(canvas, run, run.fg)
        drawCursor(canvas, frame)
    }

    private fun x(col: Int) = col * cellWidth
    private fun y(row: Int) = row * cellHeight

    private fun drawRun(canvas: Canvas, run: RenderFrame.Run, color: Int) {
        if (run.has(RenderFrame.INVISIBLE)) return
        paint.color = if (run.has(RenderFrame.FAINT)) (color and 0x00ffffff) or 0x99000000.toInt() else color
        paint.typeface = when {
            run.has(RenderFrame.BOLD) && run.has(RenderFrame.ITALIC) -> boldItalic
            run.has(RenderFrame.BOLD) -> bold
            run.has(RenderFrame.ITALIC) -> italic
            else -> regular
        }
        val left = x(run.col)
        val top = y(run.row)
        if (run.text.isNotBlank()) {
            if (run.has(RenderFrame.ASCII)) {
                paint.textScaleX = 1f
                canvas.drawText(run.text, left, top + baseline, paint)
            } else {
                // Nerd Font icons live in the Private Use Area, which no system
                // font covers: draw them from the symbols font when the chosen
                // face lacks them.
                if (!paint.hasGlyph(run.text)) symbols?.takeIf { it != paint.typeface }?.let { face ->
                    val previous = paint.typeface
                    paint.typeface = face
                    if (!paint.hasGlyph(run.text)) paint.typeface = previous
                }
                // A cluster gets exactly its cells: centred, and narrowed if the
                // fallback font draws it wider than the grid allows.
                val room = run.cells * cellWidth
                val natural = paint.measureText(run.text)
                paint.textScaleX = if (natural > room && natural > 0f) room / natural else 1f
                val drawn = min(natural, room)
                canvas.drawText(run.text, left + (room - drawn) / 2, top + baseline, paint)
                paint.textScaleX = 1f
            }
        }
        val right = left + run.cells * cellWidth
        if (run.has(RenderFrame.UNDERLINE)) {
            val line = top + cellHeight - max(1f, cellHeight / 14f)
            canvas.drawRect(left, line, right, line + max(1f, cellHeight / 16f), paint)
        }
        if (run.has(RenderFrame.STRIKETHROUGH)) {
            val line = top + cellHeight / 2
            canvas.drawRect(left, line, right, line + max(1f, cellHeight / 16f), paint)
        }
    }

    private fun drawCursor(canvas: Canvas, frame: RenderFrame) {
        val cursor = frame.cursor
        if (composing.isNotEmpty()) {
            drawComposing(canvas, frame)
            return
        }
        if (!cursor.visible || cursor.x >= frame.cols || cursor.y >= frame.rows) return
        val left = x(cursor.x)
        val top = y(cursor.y)
        fill.color = frame.cursorColor
        val stroke = max(2f, cellWidth / 6f)
        when (cursor.shape) {
            RenderFrame.CURSOR_BAR -> canvas.drawRect(left, top, left + stroke, top + cellHeight, fill)
            RenderFrame.CURSOR_UNDERLINE -> canvas.drawRect(left, top + cellHeight - stroke, left + cellWidth, top + cellHeight, fill)
            RenderFrame.CURSOR_HOLLOW -> {
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = stroke / 2
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, fill)
                fill.style = Paint.Style.FILL
            }
            else -> {
                val under = frame.runs.firstOrNull { it.row == cursor.y && cursor.x >= it.col && cursor.x < it.col + it.cells }
                val wide = under?.has(RenderFrame.WIDE) == true
                canvas.drawRect(left, top, left + cellWidth * (if (wide) 2 else 1), top + cellHeight, fill)
                if (under != null) {
                    val text = if (under.has(RenderFrame.ASCII)) under.text[cursor.x - under.col].toString() else under.text
                    drawRun(
                        canvas,
                        RenderFrame.Run(cursor.y, if (wide) under.col else cursor.x, if (wide) 2 else 1, under.flags and RenderFrame.BACKGROUND.inv(), 0, 0, text),
                        frame.background,
                    )
                }
            }
        }
    }

    /** IME composition, drawn at the cursor until the IME commits it. */
    private fun drawComposing(canvas: Canvas, frame: RenderFrame) {
        paint.typeface = regular
        paint.textScaleX = 1f
        val width = paint.measureText(composing)
        val left = min(x(frame.cursor.x), max(0f, this.width - width))
        val top = y(frame.cursor.y)
        fill.color = frame.background
        canvas.drawRect(left, top, left + width, top + cellHeight, fill)
        paint.color = frame.foreground
        canvas.drawText(composing, left, top + baseline, paint)
        canvas.drawRect(left, top + cellHeight - 2f, left + width, top + cellHeight, paint)
    }

    private fun drawSelection(canvas: Canvas) {
        val (start, end) = ordered() ?: return
        fill.color = 0x664a90e2
        for (row in start[1]..end[1]) {
            val from = if (row == start[1]) start[0] else 0
            val to = if (row == end[1]) end[0] else (frame?.cols ?: 0) - 1
            canvas.drawRect(x(from), y(row), x(to + 1), y(row + 1), fill)
        }
    }

    private fun ordered(): Pair<IntArray, IntArray>? {
        val (a, b) = selection ?: return null
        return if (a[1] < b[1] || (a[1] == b[1] && a[0] <= b[0])) a to b else b to a
    }

    // Touch

    private fun cellAt(ex: Float, ey: Float): IntArray? {
        val frame = frame ?: return null
        val column = floor(ex / cellWidth).toInt()
        val row = floor(ey / cellHeight).toInt()
        if (column < 0 || row < 0 || column >= frame.cols || row >= frame.rows) return null
        return intArrayOf(column, row)
    }

    /** The pane under a cell and the cell relative to it, or to the popup. */
    private fun target(column: Int, row: Int): Triple<RenderFrame.PaneArea?, Int, Int>? {
        val frame = frame ?: return null
        frame.popup?.let { popup ->
            if (!popup.contains(column, row)) return null
            return Triple(null, column - popup.x, row - popup.y)
        }
        val pane = frame.paneAt(column, row) ?: return null
        return Triple(pane, column - pane.rect.x, row - pane.rect.y)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scrollRemainder = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selection != null) {
                clearSelection()
                return true
            }
            val cell = cellAt(e.x, e.y)
            val target = cell?.let { target(it[0], it[1]) }
            val pane = target?.first
            when {
                target == null -> {}
                pane != null && !pane.focused -> listener?.onFocusPane(pane)
                pane == null || pane.mouseReporting -> listener?.onTap(pane, target.second, target.third)
            }
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (selection != null || e2.pointerCount > 1) return false
            scrollBy(e2.x, e2.y, distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (selection != null || abs(velocityY) < abs(velocityX)) return false
            flingAnchor = e2.x to e2.y
            flingLast = 0
            scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
            postOnAnimation(::stepFling)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val cell = cellAt(e.x, e.y) ?: return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            selection = wordAt(cell) ?: (cell to cell.copyOf())
            selecting = true
            invalidate()
        }
    })

    private var selecting = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fontSizeSp *= detector.scaleFactor
            listener?.onFontSize(fontSizeSp)
            return true
        }
    })

    private fun scrollBy(ex: Float, ey: Float, distanceY: Float) {
        scrollRemainder += distanceY
        val lines = (scrollRemainder / cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * cellHeight
        val cell = cellAt(ex, ey) ?: return
        val target = target(cell[0], cell[1]) ?: return
        // Dragging the content up reveals what is below it.
        listener?.onScroll(target.first, target.second, target.third, up = lines < 0, lines = abs(lines))
    }

    private fun stepFling() {
        if (!scroller.computeScrollOffset()) return
        val (fx, fy) = flingAnchor ?: return
        val y = scroller.currY
        scrollBy(fx, fy, (y - flingLast).toFloat())
        flingLast = y
        postOnAnimation(::stepFling)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> cellAt(event.x, event.y)?.let { cell ->
                    selection = selection?.first?.let { it to cell }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    startSelectionMode()
                }
            }
            return true
        }
        if (scaler.isInProgress) return true
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /** The run of non-blank characters around a cell, as a double-tap would pick. */
    private fun wordAt(cell: IntArray): Pair<IntArray, IntArray>? {
        val frame = frame ?: return null
        val chars = CharArray(frame.cols) { ' ' }
        frame.runs.filter { it.row == cell[1] && it.has(RenderFrame.ASCII) }.forEach { run ->
            run.text.forEachIndexed { i, c -> if (run.col + i < chars.size) chars[run.col + i] = c }
        }
        if (chars[cell[0]].isWhitespace()) return null
        var start = cell[0]
        var end = cell[0]
        while (start > 0 && !chars[start - 1].isWhitespace()) start--
        while (end < chars.size - 1 && !chars[end + 1].isWhitespace()) end++
        return intArrayOf(start, cell[1]) to intArrayOf(end, cell[1])
    }

    private fun startSelectionMode() {
        actionMode?.finish()
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, android.R.id.copy, 0, android.R.string.copy)
                menu.add(0, android.R.id.paste, 1, android.R.string.paste)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    android.R.id.copy -> ordered()?.let { (a, b) -> listener?.onCopy(a[0], a[1], b[0], b[1]) }
                    android.R.id.paste -> listener?.onPaste()
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                selection = null
                invalidate()
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                val (a, b) = ordered() ?: return
                outRect.set(x(a[0]).toInt(), y(a[1]).toInt(), x(b[0] + 1).toInt(), y(b[1] + 1).toInt())
            }
        }, ActionMode.TYPE_FLOATING)
    }

    fun clearSelection() {
        actionMode?.finish()
        selection = null
        invalidate()
    }

    // Keyboard

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions or autocorrect: keystrokes go straight to the pane.
        // CJK keyboards still compose, shown at the cursor until committed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                sendText(text.toString())
                invalidate()
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                invalidate()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) {
                    val text = composing
                    composing = ""
                    sendText(text)
                    invalidate()
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // The keyboard's own edit: its composition is its business here.
                repeat(max(1, beforeLength)) { sendKeyNow(KeyEvent.KEYCODE_DEL, 0, 0, false) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleKey(event.keyCode, event)
                }
                return true
            }
        }
    }

    private fun sendText(text: String) {
        if (text.isEmpty()) return
        val meta = latchedMeta
        if (meta and (KEY_CTRL or KEY_ALT) != 0 && text.codePointCount(0, text.length) == 1) {
            listener?.onChord(text.codePointAt(0), meta)
            consumeLatch()
            return
        }
        listener?.onText(text)
    }

    /**
     * Sends text the keyboard is still composing before a key that bypasses
     * it, so the key lands after that text rather than ahead of it, and makes
     * the keyboard start afresh so it does not send the text again.
     */
    private fun commitComposing() {
        if (composing.isEmpty()) return
        val text = composing
        composing = ""
        sendText(text)
        invalidate()
        context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
    }

    /** A special key from the extra-keys row, with any latched modifiers. */
    fun sendKey(keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean) {
        commitComposing()
        sendKeyNow(keyCode, metaState, unicode, repeat)
    }

    private fun sendKeyNow(keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean) {
        listener?.onKey(keyCode, metaState or latchedMeta, unicode, repeat)
        consumeLatch()
    }

    private fun consumeLatch() {
        if (latchedMeta != 0) {
            latchedMeta = 0
            listener?.onModifiersConsumed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return super.onKeyDown(keyCode, event)
        }
        handleKey(keyCode, event)
        return true
    }

    private fun handleKey(keyCode: Int, event: KeyEvent) {
        commitComposing()
        val meta = event.metaState or latchedMeta
        val unicode = event.getUnicodeChar(meta and (KEY_CTRL or KEY_ALT).inv())
        val sent = listener?.onKey(keyCode, meta, unicode, event.repeatCount > 0) == true
        if (!sent && unicode != 0 && unicode and KeyCharacterMapCombining == 0) {
            listener?.onText(String(Character.toChars(unicode)))
        }
        consumeLatch()
    }

    companion object {
        const val MIN_FONT_SP = 7f
        const val MAX_FONT_SP = 32f
        private const val KEY_CTRL = KeyEvent.META_CTRL_ON
        private const val KEY_ALT = KeyEvent.META_ALT_ON
        private const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT
    }
}
