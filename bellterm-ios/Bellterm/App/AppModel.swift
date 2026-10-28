import Foundation
import Observation
import SwiftUI

/// Process-wide state. Connections outlive screens, as Herdr sessions outlive
/// clients: leaving a session keeps the attachment, and only an explicit
/// disconnect ends it. Disconnecting never stops the daemon.
///
/// iOS suspends apps in the background, so attachments are closed there and
/// reopened on return; Herdr keeps everything running meanwhile.
@MainActor
@Observable
final class AppModel {
    let nodes = NodeStore()
    let keychain = Keychain()
    let settings = Settings()
    let fonts = FontManager()
    private(set) var connections: [String: Connection] = [:]
    /// Nodes attached when the app went to the background, to reattach.
    @ObservationIgnored private var suspended: [Node] = []

    func connection(_ nodeId: String) -> Connection? { connections[nodeId] }

    /// Attach to `node` along its jump hosts, reusing a live connection.
    /// `passwords` maps node IDs on the route to passwords typed for this
    /// connection; `remember` saves them to the keychain, else any saved
    /// password for those hops is forgotten. Hops without one use their saved
    /// password.
    @discardableResult
    func connect(_ node: Node, passwords: [String: String] = [:], dark: Bool, remember: Bool = false) -> Connection {
        if let live = connections[node.id], live.isOpen { return live }
        connections[node.id]?.close()
        let connection = Connection(node: node)
        connections[node.id] = connection
        nodes.touch(node)
        let route: [Node]
        switch nodes.route(node) {
        case .success(let found): route = found
        case .failure(let problem):
            connection.fail(Self.message(problem))
            return connection
        }
        do {
            _ = try keychain.deviceIdentity(name: String(localized: "This device"))
            // A password typed but not remembered also clears any saved one,
            // which is how a rejected saved password is replaced or dropped.
            for (id, password) in passwords {
                if remember { try keychain.savePassword(nodeId: id, password) } else { keychain.forgetPassword(nodeId: id) }
            }
            let scheme = settings.scheme(dark: dark).withCursor(NodeColors.rgb(node.color))
            let document = try Routes.document(
                route: route, nodes: nodes, keychain: keychain, passwords: passwords,
                grid: settings.lastGrid, scheme: scheme
            )
            connection.start(configJson: document)
        } catch {
            connection.fail(String(localized: "A key on this route could not be read. Check Keys & certificates."))
        }
        return connection
    }

    /// Nodes on `node`'s route that sign in with a password and have none saved.
    func passwordsToAsk(_ node: Node) -> [Node] {
        guard case .success(let route) = nodes.route(node) else { return [] }
        return Routes.unsavedPasswordHops(route) { keychain.hasPassword(nodeId: $0.id) }
    }

    /// Retry after the user trusted a host key or changed credentials.
    @discardableResult
    func reconnect(_ node: Node, passwords: [String: String] = [:], dark: Bool, remember: Bool = false) -> Connection {
        disconnect(node.id)
        return connect(nodes.get(node.id) ?? node, passwords: passwords, dark: dark, remember: remember)
    }

    func disconnect(_ nodeId: String) {
        connections[nodeId]?.close()
        connections[nodeId] = nil
    }

    func disconnectAll() {
        connections.values.forEach { $0.close() }
        connections = [:]
    }

    func enterBackground() {
        suspended = connections.values.filter(\.isOpen).map(\.node)
        connections.values.forEach { $0.close() }
    }

    func enterForeground(dark: Bool) {
        let nodes = suspended
        suspended = []
        for node in nodes where connections[node.id] != nil {
            reconnect(node, dark: dark)
        }
    }

    private static func message(_ problem: RouteProblem) -> String {
        switch problem {
        case .missingJump(let node):
            String(localized: "The jump host of \(node.title) was deleted. Edit the node to choose another.")
        case .loop(let node):
            String(localized: "The jump hosts of \(node.title) form a loop.")
        case .tooLong:
            String(localized: "A route may have at most \(Node.maxHops) hops.")
        }
    }
}
