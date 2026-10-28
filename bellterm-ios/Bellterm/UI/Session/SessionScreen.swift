import SwiftUI

/// What a confirmation or text prompt is for.
enum Prompt: Identifiable {
    case newTab(workspace: String)
    case renameTab(id: String, label: String)
    case renameWorkspace(id: String, label: String)
    case closeTab(id: String, label: String)
    case closePane(id: String)
    case closeWorkspace(id: String, label: String)

    var id: String {
        switch self {
        case .newTab(let workspace): "new-tab-\(workspace)"
        case .renameTab(let id, _), .closeTab(let id, _): "tab-\(id)"
        case .renameWorkspace(let id, _), .closeWorkspace(let id, _): "workspace-\(id)"
        case .closePane(let id): "pane-\(id)"
        }
    }
}

/// An attached Herdr session: the sidebar holds workspaces and agents, the
/// tab strip the focused workspace's tabs, and the terminal shows the focused
/// tab's panes as the daemon lays them out. Compact widths (iPhone, or iPhone
/// Duo folded) collapse the sidebar behind a button; regular widths (Duo
/// unfolded) keep it beside the terminal.
struct SessionScreen: View {
    let app: AppModel
    let node: Node
    let onLeave: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var column = NavigationSplitViewColumn.detail
    @State private var latched: Int32 = 0
    @State private var prompt: Prompt?
    @State private var promptText = ""
    @State private var askPasswords: [Node]?
    @State private var banner: String?
    @State private var keys = ExtraKeySender()
    @State private var posture = Posture.flat
    /// A horizontal fold across the terminal area, in its coordinates.
    @State private var fold: CGRect?

    private var connection: Connection? { app.connection(node.id) }
    private var dark: Bool { colorScheme == .dark }

    var body: some View {
        NavigationSplitView(preferredCompactColumn: $column) {
            SessionSidebar(
                node: node,
                view: connection?.view,
                onWorkspace: { connection?.focusWorkspace($0.id); column = .detail },
                onRenameWorkspace: { ask(.renameWorkspace(id: $0.id, label: $0.label)) },
                onCloseWorkspace: { ask(.closeWorkspace(id: $0.id, label: $0.label)) },
                onAgent: { connection?.focusPane($0.pane); column = .detail },
                onNewWorkspace: { connection?.newWorkspace(source: connection?.view?.workspace?.id); column = .detail }
            )
            .navigationTitle(node.title)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Nodes", systemImage: "chevron.backward", action: onLeave)
                }
            }
        } detail: {
            if let connection {
                detail(connection)
                    .id(ObjectIdentifier(connection))
            }
        }
        .alert(promptTitle, isPresented: isPrompting, presenting: prompt) { prompt in
            promptActions(prompt)
        } message: { prompt in
            Text(promptMessage(prompt))
        }
        .sheet(isPresented: Binding(get: { askPasswords != nil }, set: { if !$0 { askPasswords = nil } })) {
            PasswordsSheet(hops: askPasswords ?? []) { secrets, remember in
                askPasswords = nil
                app.reconnect(node, passwords: secrets, dark: dark, remember: remember)
            }
        }
    }

    @ViewBuilder
    private func detail(_ connection: Connection) -> some View {
        let view = connection.view
        let workspace = view?.workspace
        let tab = view?.tab
        let focusedPane = connection.frame?.panes.first(where: \.focused)?.id ?? view?.focusedPane
        let scheme = app.settings.scheme(dark: dark).withCursor(NodeColors.rgb(node.color))

        VStack(spacing: 0) {
            if let workspace {
                TabStrip(
                    tabs: view?.tabs(of: workspace.id) ?? [],
                    selected: tab?.id,
                    accent: NodeColors.of(node.color),
                    onSelect: { connection.focusTab($0.id) },
                    onNew: { ask(.newTab(workspace: workspace.id)) }
                )
            }
            // Laptop posture on iPhone Duo: the terminal fills the upper half
            // and the extra keys sit just below the fold, above the keyboard.
            let tabletop = posture == .tabletop ? fold : nil
            VStack(spacing: 0) {
                ZStack {
                    Color(rgb: scheme.background)
                    TerminalView(
                        connection: connection, settings: app.settings, pane: focusedPane,
                        latchedMeta: $latched, keys: keys
                    )
                    if connection.state == .connecting {
                        ProgressView().controlSize(.large)
                    }
                    StateCard(
                        state: connection.state,
                        rejectedPassword: rejectedHop(connection.state)?.auth == .password,
                        onRetry: retry,
                        onSignIn: { if let hop = rejectedHop(connection.state) { askPasswords = [hop] } },
                        onTrust: { host, fingerprint in
                            app.nodes.trust(host, fingerprint: fingerprint)
                            retry()
                        },
                        onLeave: { app.disconnect(node.id); onLeave() }
                    )
                }
                .frame(height: tabletop?.minY)
                if let tabletop {
                    Color.clear.frame(height: tabletop.height)
                    extraKeys(connection, pane: focusedPane)
                    Spacer(minLength: 0)
                }
            }
            .onHorizontalFoldChange { fold = $0 }
            .ignoresSafeArea(tabletop == nil ? [] : .keyboard, edges: .bottom)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if tabletop == nil { extraKeys(connection, pane: focusedPane) }
            }
        }
        .overlay(alignment: .top) {
            if let banner {
                Text(banner)
                    .font(.callout)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .glassEffect(.regular, in: .capsule)
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .onTapGesture { self.banner = nil }
            }
        }
        .navigationTitle(workspace?.label ?? node.title)
        .navigationSubtitle(subtitle(workspace: workspace, pane: focusedPane, view: view))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                SessionMenu(
                    connection: connection, workspace: workspace, tab: tab, pane: focusedPane,
                    onPrompt: ask,
                    onPaste: { if let text = UIPasteboard.general.string { connection.paste(pane: focusedPane, text) } },
                    onDisconnect: { app.disconnect(node.id); onLeave() }
                )
            }
        }
        .onPostureChange { posture = $0 }
        .animation(.smooth, value: posture)
        .task(id: scheme) { connection.setTheme(json: scheme.json) }
        .task(id: ObjectIdentifier(connection)) { await relay(connection.events, connection: connection) }
        .onAppear { keys.showKeyboard() }
    }

    private func extraKeys(_ connection: Connection, pane: String?) -> some View {
        ExtraKeysRow(
            latched: $latched,
            onKey: { code, repeats in keys.key(code, repeat: repeats) },
            onText: { connection.text(pane: pane, $0) },
            onToggleKeyboard: keys.toggleKeyboard
        )
    }

    private func subtitle(workspace: Workspace?, pane: String?, view: ShellView?) -> String {
        [workspace?.branch, view?.panes.first { $0.id == pane }?.cwd].compactMap { $0 }.joined(separator: " · ")
    }

    private func relay(_ events: AsyncStream<Connection.Event>, connection: Connection) async {
        for await event in events {
            switch event {
            case .clipboard(let text):
                UIPasteboard.general.string = text
            case .bell:
                if app.settings.hapticOnBell { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
            case .problem(let message):
                show(message)
            case .notification(let title, let body, _, _):
                show([title, body].compactMap { $0 }.joined(separator: " — "))
            }
        }
    }

    private func show(_ message: String) {
        withAnimation { banner = message }
        Task {
            try? await Task.sleep(for: .seconds(4))
            withAnimation { if banner == message { banner = nil } }
        }
    }

    /// Reconnect along the node's route, asking for unsaved passwords first.
    private func retry() {
        let needed = app.passwordsToAsk(app.nodes.get(node.id) ?? node)
        if needed.isEmpty { app.reconnect(node, dark: dark) } else { askPasswords = needed }
    }

    /// The node behind a rejected sign-in, so its password can be asked again.
    private func rejectedHop(_ state: Connection.State) -> Node? {
        guard case .authRejected(let hop, _) = state,
              case .success(let route) = app.nodes.route(app.nodes.get(node.id) ?? node),
              route.indices.contains(hop)
        else { return nil }
        return route[hop]
    }

    // MARK: Prompts

    /// Opens a prompt, with a rename's field starting from the current name.
    private func ask(_ next: Prompt) {
        switch next {
        case .renameTab(_, let label), .renameWorkspace(_, let label): promptText = label
        default: promptText = ""
        }
        prompt = next
    }

    private var isPrompting: Binding<Bool> {
        Binding(get: { prompt != nil }, set: { if !$0 { prompt = nil } })
    }

    private var promptTitle: String {
        switch prompt {
        case .newTab: String(localized: "New tab")
        case .renameTab: String(localized: "Rename tab")
        case .renameWorkspace: String(localized: "Rename workspace")
        case .closeTab(_, let label): String(localized: "Close \(label)?")
        case .closePane: String(localized: "Close this pane?")
        case .closeWorkspace(_, let label): String(localized: "Close \(label)?")
        case nil: ""
        }
    }

    private func promptMessage(_ prompt: Prompt) -> String {
        switch prompt {
        case .newTab: String(localized: "Leave blank to let Herdr number it.")
        case .renameTab, .renameWorkspace: ""
        case .closeTab: String(localized: "Herdr ends every program running in its panes.")
        case .closePane: String(localized: "Herdr ends the program running in it.")
        case .closeWorkspace: String(localized: "Herdr ends every program running in its tabs.")
        }
    }

    @ViewBuilder
    private func promptActions(_ prompt: Prompt) -> some View {
        switch prompt {
        case .newTab(let workspace):
            TextField("Tab name", text: $promptText)
            Button("Create") { connection?.newTab(workspace: workspace, label: promptText); promptText = "" }
            Button("Cancel", role: .cancel) { promptText = "" }
        case .renameTab(let id, _):
            TextField("Tab name", text: $promptText)
            Button("Rename") { rename { connection?.renameTab(id, label: $0) } }
            Button("Cancel", role: .cancel) { promptText = "" }
        case .renameWorkspace(let id, _):
            TextField("Workspace name", text: $promptText)
            Button("Rename") { rename { connection?.renameWorkspace(id, label: $0) } }
            Button("Cancel", role: .cancel) { promptText = "" }
        case .closeTab(let id, _):
            Button("Close", role: .destructive) { connection?.closeTab(id) }
        case .closePane(let id):
            Button("Close", role: .destructive) { connection?.closePane(id) }
        case .closeWorkspace(let id, _):
            Button("Close", role: .destructive) { connection?.closeWorkspace(id) }
        }
    }

    private func rename(_ apply: (String) -> Void) {
        let label = promptText.trimmingCharacters(in: .whitespaces)
        if !label.isEmpty { apply(label) }
        promptText = ""
    }
}

/// The focused workspace's tabs as Liquid Glass capsules that morph as the
/// selection moves.
struct TabStrip: View {
    let tabs: [Tab]
    let selected: String?
    let accent: Color
    let onSelect: (Tab) -> Void
    let onNew: () -> Void
    @Namespace private var glass

    var body: some View {
        HStack(spacing: 8) {
            ScrollViewReader { scroller in
                ScrollView(.horizontal, showsIndicators: false) {
                    GlassEffectContainer(spacing: 8) {
                        HStack(spacing: 8) {
                            ForEach(tabs) { tab in
                                Button { onSelect(tab) } label: {
                                    HStack(spacing: 6) {
                                        if tab.status != .unknown {
                                            Circle().fill(tab.status.color).frame(width: 7, height: 7)
                                        }
                                        Text(tab.label).lineLimit(1)
                                        if tab.zoomed { Image(systemName: "arrow.up.left.and.arrow.down.right").imageScale(.small) }
                                    }
                                    .font(.subheadline.weight(tab.id == selected ? .semibold : .regular))
                                    .padding(.horizontal, 12).padding(.vertical, 6)
                                }
                                .buttonStyle(.plain)
                                .glassEffect(
                                    tab.id == selected ? .regular.tint(accent.opacity(0.35)).interactive() : .regular.interactive(),
                                    in: .capsule
                                )
                                .glassEffectID(tab.id, in: glass)
                                .id(tab.id)
                            }
                        }
                        .padding(.horizontal, 12).padding(.vertical, 6)
                    }
                }
                .onChange(of: selected) { _, id in withAnimation { scroller.scrollTo(id, anchor: .center) } }
            }
            Button("New tab", systemImage: "plus", action: onNew)
                .labelStyle(.iconOnly)
                .buttonStyle(.glass)
                .padding(.trailing, 12)
        }
        .animation(.smooth, value: selected)
    }
}

/// Pane, tab, and session actions under the toolbar's ellipsis.
struct SessionMenu: View {
    let connection: Connection
    let workspace: Workspace?
    let tab: Tab?
    let pane: String?
    let onPrompt: (Prompt) -> Void
    let onPaste: () -> Void
    let onDisconnect: () -> Void

    var body: some View {
        Menu("More", systemImage: "ellipsis") {
            Button("Paste", systemImage: "doc.on.clipboard", action: onPaste)
            Section {
                Button("Split right", systemImage: "rectangle.split.2x1") { pane.map { connection.split(pane: $0, direction: "right") } }
                Button("Split down", systemImage: "rectangle.split.1x2") { pane.map { connection.split(pane: $0, direction: "down") } }
                Button(tab?.zoomed == true ? "Unzoom pane" : "Zoom pane", systemImage: "arrow.up.left.and.arrow.down.right") {
                    pane.map(connection.toggleZoom)
                }
                Button("Close pane", systemImage: "xmark.rectangle", role: .destructive) { pane.map { onPrompt(.closePane(id: $0)) } }
            }
            .disabled(pane == nil)
            if let tab {
                Section {
                    Button("Rename tab", systemImage: "pencil") { onPrompt(.renameTab(id: tab.id, label: tab.label)) }
                    Button("Close tab", systemImage: "xmark", role: .destructive) { onPrompt(.closeTab(id: tab.id, label: tab.label)) }
                }
            }
            if let workspace {
                Section {
                    Button("Rename workspace", systemImage: "pencil") {
                        onPrompt(.renameWorkspace(id: workspace.id, label: workspace.label))
                    }
                    Button("Close workspace", systemImage: "xmark.square", role: .destructive) {
                        onPrompt(.closeWorkspace(id: workspace.id, label: workspace.label))
                    }
                }
            }
            Section {
                Button("Disconnect", systemImage: "bolt.horizontal", role: .destructive, action: onDisconnect)
            }
        }
    }
}
