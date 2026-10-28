import Foundation

/// Herdr's agent status, as the daemon reports it for every client alike.
enum AgentStatus: String, Sendable, Decodable {
    case idle, working, blocked, done, unknown

    init(from decoder: Decoder) throws {
        self = Self(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .unknown
    }
}

struct Workspace: Sendable, Decodable, Identifiable, Equatable {
    var id: String
    var number: Int
    var label: String
    var branch: String?
    var ahead: Int?
    var behind: Int?
    var worktree: String?
    var linkedWorktree: Bool
    var activeTab: String
    var focused: Bool
    var status: AgentStatus
}

struct Tab: Sendable, Decodable, Identifiable, Equatable {
    var id: String
    var workspace: String
    var number: Int
    var label: String
    var zoomed: Bool
    var focused: Bool
    var status: AgentStatus
}

struct Pane: Sendable, Decodable, Identifiable, Equatable {
    var id: String
    var workspace: String
    var tab: String
    var label: String?
    var cwd: String?
    var focused: Bool
}

struct Agent: Sendable, Decodable, Identifiable, Equatable {
    var pane: String
    var workspace: String
    var tab: String
    var name: String?
    var agent: String?
    var title: String?
    var status: AgentStatus
    var focused: Bool

    var id: String { pane }
    var displayName: String { name ?? agent ?? "agent" }
}

/// The daemon's workspaces, tabs, panes, and agents (see `view.rs`).
struct ShellView: Sendable, Decodable, Equatable {
    var revision: Int
    var focusedWorkspace: String?
    var focusedTab: String?
    var focusedPane: String?
    var updateAvailable: String?
    var workspaces: [Workspace]
    var tabs: [Tab]
    var panes: [Pane]
    var agents: [Agent]

    var workspace: Workspace? { workspaces.first { $0.id == focusedWorkspace } }
    var tab: Tab? { tabs.first { $0.id == focusedTab } }

    func tabs(of workspace: String) -> [Tab] {
        tabs.filter { $0.workspace == workspace }.sorted { $0.number < $1.number }
    }

    func panes(of tab: String) -> [Pane] { panes.filter { $0.tab == tab } }

    func agent(in pane: String) -> Agent? { agents.first { $0.pane == pane } }

    static func parse(_ json: String) throws -> ShellView {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(ShellView.self, from: Data(json.utf8))
    }
}
