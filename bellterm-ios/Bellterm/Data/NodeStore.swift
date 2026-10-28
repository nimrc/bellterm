import Foundation
import Observation

/// Why a node's route cannot be followed.
enum RouteProblem: Error, Equatable {
    case missingJump(Node)
    case loop(Node)
    case tooLong
}

/// Saved nodes and the host keys the user trusted, in one JSON file under
/// Application Support. Saved passwords are secrets and live in the
/// `Keychain` instead.
@MainActor
@Observable
final class NodeStore {
    private struct Saved: Codable {
        var nodes: [Node] = []
        /// "host:port" -> "SHA256:…", as the SSH layer names them.
        var knownHosts: [String: String] = [:]
    }

    private(set) var nodes: [Node]
    private(set) var knownHosts: [String: String]
    @ObservationIgnored private let file: URL

    init(file: URL = AppFiles.directory.appending(path: "nodes.json")) {
        self.file = file
        let saved = (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode(Saved.self, from: $0) } ?? Saved()
        nodes = saved.nodes
        knownHosts = saved.knownHosts
    }

    private func save() {
        let saved = Saved(nodes: nodes, knownHosts: knownHosts)
        do {
            try JSONEncoder().encode(saved).write(to: file, options: [.atomic, .completeFileProtection])
        } catch {
            assertionFailure("nodes not saved: \(error)")
        }
    }

    func get(_ id: String?) -> Node? {
        guard let id else { return nil }
        return nodes.first { $0.id == id }
    }

    func upsert(_ node: Node) {
        if let index = nodes.firstIndex(where: { $0.id == node.id }) {
            nodes[index] = node
        } else {
            nodes.append(node)
        }
        save()
    }

    func upsertAll(_ added: [Node]) {
        for node in added {
            if let index = nodes.firstIndex(where: { $0.id == node.id }) {
                nodes[index] = node
            } else {
                nodes.append(node)
            }
        }
        save()
    }

    /// Removes the node; nodes that jumped through it now connect directly.
    func delete(_ node: Node) {
        nodes = nodes.filter { $0.id != node.id }.map {
            var other = $0
            if other.jumpId == node.id { other.jumpId = nil }
            return other
        }
        save()
    }

    func touch(_ node: Node) {
        guard var current = get(node.id) else { return }
        current.lastUsed = .now
        upsert(current)
    }

    /// The node's jump hosts in order, then the node itself.
    func route(_ node: Node) -> Result<[Node], RouteProblem> {
        var chain: [Node] = []
        var here = node
        while true {
            if chain.contains(where: { $0.id == here.id }) { return .failure(.loop(here)) }
            if chain.count >= Node.maxHops { return .failure(.tooLong) }
            chain.insert(here, at: 0)
            guard let jumpId = here.jumpId else { break }
            guard let next = get(jumpId) else { return .failure(.missingJump(here)) }
            here = next
        }
        return .success(chain)
    }

    /// Nodes that may serve as `node`'s jump host without making a loop.
    func jumpCandidates(for node: Node?) -> [Node] {
        nodes.filter { candidate in
            guard candidate.id != node?.id else { return false }
            var here: Node? = candidate
            for _ in 0..<Node.maxHops {
                guard let current = here else { return true }
                if current.id == node?.id { return false }
                here = get(current.jumpId)
            }
            return true
        }
    }

    func trustedKey(_ hostKeyName: String) -> String? { knownHosts[hostKeyName] }

    func trust(_ hostKeyName: String, fingerprint: String) {
        knownHosts[hostKeyName] = fingerprint
        save()
    }

    func forget(_ hostKeyName: String) {
        knownHosts[hostKeyName] = nil
        save()
    }
}

enum AppFiles {
    /// Application Support, created on first use.
    static let directory: URL = {
        let url = URL.applicationSupportDirectory
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()
}
