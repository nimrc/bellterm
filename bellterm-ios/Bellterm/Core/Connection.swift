import Foundation
import Observation

/// One attached daemon. A reader thread drains the core's event queue and
/// publishes the latest frame and shell view; the UI renders whatever is
/// current, so it never falls behind the daemon.
@MainActor
@Observable
final class Connection {
    enum State: Equatable {
        case connecting
        case connected(serverVersion: String)
        case disconnected(reason: String)
        /// `host` is `address:port` of the hop whose key is not trusted, `hop` its place in the route.
        case untrustedHostKey(hop: Int, host: String, fingerprint: String, changed: Bool)
        /// Hop number `hop` of the route refused its key or password.
        case authRejected(hop: Int, host: String)
        /// The route could not be built, such as a password left unanswered.
        case failed(reason: String)
        /// Herdr on the host and this app cannot work together; updating one
        /// fixes it, not retrying. `daemonOutdated` says which.
        case incompatible(daemonOutdated: Bool, serverVersion: String?, minVersion: String)

        var isOpen: Bool {
            switch self {
            case .connecting, .connected: true
            default: false
            }
        }
    }

    enum Event: Sendable {
        case notification(title: String, body: String?, attention: Bool, pane: String?)
        case clipboard(String)
        case problem(String)
        case bell
    }

    // `touch` kinds, as `bellterm-ffi` reads them.
    static let touchTap: Int32 = 0
    static let touchScrollUp: Int32 = 1
    static let touchScrollDown: Int32 = 2

    let node: Node
    private(set) var state: State = .connecting
    private(set) var view: ShellView?
    private(set) var frame: RenderFrame?
    let events: AsyncStream<Event>
    private let emit: AsyncStream<Event>.Continuation

    private var session: Session?
    /// The grid the view wants; re-sent once the daemon's boot is known.
    private var grid: Grid?

    init(node: Node) {
        self.node = node
        (events, emit) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(32))
    }

    var isOpen: Bool { session != nil && state.isOpen }

    /// `configJson` is the route document; it holds secrets and is not kept.
    func start(configJson: String) {
        state = .connecting
        let session: Session
        do {
            session = try Session.connect(configJson: configJson)
        } catch {
            state = .disconnected(reason: error.localizedDescription)
            return
        }
        self.session = session
        // next_event blocks for up to its timeout, so it gets its own thread
        // rather than a slot in the cooperative pool.
        let owner = Owner(self)
        let thread = Thread { Self.pump(session, into: owner) }
        thread.name = "bellterm-events"
        thread.start()
    }

    func fail(_ reason: String) {
        state = .failed(reason: reason)
    }

    /// Lets the reader thread outlive a dropped connection without keeping it.
    private final class Owner: @unchecked Sendable {
        weak var connection: Connection?
        init(_ connection: Connection) { self.connection = connection }
    }

    nonisolated private static func pump(_ session: Session, into owner: Owner) {
        while true {
            guard let json = session.nextEvent(timeoutMs: 500) else {
                if owner.connection == nil { session.disconnect(); return }
                continue
            }
            guard let event = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
                  let type = event["type"] as? String
            else { continue }
            let payload: Pumped
            switch type {
            case "frame":
                guard let frame = session.takeFrame().flatMap(RenderFrame.init) else { continue }
                payload = .frame(frame)
            case "snapshot":
                guard let view = session.takeView().flatMap({ try? ShellView.parse($0) }) else { continue }
                payload = .view(view)
            default:
                payload = .event(type, EventFields(event))
            }
            let finished = DispatchQueue.main.sync {
                MainActor.assumeIsolated { owner.connection?.apply(payload, from: session) ?? true }
            }
            if finished { return }
        }
    }

    private enum Pumped: Sendable {
        case frame(RenderFrame)
        case view(ShellView)
        case event(String, EventFields)
    }

    /// The fields events carry, copied out of the untyped JSON.
    private struct EventFields: Sendable {
        var strings: [String: String] = [:]
        var ints: [String: Int] = [:]
        var bools: [String: Bool] = [:]

        init(_ object: [String: Any]) {
            for (key, value) in object {
                switch value {
                case let value as String: strings[key] = value
                case let value as NSNumber where CFGetTypeID(value) == CFBooleanGetTypeID(): bools[key] = value.boolValue
                case let value as NSNumber: ints[key] = value.intValue
                default: break
                }
            }
        }

        func string(_ key: String) -> String { strings[key] ?? "" }
    }

    /// Applies one event; true when the session is over.
    private func apply(_ payload: Pumped, from session: Session) -> Bool {
        guard self.session === session else { return true }
        switch payload {
        case .frame(let frame):
            self.frame = frame
        case .view(let view):
            let first = self.view == nil
            self.view = view
            // A resize before the first snapshot had no boot to carry.
            if first, let grid {
                session.resize(cols: grid.cols, rows: grid.rows, cellWidth: grid.cellWidth, cellHeight: grid.cellHeight)
            }
        case .event(let type, let e):
            switch type {
            case "connected":
                state = .connected(serverVersion: e.string("server_version"))
            case "notification":
                emit.yield(.notification(
                    title: e.string("title"), body: e.strings["body"],
                    attention: e.bools["attention"] ?? false, pane: e.strings["pane"]
                ))
            case "clipboard":
                emit.yield(.clipboard(e.string("text")))
            case "bell":
                emit.yield(.bell)
            case "rejected":
                emit.yield(.problem(e.string("reason")))
            case "version_mismatch":
                state = .incompatible(
                    daemonOutdated: e.strings["outdated"] != "app",
                    serverVersion: e.strings["server_version"].flatMap { $0.isEmpty ? nil : $0 },
                    minVersion: e.string("min_version")
                )
            case "auth_rejected":
                state = .authRejected(hop: e.ints["hop"] ?? 0, host: e.string("host"))
                finish(session)
                return true
            case "host_key":
                state = .untrustedHostKey(
                    hop: e.ints["hop"] ?? 0, host: e.string("host"),
                    fingerprint: e.string("fingerprint"), changed: e.bools["changed"] ?? false
                )
                finish(session)
                return true
            case "disconnected", "closed":
                if case .incompatible = state {} else {
                    let reason = e.string("reason")
                    state = .disconnected(reason: reason.isEmpty ? "Disconnected" : reason)
                }
                finish(session)
                return true
            default:
                break
            }
        }
        return false
    }

    private func finish(_ session: Session) {
        session.disconnect()
        if self.session === session { self.session = nil }
    }

    func close() {
        session?.disconnect()
        session = nil
        if state.isOpen { state = .disconnected(reason: "Disconnected") }
    }

    // Input; dropped when not connected.

    func key(pane: String?, keyCode: Int32, meta: Int32, unicode: UInt32, repeat: Bool) -> Bool {
        session?.key(pane: pane, keyCode: keyCode, meta: meta, unicode: unicode, repeat: `repeat`) ?? false
    }

    func chord(pane: String?, codepoint: UInt32, meta: Int32) {
        session?.chord(pane: pane, codepoint: codepoint, meta: meta)
    }

    func text(pane: String?, _ text: String) { session?.text(pane: pane, text: text) }

    func paste(pane: String?, _ text: String) { session?.paste(pane: pane, text: text) }

    func touch(pane: String, kind: Int32, column: Int32, row: Int32, lines: Int32 = 1) {
        session?.touch(pane: pane, kind: kind, column: column, row: row, lines: lines)
    }

    func resize(_ grid: Grid) {
        self.grid = grid
        session?.resize(cols: grid.cols, rows: grid.rows, cellWidth: grid.cellWidth, cellHeight: grid.cellHeight)
    }

    func setTheme(json: String) { session?.setTheme(themeJson: json) }

    func selectionText(start: (column: Int32, row: Int32), end: (column: Int32, row: Int32)) -> String {
        session?.selectionText(startColumn: start.column, startRow: start.row, endColumn: end.column, endRow: end.row) ?? ""
    }

    /// Invokes a Herdr endpoint method; failures surface as `Event.problem`.
    func request(_ method: String, _ params: [String: Any]) {
        guard let session else { return }
        do {
            let json = String(decoding: try JSONSerialization.data(withJSONObject: params), as: UTF8.self)
            _ = try session.request(method: method, paramsJson: json)
        } catch {
            emit.yield(.problem(error.localizedDescription))
        }
    }

    // Herdr's own verbs, as its other clients use them.
    func focusWorkspace(_ id: String) { request("workspace.focus", ["workspace_id": id]) }
    func focusTab(_ id: String) { request("tab.focus", ["tab_id": id]) }
    func focusPane(_ id: String) { request("pane.focus", ["pane_id": id]) }
    /// A blank `label` leaves the name to Herdr, which numbers the tab.
    func newTab(workspace: String, label: String = "") {
        var params: [String: Any] = ["workspace_id": workspace, "focus": true]
        let label = label.trimmingCharacters(in: .whitespaces)
        if !label.isEmpty { params["label"] = label }
        request("tab.create", params)
    }
    func closeTab(_ id: String) { request("tab.close", ["tab_id": id]) }
    func renameTab(_ id: String, label: String) { request("tab.rename", ["tab_id": id, "label": label]) }
    func split(pane: String, direction: String) {
        request("pane.split", ["target_pane_id": pane, "direction": direction, "focus": true])
    }
    func closePane(_ id: String) { request("pane.close", ["pane_id": id]) }
    func toggleZoom(_ id: String) { request("pane.zoom", ["pane_id": id, "mode": "toggle"]) }
    func newWorkspace(source: String?) {
        var params: [String: Any] = ["focus": true]
        if let source { params["source_workspace_id"] = source }
        request("workspace.create", params)
    }
    func renameWorkspace(_ id: String, label: String) {
        request("workspace.rename", ["workspace_id": id, "label": label])
    }
    func closeWorkspace(_ id: String) { request("workspace.close", ["workspace_id": id]) }
}
