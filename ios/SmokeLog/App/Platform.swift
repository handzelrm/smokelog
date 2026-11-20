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

#if os(iOS)
/// The system share sheet, presented explicitly (ShareLink can't also run code on tap inside a List).
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
#endif

/// Opens the share sheet with a prompt for the Claude app, and reports that it was used.
struct ClaudeShareButton: View {
    let title: String
    let prompt: () -> String
    var onShare: () -> Void = {}
    @State private var item: SharedText?

    private struct SharedText: Identifiable { let id = UUID(); let text: String }

    var body: some View {
        Button {
            onShare()
            item = SharedText(text: prompt())
        } label: { Label(title, systemImage: "sparkles") }
        .sheet(item: $item) { shared in
            #if os(iOS)
            ActivityView(items: [shared.text]).presentationDetents([.medium, .large])
            #else
            ShareLink(item: shared.text)
            #endif
        }
    }
}
