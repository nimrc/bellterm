import Foundation

/// Builds the route document `native/src/config.rs` reads: every jump host in
/// order, then the node, each with its pinned host key and credential. Private
/// keys and saved passwords come from the keychain here and go straight to
/// the core; a password typed for this connection wins.
@MainActor
enum Routes {
    /// Nodes in `route` that sign in with a password.
    static func passwordHops(_ route: [Node]) -> [Node] { route.filter { $0.auth == .password } }

    /// Password nodes in `route` with no saved password, which must be asked for.
    static func unsavedPasswordHops(_ route: [Node], saved: (Node) -> Bool) -> [Node] {
        passwordHops(route).filter { !saved($0) }
    }

    static func document(
        route: [Node],
        nodes: NodeStore,
        keychain: Keychain,
        passwords: [String: String],
        grid: Grid,
        scheme: TerminalScheme
    ) throws -> String {
        guard let target = route.last else { throw RouteProblem.tooLong }
        let hops: [[String: Any]] = route.map { node in
            let auth: [String: Any] = switch node.auth {
            case .password:
                ["type": "password", "password": passwords[node.id] ?? keychain.password(nodeId: node.id) ?? ""]
            case .key:
                [
                    "type": "key",
                    "private": keychain.privateKey(node.identityId) ?? "",
                    "certificate": keychain.get(node.identityId)?.certificate ?? NSNull(),
                ]
            }
            return [
                "host": node.address,
                "port": node.port,
                "user": node.user,
                "pinned": nodes.trustedKey(node.hostKeyName) ?? NSNull(),
                "auth": auth,
            ]
        }
        let document: [String: Any] = [
            "session": target.session,
            "hops": hops,
            "keepalive_secs": target.keepAlive,
            "cols": grid.cols,
            "rows": grid.rows,
            "cell_width": grid.cellWidth,
            "cell_height": grid.cellHeight,
            "theme": scheme.document,
        ]
        return String(decoding: try JSONSerialization.data(withJSONObject: document), as: UTF8.self)
    }
}
