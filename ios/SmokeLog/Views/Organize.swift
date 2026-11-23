import SwiftUI

/// Category: worked out from the meat unless one is chosen here.
struct CategoryPicker: View {
    let meat: String
    @Binding var category: String?

    var body: some View {
        Picker("Category", selection: Binding(get: { category ?? "" }, set: { category = $0.isEmpty ? nil : $0 })) {
            Text("Automatic (\(MeatCatalog.category(for: meat)))").tag("")
            ForEach(MeatCatalog.categoryNames, id: \.self) { Text($0).tag($0) }
        }
    }
}

/// Tags as removable chips plus a box to type more. `pending` is text not yet turned into a tag;
/// it's owned by the form so saving keeps it.
struct TagEditor: View {
    @Binding var tags: String
    @Binding var pending: String
    let known: [String]

    var body: some View {
        let current = Tags.parse(tags)
        if !current.isEmpty {
            chips(current) { t in
                Button { tags = Tags.remove(tags, t) } label: {
                    HStack(spacing: 4) { Text(t); Image(systemName: "xmark").font(.caption2) }
                }
                .buttonStyle(.borderedProminent)
                .accessibilityLabel("Remove tag \(t)")
            }
        }
        HStack {
            TextField("Add a tag", text: $pending, prompt: Text("Add a tag (Competition, Thanksgiving…)"))
                .onSubmit(add)
                .submitLabel(.done)
                .onChange(of: pending) { _, v in if v.hasSuffix(",") { add() } }
            if !pending.trimmingCharacters(in: .whitespaces).isEmpty {
                Button(action: add) { Image(systemName: "plus.circle.fill") }.buttonStyle(.borderless).accessibilityLabel("Add tag")
            }
        }
        let typed = pending.trimmingCharacters(in: .whitespaces)
        let suggestions = known.filter { k in
            !current.contains { $0.lowercased() == k.lowercased() } && (typed.isEmpty || k.localizedCaseInsensitiveContains(typed))
        }.prefix(8)
        if !suggestions.isEmpty {
            chips(Array(suggestions)) { t in
                Button(t) { tags = Tags.add(tags, t, known: known); pending = "" }.buttonStyle(.bordered)
            }
        }
    }

    private func add() {
        tags = Tags.add(tags, pending, known: known)
        pending = ""
    }

    private func chips<V: View>(_ items: [String], @ViewBuilder _ chip: @escaping (String) -> V) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack { ForEach(items, id: \.self) { chip($0).buttonBorderShape(.capsule).controlSize(.small) } }
        }
    }
}

/// Categories and tags in use, as filter chips; categories widen the list, tags narrow it.
struct FilterChips: View {
    let categories: [(name: String, count: Int)]
    let tags: [(name: String, count: Int)]
    @Binding var query: CookQuery

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(categories, id: \.name) { c in
                    chip("\(c.name) \(c.count)", icon: nil, on: query.categories.contains(c.name)) {
                        if query.categories.remove(c.name) == nil { query.categories.insert(c.name) }
                    }
                }
                ForEach(tags, id: \.name) { t in
                    let selected = query.tags.filter { $0.lowercased() == t.name.lowercased() }
                    chip("\(t.name) \(t.count)", icon: "tag", on: !selected.isEmpty) {
                        if selected.isEmpty { query.tags.insert(t.name) } else { query.tags.subtract(selected) }
                    }
                }
            }
            .padding(.horizontal)
        }
    }

    private func chip(_ title: String, icon: String?, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if on { Image(systemName: "checkmark") } else if let icon { Image(systemName: icon) }
                Text(title)
            }
            .font(.subheadline)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .foregroundStyle(on ? Color.white : Color.primary)
            .background(on ? Color.accentColor : Color.secondary.opacity(0.15), in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
