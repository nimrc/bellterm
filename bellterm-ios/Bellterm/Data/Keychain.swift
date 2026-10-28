import Foundation
import Observation
import Security
import UIKit

struct Identity: Codable, Sendable, Identifiable, Hashable {
    var id: String
    var name: String
    var algorithm: String
    var fingerprint: String
    var publicKey: String
    var certificate: String?
    var created: Date = .now

    var isDevice: Bool { id == Keychain.device }

    enum CodingKeys: String, CodingKey {
        case id, name, algorithm, fingerprint, certificate, created
        case publicKey = "public"
    }
}

/// A certificate's details, as the Rust core reads them.
struct CertificateInfo: Decodable, Sendable {
    var keyId: String
    var principals: [String]
    var caFingerprint: String
    var validAfter: Int64
    var validBefore: Int64
    var matches: Bool
    var current: Bool
}

/// The key details `import_key` reports.
private struct KeyInfo: Decodable {
    var algorithm: String
    var fingerprint: String
    var `public`: String
}

private struct Imported: Decodable {
    var `private`: String
    var info: KeyInfo
}

/// SSH identities and saved node passwords. Private keys and passwords are
/// iOS Keychain items readable only on this device while it is unlocked;
/// metadata and certificates (public data) live in a JSON file.
@MainActor
@Observable
final class Keychain {
    nonisolated static let device = "device"
    private static let keyService = "app.bellterm.key"
    private static let passwordService = "app.bellterm.password"

    private(set) var identities: [Identity]
    @ObservationIgnored private let file: URL

    init(file: URL = AppFiles.directory.appending(path: "identities.json")) {
        self.file = file
        identities = (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode([Identity].self, from: $0) } ?? []
    }

    private func save(_ identities: [Identity]) {
        self.identities = identities
        do {
            try JSONEncoder().encode(identities).write(to: file, options: [.atomic, .completeFileProtection])
        } catch {
            assertionFailure("identities not saved: \(error)")
        }
    }

    func get(_ id: String?) -> Identity? { identities.first { $0.id == (id ?? Self.device) } }

    /// The device key, generated on first use.
    func deviceIdentity(name: String) throws -> Identity {
        if let existing = get(Self.device) { return existing }
        return try store(id: Self.device, name: name, privateKey: try generateKey(comment: Self.comment), info: nil)
    }

    func generate(name: String) throws -> Identity {
        try store(id: UUID().uuidString, name: name, privateKey: try generateKey(comment: Self.comment), info: nil)
    }

    /// Import key text, decrypting it with `passphrase` when protected. Throws
    /// the core's reason when it cannot be read.
    func importKey(name: String, text: String, passphrase: String?) throws -> Identity {
        let imported = try Self.decodeImport(try Bellterm.importKey(text: text, passphrase: passphrase))
        return try store(id: UUID().uuidString, name: name, privateKey: imported.private, info: imported.info)
    }

    private static func decodeImport(_ json: String) throws -> Imported {
        try JSONDecoder().decode(Imported.self, from: Data(json.utf8))
    }

    private func store(id: String, name: String, privateKey: String, info: KeyInfo?) throws -> Identity {
        let details = try info ?? Self.decodeImport(try Bellterm.importKey(text: privateKey, passphrase: nil)).info
        try Self.write(service: Self.keyService, account: id, secret: privateKey)
        let identity = Identity(
            id: id, name: name, algorithm: details.algorithm,
            fingerprint: details.fingerprint, publicKey: details.public
        )
        save(identities.filter { $0.id != id } + [identity])
        return identity
    }

    func privateKey(_ id: String?) -> String? { Self.read(service: Self.keyService, account: id ?? Self.device) }

    /// Validates `certificate` against the identity's key before attaching it.
    func certificateInfo(id: String, certificate: String) throws -> CertificateInfo {
        let json = try Bellterm.certificateInfo(certificate: certificate, privateKey: privateKey(id) ?? "")
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(CertificateInfo.self, from: Data(json.utf8))
    }

    func setCertificate(id: String, _ certificate: String?) {
        save(identities.map {
            var identity = $0
            if identity.id == id { identity.certificate = certificate?.trimmingCharacters(in: .whitespacesAndNewlines) }
            return identity
        })
    }

    func rename(id: String, to name: String) {
        save(identities.map {
            var identity = $0
            if identity.id == id { identity.name = name }
            return identity
        })
    }

    func delete(id: String) {
        Self.remove(service: Self.keyService, account: id)
        save(identities.filter { $0.id != id })
    }

    // Passwords, one item per node ID.

    func hasPassword(nodeId: String) -> Bool { password(nodeId: nodeId) != nil }

    func password(nodeId: String) -> String? { Self.read(service: Self.passwordService, account: nodeId) }

    func savePassword(nodeId: String, _ password: String) throws {
        try Self.write(service: Self.passwordService, account: nodeId, secret: password)
    }

    func forgetPassword(nodeId: String) { Self.remove(service: Self.passwordService, account: nodeId) }

    private static var comment: String {
        let model = UIDevice.current.model.filter { $0.isLetter || $0.isNumber || $0 == "-" }
        return "bellterm@\(model.isEmpty ? "ios" : model)"
    }

    // Generic password items.

    struct KeychainError: LocalizedError {
        var status: OSStatus
        var errorDescription: String? { SecCopyErrorMessageString(status, nil) as String? ?? "Keychain error \(status)" }
    }

    private static func query(service: String, account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private static func write(service: String, account: String, secret: String) throws {
        remove(service: service, account: account)
        var item = query(service: service, account: account)
        item[kSecValueData as String] = Data(secret.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    private static func read(service: String, account: String) -> String? {
        var item = query(service: service, account: account)
        item[kSecReturnData as String] = true
        item[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(item as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private static func remove(service: String, account: String) {
        SecItemDelete(query(service: service, account: account) as CFDictionary)
    }
}
