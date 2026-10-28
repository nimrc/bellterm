import Foundation

enum AuthMethod: String, Codable, Sendable, CaseIterable {
    case key = "Key"
    case password = "Password"
}

/// A saved SSH server: a machine whose Herdr daemon Bellterm attaches to, or a
/// jump host on the way to one. A node reached through another names it in
/// `jumpId`; chains are resolved by `NodeStore.route`.
struct Node: Codable, Sendable, Identifiable, Hashable {
    var id = UUID().uuidString
    var name: String
    var address: String
    var port = 22
    var user: String
    var session = "default"
    var auth = AuthMethod.key
    /// The keychain identity to sign in with; nil means the device key.
    var identityId: String?
    var jumpId: String?
    var group = ""
    /// Index into `NodeColors.all`.
    var color = 0
    /// SSH keepalive in seconds; 0 turns it off.
    var keepAlive = 15
    var lastUsed: Date = .distantPast

    var title: String { name.trimmingCharacters(in: .whitespaces).isEmpty ? address : name }
    var destination: String { port == 22 ? "\(user)@\(address)" : "\(user)@\(address):\(port)" }

    /// How the known-hosts list names this server, as the SSH layer does.
    var hostKeyName: String { "\(address):\(port)" }

    enum CodingKeys: String, CodingKey {
        case id, name, address, port, user, session, auth, group, color
        case identityId = "identity"
        case jumpId = "jump"
        case keepAlive = "keepalive"
        case lastUsed = "last_used"
    }

    init(name: String, address: String, port: Int = 22, user: String) {
        self.name = name
        self.address = address
        self.port = port
        self.user = user
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        address = try c.decode(String.self, forKey: .address)
        port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 22
        user = try c.decode(String.self, forKey: .user)
        session = (try c.decodeIfPresent(String.self, forKey: .session)).flatMap { $0.isEmpty ? nil : $0 } ?? "default"
        auth = (try? c.decodeIfPresent(AuthMethod.self, forKey: .auth)) ?? .key
        identityId = (try c.decodeIfPresent(String.self, forKey: .identityId)).flatMap { $0.isEmpty ? nil : $0 }
        jumpId = (try c.decodeIfPresent(String.self, forKey: .jumpId)).flatMap { $0.isEmpty ? nil : $0 }
        group = try c.decodeIfPresent(String.self, forKey: .group) ?? ""
        color = try c.decodeIfPresent(Int.self, forKey: .color) ?? 0
        keepAlive = try c.decodeIfPresent(Int.self, forKey: .keepAlive) ?? 15
        lastUsed = try c.decodeIfPresent(Date.self, forKey: .lastUsed) ?? .distantPast
    }

    /// Herdr session names are plain path components.
    static func validSession(_ name: String) -> Bool {
        !name.isEmpty && name.count <= 64 && name != "." && name != ".."
            && name.allSatisfy { $0.isLetter || $0.isNumber || "-_.".contains($0) }
    }

    static func validAddress(_ address: String) -> Bool {
        !address.trimmingCharacters(in: .whitespaces).isEmpty
            && !address.contains { $0.isWhitespace || $0 == "@" || $0 == "/" }
    }

    static func validUser(_ user: String) -> Bool {
        !user.trimmingCharacters(in: .whitespaces).isEmpty
            && !user.contains { $0.isWhitespace || $0 == "@" || $0 == ":" }
    }

    /// Longest jump chain followed before giving up.
    static let maxHops = 8
}
