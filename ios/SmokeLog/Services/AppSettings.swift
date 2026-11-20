import Foundation
import Observation
import Security

/// Non-secret settings in UserDefaults; the Traeger password and API key in the Keychain.
@Observable
final class AppSettings {
    static let defaultModel = "claude-opus-5-5"

    enum AIProvider: String, CaseIterable, Identifiable {
        case claudeApp = "claude_app", api = "api"
        var id: String { rawValue }
        var label: String { self == .claudeApp ? "Claude app" : "API key" }
    }

    var traegerEmail: String { didSet { defaults.set(traegerEmail, forKey: "traeger_email") } }
    var traegerPassword: String { didSet { Keychain.set(traegerPassword, for: "traeger_password") } }
    var anthropicKey: String { didSet { Keychain.set(anthropicKey, for: "anthropic_key") } }
    var aiModel: String { didSet { defaults.set(aiModel, forKey: "ai_model") } }
    var readingIntervalSec: Int { didSet { defaults.set(readingIntervalSec, forKey: "reading_interval") } }
    var aiProvider: AIProvider { didSet { defaults.set(aiProvider.rawValue, forKey: "ai_provider") } }

    @ObservationIgnored private let defaults = UserDefaults.standard

    init() {
        traegerEmail = defaults.string(forKey: "traeger_email") ?? ""
        traegerPassword = Keychain.get("traeger_password") ?? ""
        anthropicKey = Keychain.get("anthropic_key") ?? ""
        aiModel = defaults.string(forKey: "ai_model") ?? AppSettings.defaultModel
        let interval = defaults.integer(forKey: "reading_interval")
        readingIntervalSec = interval == 0 ? 60 : interval
        aiProvider = AIProvider(rawValue: defaults.string(forKey: "ai_provider") ?? "") ?? .claudeApp
    }

    var hasTraeger: Bool { !traegerEmail.isEmpty && !traegerPassword.isEmpty }
    var usesClaudeApp: Bool { aiProvider == .claudeApp }
    var hasAI: Bool { usesClaudeApp || !anthropicKey.isEmpty }
}

enum Keychain {
    private static let service = "com.smokelog.ios"

    static func set(_ value: String, for key: String) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service, kSecAttrAccount as String: key]
        SecItemDelete(base as CFDictionary)
        guard !value.isEmpty else { return }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: key, kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
}
