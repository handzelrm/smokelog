import SwiftUI

struct CookListView: View {
    @Environment(CookStore.self) private var store
    @Environment(Recorder.self) private var recorder
    @State private var path: [UUID] = []
    @State private var showSettings = false
    @State private var editNew: UUID?
    @State private var importError: String?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if store.cooks.isEmpty {
                    ContentUnavailableView("No cooks yet", systemImage: "flame",
                                           description: Text("Tap + to start logging your first smoke."))
                } else {
                    List(store.cooks) { cook in
                        NavigationLink(value: cook.id) { row(cook) }
                    }
                }
            }
            .navigationTitle("SmokeLog")
            .navigationDestination(for: UUID.self) { CookDetailView(cookId: $0) }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        let c = store.add()
                        path.append(c.id)
                        editNew = c.id
                    } label: { Label("New cook", systemImage: "plus") }
                }
                ToolbarItem(placement: .navigation) {
                    Button { showSettings = true } label: { Label("Settings", systemImage: "gearshape") }
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .sheet(item: Binding(get: { editNew.map(IdBox.init) }, set: { editNew = $0?.id })) { box in
                CookEditView(cookId: box.id)
            }
            .alert("Couldn't import", isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(importError ?? "") }
        }
        .onOpenURL { url in importFile(url) }
    }

    private func row(_ c: Cook) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(c.displayTitle).font(.headline)
                Spacer()
                if recorder.cookId == c.id { Label("Recording", systemImage: "record.circle").font(.caption).foregroundStyle(.red) }
            }
            Text([Fmt.date(c.sortDate), c.weightLb.map { "\(Fmt.num($0)) lb" } ?? "",
                  c.smokerTempF.map { "\($0)°F pit" } ?? "", c.cookMinutes.map { Fmt.duration($0) } ?? ""]
                .filter { !$0.isEmpty }.joined(separator: " · "))
                .font(.subheadline).foregroundStyle(.secondary)
            if let r = c.rating { Text(Fmt.stars(r)).foregroundStyle(.tint) }
        }
        .padding(.vertical, 2)
    }

    /// Opens a cook file shared from SmokeLog on iPhone or Android.
    private func importFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let cook = try CookShare.importCook(from: Data(contentsOf: url))
            store.save(cook)
            path = [cook.id]
        } catch {
            importError = error.localizedDescription
        }
    }
}

struct IdBox: Identifiable { let id: UUID }
