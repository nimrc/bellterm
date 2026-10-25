import Testing
@testable import Bellterm

struct CoreTests {
    @Test func generatedKeyHasEd25519PublicKey() throws {
        let key = try generateKey(comment: "test")
        #expect(try publicKey(privateKey: key).hasPrefix("ssh-ed25519 "))
    }

    @Test func importRejectsGarbage() {
        #expect(throws: Error.self) { try importKey(text: "not a key", passphrase: nil) }
    }

    @Test func sshConfigParses() throws {
        let json = try parseSshConfig(text: "Host box\n  HostName example.com\n  User me\n")
        #expect(json.contains("example.com"))
    }
}
