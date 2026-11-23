import SwiftUI

struct CookEditView: View {
    let cookId: UUID
    @Environment(CookStore.self) private var store
    @Environment(AppSettings.self) private var settings
    @Environment(\.ai) private var ai
    @Environment(\.dismiss) private var dismiss
    @State private var draft = Cook()
    @State private var loaded = false
    @State private var pendingTag = ""

    // AI quick fill
    @State private var notes = ""
    @State private var aiBusy = false
    @State private var aiResult: String?
    @State private var aiError: String?
    @State private var sentToClaude = false

    var body: some View {
        NavigationStack {
            Form {
                aiSection

                Section("Meat") {
                    TextField("Title (optional)", text: $draft.title)
                    MeatPicker(meat: $draft.meat, cut: $draft.cut)
                    NumberRow(title: "Weight", value: $draft.weightLb, unit: "lb")
                }

                Section("Organize") {
                    CategoryPicker(meat: draft.meat, category: $draft.category)
                    TagEditor(tags: Binding(get: { draft.tags ?? "" }, set: { draft.tags = $0 }), pending: $pendingTag, known: knownTags)
                }

                let past = store.nextCookNotes(meat: draft.meat, excluding: draft.id)
                if !past.isEmpty {
                    Section { PastNotesCard(meat: draft.meat, past: past).listRowInsets(EdgeInsets()) }
                }

                Section("Preparation") {
                    MultilineRow(title: "Trim", text: $draft.trimNotes)
                    TextField("Binder", text: $draft.binder, prompt: Text("Binder (mustard, hot sauce, oil…)"))
                    MultilineRow(title: "Seasoning / rub", text: $draft.seasoning)
                    NumberRow(title: "Seasoned before", value: $draft.seasonedHoursBefore, unit: "h")
                    TextField("Dry brine", text: $draft.dryBrine)
                    NumberRow(title: "Dry brine duration", value: $draft.dryBrineHours, unit: "h")
                    MultilineRow(title: "Wet brine", text: $draft.wetBrine)
                    NumberRow(title: "Wet brine duration", value: $draft.wetBrineHours, unit: "h")
                    MultilineRow(title: "Injection", text: $draft.injection)
                    NumberRow(title: "Injected before", value: $draft.injectionHoursBefore, unit: "h")
                    MultilineRow(title: "Other prep notes", text: $draft.prepNotes)
                }

                Section("Smoker setup") {
                    TextField("Smoker", text: $draft.smoker, prompt: Text("Smoker (e.g. Traeger Ironwood 885)"))
                    TextField("Pellets / wood", text: $draft.fuel)
                    IntRow(title: "Smoker temperature", value: $draft.smokerTempF, unit: "°F")
                    OptionalPicker(title: "Placement", selection: $draft.placement) { $0.label }
                    TextField("Placement notes", text: $draft.placementNotes)
                    OptionalPicker(title: "Fat cap", selection: $draft.fatCap) { $0.label }
                    YesNoRow(title: "Water pan", value: $draft.waterPan)
                    if draft.waterPan == true { TextField("Water pan notes", text: $draft.waterPanNotes) }
                }

                Section("Cook") {
                    OptionalDateRow(title: "Meat on", date: $draft.startTime)
                    IntRow(title: "Internal temp when on", value: $draft.startInternalF, unit: "°F")
                }

                Section("Wrap") {
                    OptionalPicker(title: "Wrap method", selection: $draft.wrapMethod) { $0.label }
                    if let m = draft.wrapMethod, m != .none {
                        OptionalDateRow(title: "Wrapped at", date: $draft.wrapTime)
                        IntRow(title: "Internal temp at wrap", value: $draft.wrapInternalF, unit: "°F")
                        MultilineRow(title: "Wrap notes", text: $draft.wrapNotes, prompt: "Tallow, butter, spritz before wrapping…")
                    }
                }

                Section("Off & rest") {
                    OptionalDateRow(title: "Taken off at", date: $draft.offTime)
                    IntRow(title: "Internal temp when off", value: $draft.offInternalF, unit: "°F")
                    OptionalPicker(title: "Rested in", selection: $draft.restLocation) { $0.label }
                    OptionalDateRow(title: "Rest ended / sliced", date: $draft.restEndTime)
                    IntRow(title: "Rest length (if no end time)", value: $draft.restMinutes, unit: "min")
                    TextField("Rest notes", text: $draft.restNotes, prompt: Text("Rest notes (oven at 170°F, towels…)"))
                }

                Section("Result") {
                    HStack {
                        Text("Rating")
                        Spacer()
                        ForEach(1...5, id: \.self) { n in
                            Button { draft.rating = draft.rating == n ? nil : n } label: {
                                Image(systemName: (draft.rating ?? 0) >= n ? "star.fill" : "star")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("\(n) stars")
                        }
                    }
                    MultilineRow(title: "How did it turn out?", text: $draft.resultNotes)
                }

                Section {
                    MultilineRow(title: "What to change or remember", text: $draft.nextCookNotes,
                                 prompt: "e.g. Wrap at 170 instead of 165, rest at least 2 h")
                } header: {
                    Text("Notes for next cook")
                } footer: {
                    Text("Shown when you start your next \(draft.meat.isEmpty ? "cook of this meat" : draft.meat.lowercased()).")
                }
            }
            .navigationTitle("Edit cook")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save() } }
            }
            .onAppear {
                if !loaded, let c = store.cook(cookId) { draft = c; loaded = true }
            }
        }
    }

    private var knownTags: [String] { store.tagCounts.map(\.name) }

    private func save() {
        // Keep a tag that was typed but not yet added.
        if !pendingTag.trimmingCharacters(in: .whitespaces).isEmpty {
            draft.tags = Tags.add(draft.tags ?? "", pendingTag, known: knownTags)
        }
        store.save(draft)
        dismiss()
    }

    @ViewBuilder private var aiSection: some View {
        Section {
            if !settings.hasAI {
                Text("Add an Anthropic API key in Settings, or switch the AI assistant to the Claude app, to fill this form from your notes.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                MultilineRow(title: "Describe the cook", text: $notes,
                             prompt: "“14 lb prime packer, dry brined overnight, SPG 1 h before. On at 6am at 250, fat side down, water pan. Wrapped in butcher paper with tallow at 165 around 1pm…”")
                if settings.usesClaudeApp {
                    if let ai {
                        ClaudeShareButton(title: "Ask Claude",
                                          prompt: { ai.parseRequestForClaudeApp(notes, current: draft) },
                                          onShare: { sentToClaude = true })
                            .disabled(notes.isEmpty)
                    }
                    if sentToClaude {
                        Text("In the share sheet pick Claude and send. Then copy Claude's reply and paste it here.")
                            .font(.footnote).foregroundStyle(.secondary)
                        PasteButton(payloadType: String.self) { strings in
                            guard let text = strings.first else { return }
                            Task { @MainActor in applyReply(text) }
                        }
                    }
                } else {
                    Button {
                        Task { await fillWithAPI() }
                    } label: {
                        HStack { Label("Fill fields", systemImage: "sparkles"); if aiBusy { Spacer(); ProgressView() } }
                    }
                    .disabled(notes.isEmpty || aiBusy)
                }
                if let aiResult { Text(aiResult).font(.footnote) }
                if let aiError { Text(aiError).font(.footnote).foregroundStyle(.red) }
            }
        } header: {
            Text("Quick fill with AI")
        } footer: {
            if settings.hasAI { Text("Tip: tap the microphone on the keyboard to dictate.") }
        }
    }

    private func applyReply(_ text: String) {
        guard let ai else { return }
        do {
            let patch = try ai.parseReply(text)
            aiResult = ai.apply(patch, to: &draft)
            aiError = nil
        } catch {
            aiError = error.localizedDescription
        }
    }

    private func fillWithAPI() async {
        guard let ai else { return }
        aiBusy = true; aiError = nil; aiResult = nil
        defer { aiBusy = false }
        do {
            let patch = try await ai.parseNotes(notes, current: draft)
            aiResult = ai.apply(patch, to: &draft)
        } catch {
            aiError = error.localizedDescription
        }
    }
}
