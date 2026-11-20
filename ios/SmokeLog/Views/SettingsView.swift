import SwiftUI

struct SettingsView: View {
    @Environment(AppSettings.self) private var settings
    @Environment(\.traeger) private var traeger
    @Environment(\.dismiss) private var dismiss
    @State private var testResult: String?
    @State private var testing = false

    var body: some View {
        @Bindable var s = settings
        NavigationStack {
            Form {
                Section {
                    TextField("Email", text: $s.traegerEmail).emailField()
                    SecureField("Password", text: $s.traegerPassword)
                    Stepper("Save a reading every \(s.readingIntervalSec) s", value: $s.readingIntervalSec, in: 15...900, step: 15)
                    Button {
                        Task {
                            testing = true; defer { testing = false }
                            testResult = "Signing in…"
                            do {
                                let grills = try await traeger?.listGrills() ?? []
                                testResult = grills.isEmpty ? "Signed in, but no grills are on this account."
                                    : "Connected: " + grills.map(\.name).joined(separator: ", ")
                            } catch { testResult = error.localizedDescription }
                        }
                    } label: { HStack { Text("Test connection"); if testing { Spacer(); ProgressView() } } }
                    .disabled(!s.hasTraeger || testing)
                    if let testResult { Text(testResult).font(.footnote) }
                } header: { Text("Traeger account") } footer: {
                    Text("Uses your Traeger app login to read pit and probe temperatures. Traeger has no official public API, so this uses the same cloud service as the Traeger app and could stop working if Traeger changes it.")
                }

                Section {
                    Picker("Use", selection: $s.aiProvider) {
                        Text("Claude app").tag(AppSettings.AIProvider.claudeApp)
                        Text("API key").tag(AppSettings.AIProvider.api)
                    }
                    .pickerStyle(.segmented)
                    if s.usesClaudeApp {
                        Text("Uses the Claude app and your Claude subscription, no API key needed. SmokeLog opens the share sheet with your notes or cook data: pick Claude, send, then copy Claude's reply and paste it back into SmokeLog.")
                            .font(.footnote).foregroundStyle(.secondary)
                        Link("Get the Claude app", destination: URL(string: "https://apps.apple.com/app/claude-by-anthropic/id6473753684")!)
                    } else {
                        SecureField("Anthropic API key", text: $s.anthropicKey)
                        TextField("Model", text: $s.aiModel).emailField()
                        Text("Fully automatic, using an API key from console.anthropic.com (billed separately from a Claude subscription). Your notes and cook data are sent to Anthropic only when you tap an AI button.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                } header: { Text("AI assistant") }

                Section {
                    Text("Credentials are stored in the iOS Keychain on this device.").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
