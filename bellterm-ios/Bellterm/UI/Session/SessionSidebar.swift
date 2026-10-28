import SwiftUI

/// Herdr's sidebar: every workspace with its branch and the daemon's status
/// dot, and every agent. Tabs are switched from the session's tab strip.
struct SessionSidebar: View {
    let node: Node
    let view: ShellView?
    let onWorkspace: (Workspace) -> Void
    let onRenameWorkspace: (Workspace) -> Void
    let onCloseWorkspace: (Workspace) -> Void
    let onAgent: (Agent) -> Void
    let onNewWorkspace: () -> Void

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    NodeAvatar(title: node.title, color: node.color, size: 44)
                    VStack(alignment: .leading) {
                        Text(node.title).font(.title3.weight(.semibold))
                        Text(node.destination + (node.session == "default" ? "" : " · \(node.session)"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Section("Workspaces") {
                ForEach(view?.workspaces ?? []) { workspace in
                    Button { onWorkspace(workspace) } label: {
                        HStack {
                            StatusDot(status: workspace.status)
                            VStack(alignment: .leading) {
                                Text(workspace.label).lineLimit(1)
                                if let detail = Self.detail(workspace) {
                                    Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                            Spacer()
                            Text("\(workspace.number)").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                    .listRowBackground(workspace.focused ? Color.accentColor.opacity(0.15) : nil)
                    .contextMenu {
                        Button("Rename workspace", systemImage: "pencil") { onRenameWorkspace(workspace) }
                        Button("Close workspace", systemImage: "xmark.square", role: .destructive) { onCloseWorkspace(workspace) }
                    }
                }
                Button(action: onNewWorkspace) { Label("New workspace", systemImage: "plus") }
            }
            if let agents = view?.agents, !agents.isEmpty {
                Section("Agents") {
                    ForEach(agents) { agent in
                        Button { onAgent(agent) } label: {
                            HStack {
                                StatusDot(status: agent.status)
                                VStack(alignment: .leading) {
                                    Text(agent.displayName).lineLimit(1)
                                    let workspace = view?.workspaces.first { $0.id == agent.workspace }?.label
                                    Text([agent.status.label, workspace, agent.title].compactMap { $0 }.joined(separator: " · "))
                                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                        }
                        .listRowBackground(agent.focused ? Color.accentColor.opacity(0.15) : nil)
                    }
                }
            }
        }
        .tint(.primary)
    }

    private static func detail(_ workspace: Workspace) -> String? {
        var parts: [String] = []
        if let branch = workspace.branch { parts.append(branch) }
        if let ahead = workspace.ahead, ahead > 0 { parts.append("↑\(ahead)") }
        if let behind = workspace.behind, behind > 0 { parts.append("↓\(behind)") }
        if workspace.linkedWorktree, let worktree = workspace.worktree { parts.append("⎇ \(worktree)") }
        return parts.isEmpty ? nil : parts.joined(separator: "  ")
    }
}

extension AgentStatus {
    var label: String? {
        switch self {
        case .working: String(localized: "Working")
        case .blocked: String(localized: "Needs input")
        case .done: String(localized: "Done")
        case .idle: String(localized: "Idle")
        case .unknown: nil
        }
    }
}

struct StatusDot: View {
    let status: AgentStatus
    var size: CGFloat = 10

    var body: some View {
        Circle()
            .fill(status.color)
            .frame(width: size, height: size)
            .frame(width: 24)
            .accessibilityLabel(status.label ?? "")
    }
}

struct NodeAvatar: View {
    let title: String
    let color: Int
    var size: CGFloat = 40

    var body: some View {
        Text(title.prefix(1).uppercased())
            .font(.system(size: size * 0.42, weight: .semibold, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(NodeColors.of(color), in: .rect(cornerRadius: size * 0.3))
    }
}
