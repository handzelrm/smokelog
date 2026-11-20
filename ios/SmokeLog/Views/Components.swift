import SwiftUI

/// Picker for an optional enum value with a "—" (not set) option.
struct OptionalPicker<E: Hashable & Identifiable & CaseIterable>: View where E.AllCases: RandomAccessCollection {
    let title: String
    @Binding var selection: E?
    let label: (E) -> String

    var body: some View {
        Picker(title, selection: $selection) {
            Text("—").tag(E?.none)
            ForEach(E.allCases) { Text(label($0)).tag(E?.some($0)) }
        }
    }
}

/// Date/time that may be unset.
struct OptionalDateRow: View {
    let title: String
    @Binding var date: Date?

    var body: some View {
        if let d = date {
            HStack {
                DatePicker(title, selection: Binding(get: { d }, set: { date = $0 }))
                Button { date = nil } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Clear \(title)")
            }
        } else {
            Button { date = Date() } label: { LabeledContent(title) { Text("Set").foregroundStyle(.tint) } }
                .foregroundStyle(.primary)
        }
    }
}

/// Yes / No / not set.
struct YesNoRow: View {
    let title: String
    @Binding var value: Bool?

    var body: some View {
        Picker(title, selection: $value) {
            Text("—").tag(Bool?.none)
            Text("Yes").tag(Bool?.some(true))
            Text("No").tag(Bool?.some(false))
        }
    }
}

struct NumberRow: View {
    let title: String
    @Binding var value: Double?
    var unit: String? = nil

    var body: some View {
        LabeledContent(title) {
            HStack(spacing: 4) {
                TextField("", value: $value, format: .number).multilineTextAlignment(.trailing).decimalKeyboard()
                if let unit { Text(unit).foregroundStyle(.secondary) }
            }
        }
    }
}

struct IntRow: View {
    let title: String
    @Binding var value: Int?
    var unit: String? = nil

    var body: some View {
        LabeledContent(title) {
            HStack(spacing: 4) {
                TextField("", value: $value, format: .number).multilineTextAlignment(.trailing).numberKeyboard()
                if let unit { Text(unit).foregroundStyle(.secondary) }
            }
        }
    }
}

struct MultilineRow: View {
    let title: String
    @Binding var text: String
    var prompt: String? = nil

    var body: some View {
        TextField(title, text: $text, prompt: prompt.map { Text($0) }, axis: .vertical).lineLimit(1...6)
    }
}

/// Meat grouped by category, then the cuts for that meat. Both allow "Other" with free text.
struct MeatPicker: View {
    @Binding var meat: String
    @Binding var cut: String
    @State private var customMeat = false
    @State private var customCut = false

    private static let other = "__other__"

    var body: some View {
        let catalogMeat = MeatCatalog.find(meat)
        let meatIsCustom = customMeat || (!meat.isEmpty && catalogMeat == nil)

        Picker("Meat", selection: Binding<String>(
            get: { meatIsCustom ? Self.other : (catalogMeat?.name ?? "") },
            set: { picked in
                if picked == Self.other {
                    customMeat = true
                    if catalogMeat != nil { meat = "" }
                    cut = ""
                } else {
                    customMeat = false
                    if picked != meat {
                        meat = picked
                        if MeatCatalog.find(picked)?.cuts.contains(cut) != true { cut = "" }
                    }
                }
            }
        )) {
            Text("—").tag("")
            ForEach(MeatCatalog.categories, id: \.name) { cat in
                Section(cat.name) { ForEach(cat.meats, id: \.name) { Text($0.name).tag($0.name) } }
            }
            Text("Other (type your own)").tag(Self.other)
        }
        if meatIsCustom { TextField("Meat (your own)", text: $meat, prompt: Text("e.g. Elk roast")) }

        let cuts = meatIsCustom ? [] : (catalogMeat?.cuts ?? [])
        let cutIsCustom = customCut || (!cut.isEmpty && !cuts.contains(cut))
        if !cuts.isEmpty {
            Picker("Cut / style", selection: Binding<String>(
                get: { cutIsCustom ? Self.other : cut },
                set: { picked in
                    if picked == Self.other { customCut = true; if cuts.contains(cut) { cut = "" } }
                    else { customCut = false; cut = picked }
                }
            )) {
                Text("—").tag("")
                ForEach(cuts, id: \.self) { Text($0).tag($0) }
                Text("Other (type your own)").tag(Self.other)
            }
            if cutIsCustom { TextField("Cut / style (your own)", text: $cut) }
        } else if !meat.isEmpty {
            TextField("Cut / style (optional)", text: $cut)
        }
    }
}

/// "Notes for next cook" left on earlier cooks of the same meat.
struct PastNotesCard: View {
    let meat: String
    let past: [Cook]

    var body: some View {
        if !past.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Label("Notes from your last \(meat.lowercased()) cook\(past.count > 1 ? "s" : "")", systemImage: "lightbulb.fill")
                    .font(.subheadline.weight(.semibold))
                ForEach(past) { p in
                    VStack(alignment: .leading, spacing: 2) {
                        Text([Fmt.date(p.sortDate), p.cut, p.rating.map { String(repeating: "★", count: $0) } ?? ""]
                            .filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                        Text(p.nextCookNotes.trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.yellow.opacity(0.22), in: RoundedRectangle(cornerRadius: 12))
        }
    }
}

struct KeyValueRow: View {
    let label: String
    let value: String?

    var body: some View {
        if let v = value?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty, v != "–" {
            LabeledContent(label) { Text(v).multilineTextAlignment(.trailing) }
        }
    }
}
