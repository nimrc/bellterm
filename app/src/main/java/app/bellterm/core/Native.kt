package app.bellterm.core

/**
 * The Rust core (native/). Sessions are opaque IDs; every call is cheap
 * and non-blocking except [nextEvent], which waits up to its timeout and must
 * run off the main thread.
 */
object Native {
    init {
        System.loadLibrary("bellterm")
        init()
    }

    private external fun init()

    /** A new Ed25519 identity, as OpenSSH private key text. */
    external fun generateKey(comment: String): String

    /** The `authorized_keys` line for a stored private key. */
    external fun publicKey(privateKey: String): String

    /**
     * Starts connecting with a route document (see `native/src/config.rs`);
     * progress arrives through [nextEvent]. The document holds secrets.
     */
    external fun connect(configJson: String): Long

    /** `{"private": …, "info": {algorithm, fingerprint, comment, public}}`; throws when unreadable. */
    external fun importKey(text: String, passphrase: String?): String

    /** Certificate details as JSON; throws when it is not a certificate. */
    external fun certificateInfo(certificate: String, privateKey: String): String

    /** `{"entries": […], "skipped": […]}` from OpenSSH client configuration text. */
    external fun parseSshConfig(text: String): String

    /** The next event as JSON, or null after [timeoutMs] without one. */
    external fun nextEvent(id: Long, timeoutMs: Int): String?

    external fun takeFrame(id: Long): ByteArray?

    external fun takeView(id: Long): String?

    /** True when the key was sent; false leaves it to text input. */
    external fun key(id: Long, pane: String?, keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean): Boolean

    external fun chord(id: Long, pane: String?, codepoint: Int, metaState: Int)

    external fun text(id: Long, pane: String?, text: String)

    external fun paste(id: Long, pane: String?, text: String)

    external fun touch(id: Long, pane: String, kind: Int, column: Int, row: Int, lines: Int)

    external fun resize(id: Long, cols: Int, rows: Int, cellWidth: Int, cellHeight: Int)

    /** Invokes an endpoint method; returns the request ID. */
    external fun request(id: Long, method: String, paramsJson: String): String

    external fun setTheme(id: Long, themeJson: String)

    external fun selectionText(id: Long, startColumn: Int, startRow: Int, endColumn: Int, endRow: Int): String

    external fun disconnect(id: Long)

    const val TOUCH_TAP = 0
    const val TOUCH_SCROLL_UP = 1
    const val TOUCH_SCROLL_DOWN = 2

    // KeyEvent meta-state bits the extra-keys row synthesises.
    const val META_SHIFT = 0x1
    const val META_ALT = 0x2
    const val META_CTRL = 0x1000
}
