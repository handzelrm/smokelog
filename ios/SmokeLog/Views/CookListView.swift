import SwiftUI

struct CookListView: View {
    @Environment(CookStore.self) private var store
    @Environment(Recorder.self) private var recorder
    @State private var path: [UUID] = []
    @State private var showSettings = false
    @State private var editNew: UUID?
    @State private var importError: String?
    @State private var query = CookQuery()
    @AppStorage("list_sort") private var sort: CookSort = .newest
    @AppStorage("list_group") private var group: CookGroup = .none

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if store.cooks.isEmpty {
                    ContentUnavailableView("No cooks yet", systemImage: "flame",
                                           description: Text("Tap + to start logging your first smoke."))
                } else {
                    cookList
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
                if !store.cooks.isEmpty {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Picker("Sort by", selection: $sort) { ForEach(CookSort.allCases) { Text($0.label).tag($0) } }
                            Picker("Group by", selection: $group) { ForEach(CookGroup.allCases) { Text($0.label).tag($0) } }
                        } label: { Label("Sort and group", systemImage: "arrow.up.arrow.down") }
                        .pickerStyle(.menu)
                    }
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

    private var cookList: some View {
        var q = query
        q.sort = sort
        q.group = group
        let sections = q.run(store.cooks)
        let shown = sections.reduce(0) { $0 + $1.hits.count }
        let categories = store.categoryCounts, tags = store.tagCounts
        return List {
            if categories.count > 1 || !tags.isEmpty || !query.categories.isEmpty {
                Section {
                    FilterChips(categories: categories, tags: tags, query: $query)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }
            if q.isFiltering {
                Section {
                    HStack {
                        Text(shown == 0 ? "No cooks match" : "\(shown) of \(store.cooks.count) cooks").foregroundStyle(.secondary)
                        Spacer()
                        Button("Clear") { query = CookQuery() }.buttonStyle(.borderless)
                    }
                    .font(.subheadline)
                    .listRowBackground(Color.clear)
                }
            }
            ForEach(sections) { s in
                Section {
                    ForEach(s.hits) { hit in NavigationLink(value: hit.cook.id) { row(hit.cook, snippet: hit.snippet) } }
                } header: {
                    if let t = s.title { Text("\(t) (\(s.hits.count))") }
                }
            }
        }
        .compactSections()
        .searchable(text: $query.text, prompt: "Search cooks, rubs, wood, notes…")
    }

    private func row(_ c: Cook, snippet: String?) -> some View {
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
            if !c.tagList.isEmpty {
                Text(c.tagList.map { "#\($0)" }.joined(separator: "  ")).font(.caption).foregroundStyle(.secondary)
            }
            if let snippet { Text(snippet).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
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
