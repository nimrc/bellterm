import SwiftUI

// Placeholder until the node list lands: proves the Rust core links and runs.
struct RootView: View {
    @State private var publicKey: String?
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button("Generate device key") {
                        do {
                            let key = try generateKey(comment: "bellterm")
                            publicKey = try Bellterm.publicKey(privateKey: key)
                            failure = nil
                        } catch {
                            failure = error.localizedDescription
                        }
                    }
                    .buttonStyle(.glassProminent)
                }
                if let publicKey {
                    Section("Public key") {
                        Text(publicKey).font(.caption.monospaced()).textSelection(.enabled)
                    }
                }
                if let failure {
                    Section { Text(failure).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Bellterm")
        }
    }
}
