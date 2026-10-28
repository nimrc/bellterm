import SwiftUI

/// Saved nodes, grouped and searchable, each with its colour label. Tapping
/// one attaches to its Herdr session.
struct NodesScreen: View {
    let app: AppModel
    @Binding var session: Node?

    @Environment(\.colorScheme) private var colorScheme
    @State private var search = ""
    @State private var editing: NodeEditor.Target?
    @State private var deleting: Node?
    @State private var askPasswords: (node: Node, hops: [Node])?
    @State private var route: Route?

    enum Route: Hashable {
        case keys, knownHosts, settings, importConfig
    }

    private var groups: [(name: String, nodes: [Node])] {
        let query = search.trimmingCharacters(in: .whitespaces).lowercased()
        let matching = app.nodes.nodes.filter {
            query.isEmpty || [$0.title, $0.address, $0.user, $0.group].contains { $0.lowercased().contains(query) }
        }
        return Dictionary(grouping: matching, by: \.group)
            .map { (name: $0.key, nodes: $0.value.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }) }
            .sorted { ($0.name.isEmpty ? 1 : 0, $0.name) < ($1.name.isEmpty ? 1 : 0, $1.name) }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(groups, id: \.name) { group in
                    Section(group.name.isEmpty ? (groups.count > 1 ? String(localized: "Ungrouped") : "") : group.name) {
                        ForEach(group.nodes) { node in
                            row(node)
                        }
                    }
                }
            }
            .overlay {
                if app.nodes.nodes.isEmpty {
                    ContentUnavailableView {
                        Label("No nodes yet", systemImage: "server.rack")
                    } description: {
                        Text("Add a machine that runs Herdr, or import your ~/.ssh/config.")
                    } actions: {
                        Button("Add node") { editing = .new }.buttonStyle(.glassProminent)
                        Button("Import SSH config") { route = .importConfig }.buttonStyle(.glass)
                    }
                } else if groups.isEmpty {
                    ContentUnavailableView.search(text: search)
                }
            }
            .searchable(text: $search, prompt: "Search nodes")
            .navigationTitle("Bellterm")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    // The ellipsis belongs to the system's overflow menu.
                    Menu("Manage", systemImage: "slider.horizontal.3") {
                        Button("Import SSH config", systemImage: "square.and.arrow.down") { route = .importConfig }
                        Button("Keys & certificates", systemImage: "key") { route = .keys }
                        Button("Known hosts", systemImage: "checkmark.shield") { route = .knownHosts }
                        Button("Settings", systemImage: "gear") { route = .settings }
                    }
                }
                ToolbarSpacer(.fixed, placement: .topBarTrailing)
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Add node", systemImage: "plus") { editing = .new }
                }
            }
            .navigationDestination(item: $route) { route in
                switch route {
                case .keys: KeysScreen(app: app)
                case .knownHosts: KnownHostsScreen(nodes: app.nodes)
                case .settings: SettingsScreen(settings: app.settings, fonts: app.fonts)
                case .importConfig: ImportConfigScreen(app: app)
                }
            }
            .sheet(item: $editing) { target in
                NodeEditor(app: app, target: target)
            }
            .confirmationDialog(
                deleting.map { String(localized: "Delete \($0.title)?") } ?? "",
                isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                titleVisibility: .visible,
                presenting: deleting
            ) { node in
                Button("Delete", role: .destructive) {
                    app.disconnect(node.id)
                    app.keychain.forgetPassword(nodeId: node.id)
                    app.nodes.delete(node)
                }
            } message: { _ in
                Text("Only the saved node is removed. Herdr and everything running on the host are untouched; nodes that jumped through it will connect directly.")
            }
            .sheet(isPresented: Binding(get: { askPasswords != nil }, set: { if !$0 { askPasswords = nil } })) {
                if let ask = askPasswords {
                    PasswordsSheet(hops: ask.hops) { secrets, remember in
                        askPasswords = nil
                        app.connect(ask.node, passwords: secrets, dark: colorScheme == .dark, remember: remember)
                        session = ask.node
                    }
                }
            }
        }
    }

    private func row(_ node: Node) -> some View {
        let connection = app.connection(node.id)
        let attached = connection?.isOpen == true
        let waiting = connection?.view?.agents.filter { $0.status == .blocked } ?? []
        return Button { open(node) } label: {
            HStack(spacing: 14) {
                NodeAvatar(title: node.title, color: node.color)
                VStack(alignment: .leading, spacing: 2) {
                    Text(node.title).font(.body.weight(.medium)).foregroundStyle(.primary)
                    Text(subtitle(node)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    if let agent = waiting.first {
                        Text("\(agent.displayName) needs input").font(.caption).foregroundStyle(AgentStatus.blocked.color)
                    } else if attached, let view = connection?.view {
                        Text("Attached · workspaces: \(view.workspaces.count) · agents: \(view.agents.count)")
                            .font(.caption).foregroundStyle(.tint)
                    }
                }
                Spacer()
                if attached { Circle().fill(.green).frame(width: 8, height: 8) }
            }
        }
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive) { deleting = node }
            Button("Edit", systemImage: "pencil") { editing = .edit(node) }.tint(.orange)
        }
        .contextMenu {
            Button("Edit", systemImage: "pencil") { editing = .edit(node) }
            Button("Duplicate", systemImage: "plus.square.on.square") { editing = .duplicate(node) }
            if attached {
                Button("Disconnect", systemImage: "bolt.horizontal") { app.disconnect(node.id) }
            }
            Button("Delete", systemImage: "trash", role: .destructive) { deleting = node }
        }
    }

    private func subtitle(_ node: Node) -> String {
        var parts = [node.destination]
        if node.session != "default" { parts.append(node.session) }
        if let jump = app.nodes.get(node.jumpId) { parts.append(String(localized: "via \(jump.title)")) }
        return parts.joined(separator: " · ")
    }

    private func open(_ node: Node) {
        if app.connection(node.id)?.isOpen != true {
            let hops = app.passwordsToAsk(node)
            guard hops.isEmpty else {
                askPasswords = (node, hops)
                return
            }
            app.connect(node, dark: colorScheme == .dark)
        }
        session = node
    }
}
