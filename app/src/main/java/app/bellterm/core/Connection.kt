package app.bellterm.core

import app.bellterm.data.Node
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.IOException

/**
 * One attached daemon. A reader thread drains the native event queue and
 * publishes the latest frame and shell view as state; the UI renders whatever
 * is current, so it never falls behind the daemon.
 */
class Connection(val node: Node) {
    sealed interface State {
        data object Connecting : State
        data class Connected(val serverVersion: String) : State
        data class Disconnected(val reason: String) : State
        /** [host] is `address:port` of the hop whose key is not trusted, [hop] its place in the route. */
        data class UntrustedHostKey(val hop: Int, val host: String, val fingerprint: String, val changed: Boolean) : State
        /** Hop number [hop] of the route, [host] as `address:port`, refused its key or password. */
        data class AuthRejected(val hop: Int, val host: String) : State
        /** The route could not be built, such as a password left unanswered. */
        data class Failed(val reason: String) : State
        /**
         * Herdr on the host and this app cannot work together; updating one
         * fixes it, not retrying. [daemonOutdated] says which: the host's
         * Herdr when true, else this app. [minVersion] is the oldest Herdr
         * this app accepts.
         */
        data class Incompatible(val daemonOutdated: Boolean, val serverVersion: String?, val minVersion: String) : State {
            companion object {
                fun parse(event: JSONObject) = Incompatible(
                    daemonOutdated = event.optString("outdated") != "app",
                    serverVersion = event.optString("server_version").takeUnless { event.isNull("server_version") || it.isBlank() },
                    minVersion = event.optString("min_version"),
                )
            }
        }
    }

    sealed interface Event {
        data class Notification(val title: String, val body: String?, val attention: Boolean, val pane: String?) : Event
        data class Clipboard(val text: String) : Event
        data class Problem(val message: String) : Event
        data object Bell : Event
    }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state
    private val _view = MutableStateFlow<ShellView?>(null)
    val view: StateFlow<ShellView?> = _view
    private val _frame = MutableStateFlow<RenderFrame?>(null)
    val frame: StateFlow<RenderFrame?> = _frame
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events

    @Volatile
    private var id = 0L

    @Volatile
    private var closed = false

    /** The grid the view wants; re-sent once the daemon's boot is known. */
    @Volatile
    private var grid: IntArray? = null

    val isOpen: Boolean get() = !closed && _state.value.let { it is State.Connecting || it is State.Connected }

    /** [configJson] is the route document; it holds secrets and is not kept. */
    fun start(configJson: String) {
        _state.value = State.Connecting
        closed = false
        val session = try {
            Native.connect(configJson)
        } catch (e: IOException) {
            _state.value = State.Disconnected(e.message ?: "Could not connect")
            return
        }
        id = session
        Thread({ pump(session) }, "bellterm-events").apply { isDaemon = true }.start()
    }

    fun fail(reason: String) {
        _state.value = State.Failed(reason)
    }

    private fun pump(session: Long) {
        while (!closed && id == session) {
            val json = Native.nextEvent(session, 500) ?: continue
            val event = JSONObject(json)
            when (event.optString("type")) {
                "frame" -> Native.takeFrame(session)?.let { RenderFrame.parse(it) }?.let { _frame.value = it }
                "snapshot" -> Native.takeView(session)?.let {
                    val first = _view.value == null
                    _view.value = ShellView.parse(it)
                    // A resize before the first snapshot had no boot to carry.
                    if (first) grid?.let { g -> Native.resize(session, g[0], g[1], g[2], g[3]) }
                }
                "connected" -> _state.value = State.Connected(event.optString("server_version"))
                "notification" -> _events.tryEmit(
                    Event.Notification(
                        event.optString("title"),
                        event.optString("body").takeUnless { event.isNull("body") },
                        event.optBoolean("attention"),
                        event.optString("pane").takeUnless { event.isNull("pane") },
                    ),
                )
                "clipboard" -> _events.tryEmit(Event.Clipboard(event.optString("text")))
                "bell" -> _events.tryEmit(Event.Bell)
                "rejected" -> _events.tryEmit(Event.Problem(event.optString("reason")))
                "version_mismatch" -> _state.value = State.Incompatible.parse(event)
                "auth_rejected" -> {
                    _state.value = State.AuthRejected(event.optInt("hop"), event.optString("host"))
                    finish(session)
                    return
                }
                "host_key" -> {
                    _state.value = State.UntrustedHostKey(
                        event.optInt("hop"),
                        event.optString("host"),
                        event.optString("fingerprint"),
                        event.optBoolean("changed"),
                    )
                    finish(session)
                    return
                }
                "disconnected", "closed" -> {
                    if (_state.value !is State.Incompatible) {
                        _state.value = State.Disconnected(event.optString("reason").ifBlank { "Disconnected" })
                    }
                    finish(session)
                    return
                }
            }
        }
    }

    private fun finish(session: Long) {
        Native.disconnect(session)
        if (id == session) id = 0
    }

    /** The ID input and requests go to, or 0 when not connected. */
    private fun live(): Long = if (closed) 0 else id

    fun close() {
        closed = true
        val session = id
        id = 0
        if (session != 0L) Native.disconnect(session)
        if (_state.value is State.Connecting || _state.value is State.Connected) {
            _state.value = State.Disconnected("Disconnected")
        }
    }

    fun key(pane: String?, keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean): Boolean =
        live().let { it != 0L && Native.key(it, pane, keyCode, metaState, unicode, repeat) }

    fun chord(pane: String?, codepoint: Int, metaState: Int) {
        live().takeIf { it != 0L }?.let { Native.chord(it, pane, codepoint, metaState) }
    }

    fun text(pane: String?, text: String) {
        live().takeIf { it != 0L }?.let { Native.text(it, pane, text) }
    }

    fun paste(pane: String?, text: String) {
        live().takeIf { it != 0L }?.let { Native.paste(it, pane, text) }
    }

    fun touch(pane: String, kind: Int, column: Int, row: Int, lines: Int = 1) {
        live().takeIf { it != 0L }?.let { Native.touch(it, pane, kind, column, row, lines) }
    }

    fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        grid = intArrayOf(cols, rows, cellWidth, cellHeight)
        live().takeIf { it != 0L }?.let { Native.resize(it, cols, rows, cellWidth, cellHeight) }
    }

    fun setTheme(themeJson: String) {
        live().takeIf { it != 0L }?.let { Native.setTheme(it, themeJson) }
    }

    fun selectionText(startColumn: Int, startRow: Int, endColumn: Int, endRow: Int): String =
        live().takeIf { it != 0L }?.let { Native.selectionText(it, startColumn, startRow, endColumn, endRow) } ?: ""

    /** Invokes a Herdr endpoint method; failures surface as [Event.Problem]. */
    fun request(method: String, params: JSONObject) {
        val session = live()
        if (session == 0L) return
        try {
            Native.request(session, method, params.toString())
        } catch (e: IOException) {
            _events.tryEmit(Event.Problem(e.message ?: "$method failed"))
        }
    }

    // Herdr's own verbs, as its other clients use them.
    fun focusWorkspace(id: String) = request("workspace.focus", JSONObject().put("workspace_id", id))
    fun focusTab(id: String) = request("tab.focus", JSONObject().put("tab_id", id))
    fun focusPane(id: String) = request("pane.focus", JSONObject().put("pane_id", id))
    /** A blank [label] leaves the name to Herdr, which numbers the tab. */
    fun newTab(workspace: String, label: String = "") = request(
        "tab.create",
        JSONObject().put("workspace_id", workspace).put("focus", true).apply {
            label.trim().takeIf { it.isNotEmpty() }?.let { put("label", it) }
        },
    )
    fun closeTab(id: String) = request("tab.close", JSONObject().put("tab_id", id))
    fun renameTab(id: String, label: String) = request("tab.rename", JSONObject().put("tab_id", id).put("label", label))
    fun split(pane: String, direction: String) =
        request("pane.split", JSONObject().put("target_pane_id", pane).put("direction", direction).put("focus", true))
    fun closePane(id: String) = request("pane.close", JSONObject().put("pane_id", id))
    fun toggleZoom(id: String) = request("pane.zoom", JSONObject().put("pane_id", id).put("mode", "toggle"))
    fun newWorkspace(source: String?) = request(
        "workspace.create",
        JSONObject().put("focus", true).apply { source?.let { put("source_workspace_id", it) } },
    )
    fun renameWorkspace(id: String, label: String) =
        request("workspace.rename", JSONObject().put("workspace_id", id).put("label", label))
    fun closeWorkspace(id: String) = request("workspace.close", JSONObject().put("workspace_id", id))
}
