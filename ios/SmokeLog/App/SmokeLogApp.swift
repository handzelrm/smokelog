import SwiftUI

@main
struct SmokeLogApp: App {
    @State private var store = CookStore()
    @State private var settings: AppSettings
    @State private var recorder = Recorder()
    private let traeger: TraegerClient
    private let ai: AIAssistant

    init() {
        let s = AppSettings()
        _settings = State(initialValue: s)
        traeger = TraegerClient(settings: s)
        ai = AIAssistant(settings: s)
    }

    var body: some Scene {
        WindowGroup {
            CookListView()
                .environment(store)
                .environment(settings)
                .environment(recorder)
                .environment(\.traeger, traeger)
                .environment(\.ai, ai)
        }
    }
}

private struct TraegerKey: EnvironmentKey { static let defaultValue: TraegerClient? = nil }
private struct AIKey: EnvironmentKey { static let defaultValue: AIAssistant? = nil }

extension EnvironmentValues {
    var traeger: TraegerClient? {
        get { self[TraegerKey.self] }
        set { self[TraegerKey.self] = newValue }
    }
    var ai: AIAssistant? {
        get { self[AIKey.self] }
        set { self[AIKey.self] = newValue }
    }
}
