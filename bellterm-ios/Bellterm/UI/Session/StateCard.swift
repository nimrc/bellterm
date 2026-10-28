import SwiftUI

/// Why the session is not attached, and what can be done about it.
struct StateCard: View {
    let state: Connection.State
    let rejectedPassword: Bool
    let onRetry: () -> Void
    let onSignIn: () -> Void
    let onTrust: (_ host: String, _ fingerprint: String) -> Void
    let onLeave: () -> Void

    var body: some View {
        if let (title, body) = text {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(.headline)
                Text(body).font(.callout).textSelection(.enabled)
                HStack {
                    action
                    Button("Nodes", action: onLeave).buttonStyle(.glass)
                }
            }
            .padding(20)
            .frame(maxWidth: 460, alignment: .leading)
            .glassEffect(.regular, in: .rect(cornerRadius: 24))
            .padding(24)
        }
    }

    @ViewBuilder
    private var action: some View {
        switch state {
        case .untrustedHostKey(_, let host, let fingerprint, _):
            Button("Trust and connect") { onTrust(host, fingerprint) }.buttonStyle(.glassProminent)
        case .authRejected where rejectedPassword:
            Button("Enter password", action: onSignIn).buttonStyle(.glassProminent)
        default:
            Button("Reconnect", action: onRetry).buttonStyle(.glassProminent)
        }
    }

    private var text: (String, String)? {
        switch state {
        case .connecting, .connected:
            return nil
        case .disconnected(let reason):
            return (String(localized: "Disconnected"), reason)
        case .authRejected(_, let host):
            return (
                String(localized: "Sign-in rejected"),
                rejectedPassword
                    ? String(localized: "\(host) rejected the password. It may have changed; enter it again.")
                    : String(localized: "\(host) rejected the key. Check that its public key is in ~/.ssh/authorized_keys on the host.")
            )
        case .incompatible(let daemonOutdated, let serverVersion, let minVersion):
            let version = serverVersion ?? String(localized: "an unknown version")
            return daemonOutdated
                ? (String(localized: "Herdr on the host is too old"),
                   String(localized: "The host runs Herdr \(version); Bellterm needs \(minVersion) or newer. Update Herdr on the host."))
                : (String(localized: "Bellterm is too old"),
                   String(localized: "The host runs Herdr \(version), which this version of Bellterm cannot talk to. Update Bellterm."))
        case .failed(let reason):
            return (String(localized: "Could not connect"), reason)
        case .untrustedHostKey(let hop, let host, let fingerprint, let changed):
            let named = hop > 0 ? String(localized: "\(host) (hop \(hop + 1))") : host
            return changed
                ? (String(localized: "Host key changed"),
                   String(localized: "\(named) presented a different key than the one you trusted. This can mean the host was reinstalled, or that someone is intercepting the connection. New fingerprint:\n\n\(fingerprint)"))
                : (String(localized: "Trust this host?"),
                   String(localized: "First connection to \(named). Check that its key fingerprint matches before trusting it:\n\n\(fingerprint)\n\nOn the host: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"))
        }
    }
}

/// Asks for the passwords a route needs, optionally saving them.
struct PasswordsSheet: View {
    let hops: [Node]
    let onConnect: (_ passwords: [String: String], _ remember: Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var secrets: [String: String] = [:]
    @State private var remember = true
    @FocusState private var focused: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(hops) { node in
                        SecureField(node.destination, text: Binding(
                            get: { secrets[node.id, default: ""] }, set: { secrets[node.id] = $0 }
                        ))
                        .textContentType(.password)
                        .focused($focused, equals: node.id)
                    }
                }
                Section {
                    Toggle("Remember password", isOn: $remember)
                } footer: {
                    Text(remember ? "Saved in this device's keychain." : "Not stored; you will be asked again next time.")
                }
            }
            .navigationTitle("Passwords")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Connect") { onConnect(secrets, remember) }
                        .disabled(hops.contains { secrets[$0.id, default: ""].isEmpty })
                }
            }
            .onAppear { focused = hops.first?.id }
        }
        .presentationDetents([.medium])
    }
}
