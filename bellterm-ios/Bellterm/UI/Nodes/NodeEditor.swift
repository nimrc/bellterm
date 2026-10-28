import SwiftUI

/// Adds or edits a node: where it is, how to sign in, the jump host to reach
/// it through, and how it is labelled.
struct NodeEditor: View {
    enum Target: Identifiable {
        case new
        case edit(Node)
        case duplicate(Node)

        var id: String {
            switch self {
            case .new: "new"
            case .edit(let node): "edit-\(node.id)"
            case .duplicate(let node): "duplicate-\(node.id)"
            }
        }
    }

    let app: AppModel
    let target: Target

    @Environment(\.dismiss) private var dismiss
    @State private var node: Node
    @State private var port: String
    @State private var keepAlive: String
    @State private var password = ""
    @State private var forgetPassword = false

    init(app: AppModel, target: Target) {
        self.app = app
        self.target = target
        let node: Node = switch target {
        case .new: Node(name: "", address: "", user: "")
        case .edit(let existing): existing
        case .duplicate(let existing): {
            var copy = existing
            copy.id = UUID().uuidString
            copy.name = existing.name.isEmpty ? "" : String(localized: "\(existing.name) copy")
            copy.lastUsed = .distantPast
            return copy
        }()
        }
        _node = State(initialValue: node)
        _port = State(initialValue: String(node.port))
        _keepAlive = State(initialValue: String(node.keepAlive))
    }

    private var isNew: Bool {
        if case .edit = target { false } else { true }
    }

    private var hasSavedPassword: Bool { !isNew && app.keychain.hasPassword(nodeId: node.id) && !forgetPassword }

    private var valid: Bool {
        Node.validAddress(node.address) && Node.validUser(node.user) && Node.validSession(node.session)
            && (Int(port).map { (1...65535).contains($0) } ?? false) && Int(keepAlive).map { $0 >= 0 } ?? false
    }

    private var groups: [String] {
        Array(Set(app.nodes.nodes.map(\.group).filter { !$0.isEmpty })).sorted()
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
                    TextField("Name", text: $node.name)
                    TextField("Host or address", text: $node.address)
                        .textContentType(.URL).keyboardType(.URL).autocorrectionDisabled().textInputAutocapitalization(.never)
                    TextField("Port", text: $port).keyboardType(.numberPad)
                    TextField("User", text: $node.user)
                        .textContentType(.username).autocorrectionDisabled().textInputAutocapitalization(.never)
                    VStack(alignment: .leading) {
                        TextField("Herdr session", text: $node.session)
                            .autocorrectionDisabled().textInputAutocapitalization(.never)
                        Text("Usually “default”; named sessions are separate daemons").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section {
                    Picker("Sign in with", selection: $node.auth) {
                        Text("Key").tag(AuthMethod.key)
                        Text("Password").tag(AuthMethod.password)
                    }
                    .pickerStyle(.segmented)
                    switch node.auth {
                    case .key:
                        Picker("Key", selection: $node.identityId) {
                            Text("This device").tag(String?.none)
                            ForEach(app.keychain.identities.filter { !$0.isDevice }) { identity in
                                Text(identity.name).tag(Optional(identity.id))
                            }
                        }
                    case .password:
                        SecureField(hasSavedPassword ? String(localized: "Saved") : String(localized: "Password"), text: $password)
                            .textContentType(.password)
                        if hasSavedPassword {
                            Button("Forget saved password", role: .destructive) { forgetPassword = true }
                        }
                    }
                } header: {
                    Text("Sign in")
                } footer: {
                    switch node.auth {
                    case .key:
                        Text("The host must trust this key: add its public key to ~/.ssh/authorized_keys, or attach a certificate signed by a CA the host trusts.")
                    case .password:
                        Text(hasSavedPassword
                            ? "A password is saved. Type a new one to replace it."
                            : "Saved in this device's keychain. Leave blank to be asked each time you connect.")
                    }
                }
                Section {
                    Picker("Jump host", selection: $node.jumpId) {
                        Text("None (connect directly)").tag(String?.none)
                        ForEach(app.nodes.jumpCandidates(for: node)) { candidate in
                            Text(candidate.title).tag(Optional(candidate.id))
                        }
                    }
                } header: {
                    Text("Route")
                } footer: {
                    Text("Bellterm connects to the jump host first and reaches this node through it, as ssh -J does. Jump hosts can have jump hosts of their own.")
                }
                Section {
                    TextField("Group", text: $node.group)
                    if !groups.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(groups, id: \.self) { group in
                                    Button(group) { node.group = group }.buttonStyle(.glass).controlSize(.small)
                                }
                            }
                        }
                    }
                    HStack {
                        ForEach(NodeColors.all.indices, id: \.self) { index in
                            Button { node.color = index } label: {
                                Circle()
                                    .fill(NodeColors.of(index))
                                    .frame(width: 28, height: 28)
                                    .overlay { if node.color == index { Image(systemName: "checkmark").foregroundStyle(.white) } }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Colour \(index + 1)"))
                        }
                    }
                    VStack(alignment: .leading) {
                        TextField("Keepalive (seconds)", text: $keepAlive).keyboardType(.numberPad)
                        Text("How often to ping over SSH so idle mobile networks keep the connection; 0 turns it off")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } header: {
                    Text("More")
                }
            }
            .navigationTitle(isNew ? "Add node" : "Edit node")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", role: .cancel) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", role: .confirm, action: save).disabled(!valid)
                }
            }
        }
    }

    private func save() {
        node.port = Int(port) ?? 22
        node.keepAlive = Int(keepAlive) ?? 15
        node.address = node.address.trimmingCharacters(in: .whitespaces)
        node.user = node.user.trimmingCharacters(in: .whitespaces)
        if node.auth == .key || forgetPassword { app.keychain.forgetPassword(nodeId: node.id) }
        if node.auth == .password, !password.isEmpty { try? app.keychain.savePassword(nodeId: node.id, password) }
        app.nodes.upsert(node)
        dismiss()
    }
}
