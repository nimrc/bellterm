import Foundation
import Testing
@testable import Bellterm

struct ShellViewTests {
    @Test func parsesTheCoreSnapshot() throws {
        let json = """
        {"revision":7,"focused_workspace":"w1","focused_tab":"w1:t2","focused_pane":"w1:p3",
         "update_available":null,
         "workspaces":[{"id":"w1","number":1,"label":"bellterm","branch":"main","ahead":2,"behind":null,
           "worktree":null,"linked_worktree":false,"active_tab":"w1:t2","focused":true,"status":"working"}],
         "tabs":[{"id":"w1:t2","workspace":"w1","number":2,"label":"two","zoomed":false,"focused":true,"status":"idle"},
                 {"id":"w1:t1","workspace":"w1","number":1,"label":"one","zoomed":true,"focused":false,"status":"something-new"}],
         "panes":[{"id":"w1:p3","workspace":"w1","tab":"w1:t2","label":null,"cwd":"/src","focused":true}],
         "agents":[{"pane":"w1:p3","workspace":"w1","tab":"w1:t2","name":null,"agent":"claude","title":"fix",
           "status":"blocked","focused":true}]}
        """
        let view = try ShellView.parse(json)
        #expect(view.revision == 7)
        #expect(view.workspace?.branch == "main" && view.workspace?.ahead == 2 && view.workspace?.behind == nil)
        #expect(view.workspace?.status == .working)
        #expect(view.tabs(of: "w1").map(\.label) == ["one", "two"])
        #expect(view.tabs[1].status == .unknown)
        #expect(view.agent(in: "w1:p3")?.displayName == "claude")
        #expect(view.agent(in: "w1:p3")?.status == .blocked)
    }
}

@MainActor
struct NodeStoreTests {
    private func store() -> NodeStore {
        NodeStore(file: URL.temporaryDirectory.appending(path: "nodes-\(UUID().uuidString).json"))
    }

    @Test func routeFollowsJumpsInOrder() throws {
        let nodes = store()
        let bastion = Node(name: "bastion", address: "b.example", user: "me")
        var inner = Node(name: "inner", address: "10.0.0.2", user: "me")
        inner.jumpId = bastion.id
        var target = Node(name: "box", address: "10.0.0.3", user: "me")
        target.jumpId = inner.id
        nodes.upsertAll([bastion, inner, target])
        #expect(try nodes.route(target).get().map(\.name) == ["bastion", "inner", "box"])
    }

    @Test func routeReportsLoopsAndMissingJumps() {
        let nodes = store()
        var a = Node(name: "a", address: "a", user: "me")
        var b = Node(name: "b", address: "b", user: "me")
        a.jumpId = b.id
        b.jumpId = a.id
        var orphan = Node(name: "c", address: "c", user: "me")
        orphan.jumpId = "gone"
        nodes.upsertAll([a, b, orphan])
        guard case .failure(.loop) = nodes.route(a) else { Issue.record("no loop"); return }
        guard case .failure(.missingJump) = nodes.route(orphan) else { Issue.record("no missing jump"); return }
        #expect(nodes.jumpCandidates(for: a).isEmpty == false)
    }

    @Test func deletingAJumpHostDetachesItsUsers() {
        let nodes = store()
        let bastion = Node(name: "bastion", address: "b", user: "me")
        var box = Node(name: "box", address: "x", user: "me")
        box.jumpId = bastion.id
        nodes.upsertAll([bastion, box])
        nodes.delete(bastion)
        #expect(nodes.nodes.map(\.jumpId) == [nil])
    }

    @Test func persistsNodesAndKnownHosts() {
        let file = URL.temporaryDirectory.appending(path: "nodes-\(UUID().uuidString).json")
        let first = NodeStore(file: file)
        first.upsert(Node(name: "box", address: "x", port: 2222, user: "me"))
        first.trust("x:2222", fingerprint: "SHA256:abc")
        let second = NodeStore(file: file)
        #expect(second.nodes.first?.destination == "me@x:2222")
        #expect(second.trustedKey("x:2222") == "SHA256:abc")
    }

    @Test func readsAndroidNodeJson() throws {
        let json = #"{"id":"n1","label":"old","address":"h","user":"u","auth":"Password","identity":"","jump":null,"keepalive":30}"#
        let node = try JSONDecoder().decode(Node.self, from: Data(json.utf8))
        #expect(node.auth == .password && node.identityId == nil && node.keepAlive == 30 && node.session == "default")
    }

    @Test func validation() {
        #expect(Node.validSession("default") && !Node.validSession("..") && !Node.validSession("a/b"))
        #expect(Node.validAddress("example.com") && !Node.validAddress("me@host"))
        #expect(Node.validUser("me") && !Node.validUser("a b"))
    }
}

@MainActor
struct RoutesTests {
    @Test func documentCarriesEveryHop() throws {
        let nodes = NodeStore(file: URL.temporaryDirectory.appending(path: "nodes-\(UUID().uuidString).json"))
        let keychain = Keychain(file: URL.temporaryDirectory.appending(path: "ids-\(UUID().uuidString).json"))
        var hop = Node(name: "bastion", address: "b", user: "me")
        hop.auth = .password
        var target = Node(name: "box", address: "x", port: 2222, user: "me")
        target.session = "work"
        nodes.trust("x:2222", fingerprint: "SHA256:abc")
        let json = try Routes.document(
            route: [hop, target], nodes: nodes, keychain: keychain, passwords: [hop.id: "typed"],
            grid: Grid(cols: 100, rows: 30, cellWidth: 9, cellHeight: 18),
            scheme: TerminalSchemes.byId("nord", dark: true)
        )
        let doc = try #require(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        let hops = try #require(doc["hops"] as? [[String: Any]])
        #expect(doc["session"] as? String == "work" && doc["cols"] as? Int == 100)
        #expect((hops[0]["auth"] as? [String: Any])?["password"] as? String == "typed")
        #expect(hops[1]["pinned"] as? String == "SHA256:abc" && hops[0]["pinned"] is NSNull)
        #expect((doc["theme"] as? [String: Any])?["background"] as? String == "2e3440")
    }
}
