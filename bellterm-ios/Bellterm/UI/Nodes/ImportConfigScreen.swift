import SwiftUI
import UniformTypeIdentifiers

/// One `Host` alias from an OpenSSH configuration, as `ssh_config.rs` reads it.
struct ConfigEntry: Decodable, Hashable {
    var alias: String
    var hostName: String?
    var user: String?
    var port: Int?
    var proxyJump: [String]
    var identityFile: String?
    var serverAliveInterval: Int?
}

/// Turns parsed entries into nodes, with `ProxyJump` hops as jump-host nodes.
enum ConfigImport {
    struct Parsed: Decodable {
        var entries: [ConfigEntry]
        var skipped: [String]
    }

    static func parse(_ text: String) throws -> Parsed {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(Parsed.self, from: Data(try parseSshConfig(text: text).utf8))
    }

    /// Nodes for `selected`, plus a node for every jump hop that is not itself
    /// an imported alias or an existing node. `fallbackUser` fills entries
    /// without a `User`.
    static func nodes(selected: [ConfigEntry], all: [ConfigEntry], existing: [Node], fallbackUser: String, group: String) -> [Node] {
        var made: [String: Node] = [:]
        func node(for entry: ConfigEntry) -> Node {
            if let node = made[entry.alias] { return node }
            var node = Node(name: entry.alias, address: entry.hostName ?? entry.alias, port: entry.port ?? 22, user: entry.user ?? fallbackUser)
            node.group = group
            // Stable across launches, unlike `hashValue`.
            node.color = entry.alias.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7fff_ffff } % NodeColors.all.count
            node.keepAlive = entry.serverAliveInterval ?? 15
            made[entry.alias] = node
            return node
        }
        for entry in selected {
            var previous: Node?
            for spec in entry.proxyJump {
                // An alias or a saved node keeps its own route; a bare
                // `user@host:port` hop continues the chain so far.
                if let alias = all.first(where: { $0.alias == spec }) {
                    previous = node(for: alias)
                } else if let known = existing.first(where: { $0.name == spec }) {
                    previous = known
                } else if let hop = made["jump:\(spec)"] {
                    previous = hop
                } else {
                    let (user, host, port) = splitSpec(spec)
                    var hop = Node(name: host, address: host, port: port ?? 22, user: user ?? fallbackUser)
                    hop.group = group
                    hop.jumpId = previous?.id
                    made["jump:\(spec)"] = hop
                    previous = hop
                }
            }
            var target = node(for: entry)
            target.jumpId = previous?.id
            made[entry.alias] = target
        }
        return made.values.filter { new in !existing.contains { $0.id == new.id } }
    }

    /// `[user@]host[:port]`, with IPv6 in brackets.
    static func splitSpec(_ spec: String) -> (user: String?, host: String, port: Int?) {
        let at = spec.lastIndex(of: "@")
        let user = at.map { String(spec[..<$0]) }
        let rest = at.map { String(spec[spec.index(after: $0)...]) } ?? spec
        if rest.hasPrefix("["), let end = rest.firstIndex(of: "]") {
            let host = String(rest[rest.index(after: rest.startIndex)..<end])
            let tail = rest[rest.index(after: end)...]
            return (user, host, tail.hasPrefix(":") ? Int(tail.dropFirst()) : nil)
        }
        if let colon = rest.lastIndex(of: ":"), rest.firstIndex(of: ":") == colon, colon != rest.startIndex {
            return (user, String(rest[..<colon]), Int(rest[rest.index(after: colon)...]))
        }
        return (user, rest, nil)
    }
}

/// Paste or pick `~/.ssh/config`; every `Host` alias becomes a node.
struct ImportConfigScreen: View {
    let app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var parsed: ConfigImport.Parsed?
    @State private var selected: Set<String> = []
    @State private var fallbackUser = ""
    @State private var group = ""
    @State private var picking = false
    @State private var failure: String?

    var body: some View {
        Form {
            if let parsed {
                Section {
                    ForEach(parsed.entries, id: \.alias) { entry in
                        Toggle(isOn: Binding(
                            get: { selected.contains(entry.alias) },
                            set: { if $0 { selected.insert(entry.alias) } else { selected.remove(entry.alias) } }
                        )) {
                            VStack(alignment: .leading) {
                                Text(entry.alias)
                                Text(describe(entry)).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Hosts")
                } footer: {
                    if parsed.entries.contains(where: { $0.identityFile != nil }) {
                        Text("Keys named by IdentityFile are not imported; add them under Keys & certificates.")
                    }
                }
                Section {
                    TextField("User for hosts without one", text: $fallbackUser)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                    TextField("Group", text: $group)
                }
                if !parsed.skipped.isEmpty {
                    Section("Not applied") {
                        ForEach(parsed.skipped, id: \.self) { Text($0).font(.caption.monospaced()) }
                    }
                }
            } else {
                Section {
                    TextEditor(text: $text)
                        .font(.caption.monospaced())
                        .frame(minHeight: 200)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                } header: {
                    Text("Paste ~/.ssh/config")
                } footer: {
                    Text("HostName, User, Port, ServerAliveInterval, and ProxyJump are applied. Match and Include are reported, not applied.")
                }
                Button("Choose file…", systemImage: "folder") { picking = true }
                if let failure { Text(failure).foregroundStyle(.red) }
            }
        }
        .navigationTitle("Import SSH config")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if parsed == nil {
                    Button("Read", action: read).disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                } else {
                    Button("Import", action: save)
                        .disabled(selected.isEmpty || (needsUser && !Node.validUser(fallbackUser)))
                }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.plainText, .data]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let contents = try? String(contentsOf: url, encoding: .utf8) {
                text = contents
                read()
            }
        }
    }

    private var needsUser: Bool {
        parsed?.entries.contains { selected.contains($0.alias) && $0.user == nil } ?? false
    }

    private func describe(_ entry: ConfigEntry) -> String {
        var parts = ["\(entry.user.map { "\($0)@" } ?? "")\(entry.hostName ?? entry.alias)\(entry.port.map { ":\($0)" } ?? "")"]
        if !entry.proxyJump.isEmpty { parts.append("via \(entry.proxyJump.joined(separator: ", "))") }
        return parts.joined(separator: " · ")
    }

    private func read() {
        do {
            let result = try ConfigImport.parse(text)
            parsed = result
            selected = Set(result.entries.map(\.alias)).subtracting(app.nodes.nodes.map(\.name))
            failure = nil
        } catch {
            failure = error.localizedDescription
        }
    }

    private func save() {
        guard let parsed else { return }
        let chosen = parsed.entries.filter { selected.contains($0.alias) }
        app.nodes.upsertAll(ConfigImport.nodes(
            selected: chosen, all: parsed.entries, existing: app.nodes.nodes,
            fallbackUser: fallbackUser.trimmingCharacters(in: .whitespaces), group: group.trimmingCharacters(in: .whitespaces)
        ))
        dismiss()
    }
}
