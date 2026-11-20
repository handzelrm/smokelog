import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// The few iOS-only calls, isolated so the rest of the code also compiles for macOS (used to
/// type-check the project without Xcode).
enum Platform {
    static func keepScreenAwake(_ on: Bool) {
        #if os(iOS)
        DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = on }
        #endif
    }

    static func pasteboardText() -> String? {
        #if os(iOS)
        return UIPasteboard.general.string
        #else
        return NSPasteboard.general.string(forType: .string)
        #endif
    }

    static func open(_ url: URL) {
        #if os(iOS)
        UIApplication.shared.open(url)
        #else
        NSWorkspace.shared.open(url)
        #endif
    }
}

extension View {
    func decimalKeyboard() -> some View {
        #if os(iOS)
        keyboardType(.decimalPad)
        #else
        self
        #endif
    }

    func numberKeyboard() -> some View {
        #if os(iOS)
        keyboardType(.numberPad)
        #else
        self
        #endif
    }

    func emailField() -> some View {
        #if os(iOS)
        keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
        #else
        self
        #endif
    }

    func inlineTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }
}
