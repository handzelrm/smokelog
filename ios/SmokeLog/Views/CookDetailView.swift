import SwiftUI
import UniformTypeIdentifiers

/// The cook file, written only when actually shared.
struct CookFile: Transferable {
    let cook: Cook
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .json) { f in SentTransferredFile(try CookShare.exportFile(f.cook)) }
    }
}

struct CookDetailView: View {
    let cookId: UUID
    @Environment(CookStore.self) private var store
    @Environment(AppSettings.self) private var settings
    @Environment(Recorder.self) private var recorder
    @Environment(\.traeger) private var traeger
    @Environment(\.ai) private var ai
    @Environment(\.dismiss) private var dismiss

    @State private var showEdit = false
    @State private var showGrills = false
    @State private var showEvent = false
    @State private var showReading = false
    @State private var confirmDelete = false
    @State private var shareItems: [Any]?

    var body: some View {
        if let c = store.cook(cookId) {
            List {
                let past = store.nextCookNotes(meat: c.meat, excluding: c.id)
                if !past.isEmpty { Section { PastNotesCard(meat: c.meat, past: past).listRowInsets(EdgeInsets()) } }
                grillSection(c)
                milestoneSection(c)
                Section("Temperatures") { TempChartView(cook: c) }
                PhotosSection(cook: c)
                setupSection(c)
                if !c.events.isEmpty { timelineSection(c) }
                AIDebriefSection(cook: c)
                if c.rating != nil || !c.resultNotes.isEmpty {
                    Section("Result") {
                        if let r = c.rating { Text(Fmt.stars(r)).foregroundStyle(.tint) }
                        if !c.resultNotes.isEmpty { Text(c.resultNotes) }
                    }
                }
                Section("Notes for next cook") {
                    if c.nextCookNotes.isEmpty {
                        Text("Nothing yet. Tap Edit to leave yourself notes for the next \(c.meat.isEmpty ? "cook" : c.meat.lowercased()).")
                            .foregroundStyle(.secondary)
                    } else { Text(c.nextCookNotes) }
                }
            }
            .navigationTitle(c.displayTitle)
            .inlineTitle()
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button("Edit") { showEdit = true }
                    Menu {
                        Button("Share summary (text + photos)") {
                            shareItems = [CookShare.summary(c)] + c.photoList.map { PhotoStore.shared.url($0.fileName) }
                        }
                        ShareLink("Share full cook file", item: CookFile(cook: c), preview: SharePreview("\(c.displayTitle).smokelog.json"))
                        Divider()
                        Button("Delete cook", role: .destructive) { confirmDelete = true }
                    } label: { Label("More", systemImage: "square.and.arrow.up") }
                }
            }
            .modifier(ShareSheetModifier(items: $shareItems))
            .sheet(isPresented: $showEdit) { CookEditView(cookId: c.id) }
            .sheet(isPresented: $showGrills) { GrillPickerView(cookId: c.id) }
            .sheet(isPresented: $showEvent) { EventSheet { type, note in addEvent(type, note) } }
            .sheet(isPresented: $showReading) { ReadingSheet { pit, probe in addReading(pit, probe) } }
            .confirmationDialog("Delete this cook?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    if recorder.cookId == c.id { recorder.stop() }
                    store.delete(c.id)
                    dismiss()
                }
            } message: { Text("This removes the cook and all of its temperature readings. It can't be undone.") }
        } else {
            ContentUnavailableView("Cook not found", systemImage: "questionmark")
        }
    }

    // MARK: Sections

    @ViewBuilder private func grillSection(_ c: Cook) -> some View {
        Section {
            if recorder.cookId == c.id {
                HStack(alignment: .top, spacing: 24) {
                    bigStat("Pit", Fmt.temp(recorder.status?.grillF), recorder.status?.setF.map { "set \(Fmt.temp($0))" })
                    bigStat("Probe", Fmt.temp(recorder.status?.probeF), recorder.status?.probeTargetF.map { "target \(Fmt.temp($0))" })
                    if let p2 = recorder.status?.probe2F { bigStat("Probe 2", Fmt.temp(p2), nil) }
                }
                if let m = recorder.message { Text(m).font(.caption).foregroundStyle(.secondary) }
                Button("Stop recording", role: .destructive) { recorder.stop() }
            } else {
                Text(recorder.isRecording ? "Another cook is recording. Stop it to record this one."
                     : "Connect to your Traeger to plot pit and probe temperatures automatically. Keep SmokeLog open while recording.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button("Record from grill") { showGrills = true }.disabled(recorder.isRecording)
            }
        } header: { Text("Grill") }
    }

    private func bigStat(_ label: String, _ value: String, _ sub: String?) -> some View {
        VStack(alignment: .leading) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.title.weight(.semibold)).monospacedDigit()
            if let sub { Text(sub).font(.caption).foregroundStyle(.secondary) }
        }
    }

    @ViewBuilder private func milestoneSection(_ c: Cook) -> some View {
        Section("Milestones") {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    if c.startTime == nil { chip("Meat on now") { store.update(c.id) { $0.startTime = Date() } } }
                    if c.wrapTime == nil && c.wrapMethod != WrapMethod.none { chip("Wrapped now") { markNow(.wrapped) } }
                    if c.offTime == nil { chip("Taken off now") { markNow(.pulled) } }
                    if c.offTime != nil && c.restEndTime == nil && c.restMinutes == nil { chip("Rest done now") { markNow(.restEnd) } }
                    chip("Spritzed") { markNow(.spritz) }
                    chip("Add event…") { showEvent = true }
                    chip("Add reading…") { showReading = true }
                }
            }
            KeyValueRow(label: "Meat on", value: c.startTime.map { Fmt.dateTime($0) + (c.startInternalF.map { " · \($0)°F" } ?? "") })
            KeyValueRow(label: "Wrapped", value: c.wrapTime.map { w in
                [Fmt.time(w), c.wrapInternalF.map { "\($0)°F" } ?? "", c.wrapElapsedMinutes.map { "at \(Fmt.duration($0))" } ?? "",
                 c.wrapMethod?.label ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
            } ?? (c.wrapMethod == WrapMethod.none ? "Not wrapped" : nil))
            KeyValueRow(label: "Taken off", value: c.offTime.map { Fmt.time($0) + (c.offInternalF.map { " · \($0)°F" } ?? "") })
            KeyValueRow(label: "Total cook", value: c.cookMinutes.map { m in
                Fmt.duration(m) + (c.weightLb.map { String(format: " · %.1f h/lb", Double(m) / 60 / $0) } ?? "")
            })
            KeyValueRow(label: "Rest", value: [c.effectiveRestMinutes.map { Fmt.duration($0) } ?? "", c.restLocation?.label ?? ""]
                .filter { !$0.isEmpty }.joined(separator: " · "))
        }
    }

    private func chip(_ title: String, action: @escaping () -> Void) -> some View {
        Button(title, action: action).buttonStyle(.bordered).buttonBorderShape(.capsule)
    }

    @ViewBuilder private func setupSection(_ c: Cook) -> some View {
        Section("Setup & prep") {
            KeyValueRow(label: "Meat", value: [c.meat, c.cut].filter { !$0.isEmpty }.joined(separator: " – ") + (c.weightLb.map { " · \(Fmt.num($0)) lb" } ?? ""))
            KeyValueRow(label: "Smoker", value: [c.smoker, c.smokerTempF.map { "\($0)°F" } ?? ""].filter { !$0.isEmpty }.joined(separator: " @ "))
            KeyValueRow(label: "Pellets / wood", value: c.fuel)
            KeyValueRow(label: "Placement", value: [c.placement?.label ?? "", c.placementNotes].filter { !$0.isEmpty }.joined(separator: " – "))
            KeyValueRow(label: "Fat cap", value: c.fatCap?.label)
            KeyValueRow(label: "Water pan", value: c.waterPan.map { $0 ? "Yes" + (c.waterPanNotes.isEmpty ? "" : " – \(c.waterPanNotes)") : "No" })
            KeyValueRow(label: "Trim", value: c.trimNotes)
            KeyValueRow(label: "Binder", value: c.binder)
            KeyValueRow(label: "Seasoning", value: c.seasoning.isEmpty ? nil : c.seasoning + hrs(c.seasonedHoursBefore, " before"))
            KeyValueRow(label: "Dry brine", value: c.dryBrine.isEmpty ? nil : c.dryBrine + hrs(c.dryBrineHours, ""))
            KeyValueRow(label: "Wet brine", value: c.wetBrine.isEmpty ? nil : c.wetBrine + hrs(c.wetBrineHours, ""))
            KeyValueRow(label: "Injection", value: c.injection.isEmpty ? nil : c.injection + hrs(c.injectionHoursBefore, " before"))
            KeyValueRow(label: "Prep notes", value: c.prepNotes)
            KeyValueRow(label: "Wrap notes", value: c.wrapNotes)
            KeyValueRow(label: "Rest notes", value: c.restNotes)
        }
    }

    private func hrs(_ h: Double?, _ s: String) -> String { h.map { " (\(Fmt.num($0)) h\(s))" } ?? "" }

    @ViewBuilder private func timelineSection(_ c: Cook) -> some View {
        Section("Timeline") {
            ForEach(c.events.sorted { $0.time < $1.time }) { e in
                VStack(alignment: .leading, spacing: 2) {
                    Text(Fmt.time(e.time) + (c.startTime.map { " · \(Fmt.duration(Int(e.time.timeIntervalSince($0) / 60))) in" } ?? ""))
                        .font(.caption).foregroundStyle(.secondary)
                    Text(e.type.label + (e.note.isEmpty ? "" : " – \(e.note)"))
                }
                .swipeActions { Button("Delete", role: .destructive) { store.update(c.id) { $0.events.removeAll { $0.id == e.id } } } }
            }
        }
    }

    // MARK: Actions

    /// One-tap milestones: stamp the time and grab the current probe temp when we have one.
    private func markNow(_ type: EventType) {
        let now = Date()
        let liveProbe = recorder.cookId == cookId ? recorder.status?.probeF : nil
        let probe = (liveProbe ?? store.cook(cookId)?.readings.max { $0.time < $1.time }?.probeF).map { Int($0.rounded()) }
        store.update(cookId) { c in
            switch type {
            case .wrapped:
                c.wrapTime = now
                c.wrapInternalF = probe ?? c.wrapInternalF
                if c.wrapMethod == nil || c.wrapMethod == WrapMethod.none { c.wrapMethod = .butcherPaper }
            case .pulled:
                c.offTime = now
                c.offInternalF = probe ?? c.offInternalF
            case .restEnd:
                c.restEndTime = now
            default: break
            }
            c.events.append(CookEvent(time: now, type: type, note: probe.map { "Probe \($0)°F" } ?? ""))
        }
        if type == .pulled, recorder.cookId == cookId { recorder.stop() }
    }

    private func addEvent(_ type: EventType, _ note: String) {
        store.update(cookId) { $0.events.append(CookEvent(time: Date(), type: type, note: note)) }
    }

    private func addReading(_ pit: Double?, _ probe: Double?) {
        guard pit != nil || probe != nil else { return }
        store.addReading(TempReading(time: Date(), grillF: pit, probeF: probe), to: cookId)
    }
}

// MARK: - AI debrief

struct AIDebriefSection: View {
    let cook: Cook
    @Environment(CookStore.self) private var store
    @Environment(AppSettings.self) private var settings
    @Environment(\.ai) private var ai
    @State private var question = ""
    @State private var busy = false
    @State private var error: String?
    @State private var sentToClaude = false

    var body: some View {
        Section {
            if !settings.hasAI {
                Text("Add an Anthropic API key in Settings, or switch the AI assistant to the Claude app, to get a debrief of this cook compared with your past cooks.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else if let ai {
                if !cook.aiAnalysis.isEmpty { Text(cook.aiAnalysis).textSelection(.enabled) }
                if let error { Text(error).foregroundStyle(.red).font(.footnote) }
                TextField("Ask about this cook (optional)", text: $question, prompt: Text("e.g. Why did it stall so long?"), axis: .vertical)
                let past = store.similarCooks(meat: cook.meat, excluding: cook.id)
                if settings.usesClaudeApp {
                    ClaudeShareButton(title: question.isEmpty ? "Debrief in Claude" : "Ask Claude",
                                      prompt: { ai.analyzeRequestForClaudeApp(cook, past: past, question: question) },
                                      onShare: { sentToClaude = true })
                    if sentToClaude {
                        Text("Pick Claude in the share sheet and send. Copy the reply and paste it here to keep it with this cook.")
                            .font(.footnote).foregroundStyle(.secondary)
                        PasteButton(payloadType: String.self) { strings in
                            guard let text = strings.first?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return }
                            Task { @MainActor in store.update(cook.id) { $0.aiAnalysis = text } }
                        }
                    }
                } else {
                    Button {
                        Task {
                            busy = true; error = nil
                            defer { busy = false }
                            do {
                                let text = try await ai.analyze(cook, past: past, question: question)
                                store.update(cook.id) { $0.aiAnalysis = text }
                            } catch { self.error = error.localizedDescription }
                        }
                    } label: {
                        HStack {
                            Label(question.isEmpty ? (cook.aiAnalysis.isEmpty ? "Analyze cook" : "Re-analyze") : "Ask", systemImage: "sparkles")
                            if busy { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(busy)
                }
            }
        } header: { Label("AI pitmaster", systemImage: "sparkles") }
    }
}

// MARK: - Sheets

struct GrillPickerView: View {
    let cookId: UUID
    @Environment(CookStore.self) private var store
    @Environment(AppSettings.self) private var settings
    @Environment(Recorder.self) private var recorder
    @Environment(\.traeger) private var traeger
    @Environment(\.dismiss) private var dismiss
    @State private var grills: [Grill] = []
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if loading { ProgressView("Finding grills…") }
                if let error { Text(error).foregroundStyle(.red).font(.footnote) }
                ForEach(grills + [Grill(thingName: TraegerClient.demoThing, name: "Demo grill (simulated)")]) { g in
                    Button(g.name) { start(g) }
                }
            }
            .navigationTitle("Choose a grill")
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .task {
                defer { loading = false }
                guard settings.hasTraeger else { error = "Add your Traeger login in Settings to see your grills."; return }
                do { grills = try await traeger?.listGrills() ?? [] } catch { self.error = error.localizedDescription }
            }
        }
    }

    private func start(_ g: Grill) {
        guard let traeger else { return }
        store.update(cookId) { c in
            c.traegerThing = g.thingName
            if c.startTime == nil { c.startTime = Date() }
        }
        recorder.start(cookId: cookId, thing: g.thingName, client: traeger, store: store, settings: settings)
        dismiss()
    }
}

struct EventSheet: View {
    let onAdd: (EventType, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var type: EventType = .note
    @State private var note = ""

    var body: some View {
        NavigationStack {
            Form {
                Picker("Type", selection: $type) { ForEach(EventType.allCases) { Text($0.label).tag($0) } }
                MultilineRow(title: "Note", text: $note)
            }
            .navigationTitle("Add event")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Add") { onAdd(type, note); dismiss() } }
            }
        }
        .presentationDetents([.medium])
    }
}

struct ReadingSheet: View {
    let onAdd: (Double?, Double?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pit: Double?
    @State private var probe: Double?

    var body: some View {
        NavigationStack {
            Form {
                NumberRow(title: "Pit temp", value: $pit, unit: "°F")
                NumberRow(title: "Probe / internal", value: $probe, unit: "°F")
            }
            .navigationTitle("Add reading (now)")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Add") { onAdd(pit, probe); dismiss() }.disabled(pit == nil && probe == nil) }
            }
        }
        .presentationDetents([.medium])
    }
}
