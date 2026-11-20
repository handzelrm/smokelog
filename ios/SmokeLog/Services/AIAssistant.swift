import Foundation

struct AIError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Fields Claude may fill from free-form notes. nil = leave unchanged.
struct CookPatch: Decodable {
    var title, meat, cut: String?
    var weightLb: Double?
    var smoker, fuel: String?
    var smokerTempF: Int?
    var placement, placementNotes, fatCap: String?
    var waterPan: Bool?
    var waterPanNotes, trimNotes, binder, seasoning: String?
    var seasonedHoursBefore: Double?
    var dryBrine: String?
    var dryBrineHours: Double?
    var wetBrine: String?
    var wetBrineHours: Double?
    var injection: String?
    var injectionHoursBefore: Double?
    var prepNotes: String?
    var startTime: String?
    var startInternalF: Int?
    var wrapTime: String?
    var wrapInternalF: Int?
    var wrapMethod, wrapNotes, offTime: String?
    var offInternalF: Int?
    var restLocation: String?
    var restMinutes: Int?
    var restNotes: String?
    var rating: Int?
    var resultNotes, nextCookNotes: String?
    var events: [PatchEvent]?
    var readings: [PatchReading]?
    var summary: String?

    struct PatchEvent: Decodable { var time: String; var type: String; var note: String? }
    struct PatchReading: Decodable { var time: String; var grillF: Double?; var probeF: Double? }
}

/// Claude via the Anthropic Messages API (raw HTTP – there's no official Swift SDK), or prompts
/// to hand to the Claude app.
final class AIAssistant {
    private let settings: AppSettings
    init(settings: AppSettings) { self.settings = settings }

    private static let iso: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"; f.locale = Locale(identifier: "en_US_POSIX"); return f
    }()
    private static let isoNoSeconds: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd'T'HH:mm"; f.locale = Locale(identifier: "en_US_POSIX"); return f
    }()

    // MARK: API call

    private func ask(system: String, user: String, effort: String?) async throws -> String {
        guard !settings.anthropicKey.isEmpty else { throw AIError(message: "Add an Anthropic API key in Settings") }
        var req = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        req.httpMethod = "POST"
        req.timeoutInterval = 600
        req.setValue(settings.anthropicKey, forHTTPHeaderField: "x-api-key")
        req.setValue("2026-10-21", forHTTPHeaderField: "anthropic-version")
        req.setValue("application/json", forHTTPHeaderField: "content-type")
        // Server-side fallback if a request is ever declined by a safety classifier.
        req.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
        var body: [String: Any] = [
            "model": settings.aiModel,
            "max_tokens": 16000,
            "system": system,
            "messages": [["role": "user", "content": user]],
            "fallbacks": "default",
        ]
        if let effort { body["output_config"] = ["effort": effort] }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data: Data, resp: URLResponse
        do { (data, resp) = try await URLSession.shared.data(for: req) }
        catch { throw AIError(message: "Couldn't reach the AI service – check your connection") }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        switch code {
        case 200: break
        case 401: throw AIError(message: "The Anthropic API key was rejected – check it in Settings")
        case 429: throw AIError(message: "Rate limited by the AI service – try again in a minute")
        default:
            let msg = (obj?["error"] as? [String: Any])?["message"] as? String ?? "HTTP \(code)"
            throw AIError(message: "AI service error: \(msg)")
        }
        if obj?["stop_reason"] as? String == "refusal" { throw AIError(message: "The AI declined this request") }
        let blocks = obj?["content"] as? [[String: Any]] ?? []
        return blocks.compactMap { $0["type"] as? String == "text" ? $0["text"] as? String : nil }
            .joined().trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: Note parsing

    func parseNotes(_ notes: String, current: Cook) async throws -> CookPatch {
        try parseReply(try await ask(system: Self.parseSystem, user: parseUserPrompt(notes, current), effort: "low"))
    }

    /// One self-contained message for the Claude app (it has no separate system prompt).
    func parseRequestForClaudeApp(_ notes: String, current: Cook) -> String {
        Self.parseSystem + "\n\n" + parseUserPrompt(notes, current) +
            "\nReply with only the JSON object, in one code block, so it can be copied back into SmokeLog."
    }

    private func parseUserPrompt(_ notes: String, _ c: Cook) -> String {
        var s = "Current local date/time: \(Self.iso.string(from: Date()))\n"
        if let st = c.startTime { s += "Cook start (meat on): \(Self.iso.string(from: st))\n" }
        s += "Fields already recorded (don't repeat unless the notes change them):\n"
        s += CookShare.summary(c, includeEvents: false, includeReadings: false) + "\n\n<notes>\n\(notes)\n</notes>\n"
        return s
    }

    /// Extracts the JSON fields from a reply – from the API or pasted from the Claude app.
    func parseReply(_ text: String) throws -> CookPatch {
        guard let a = text.firstIndex(of: "{"), let b = text.lastIndex(of: "}"), a < b else {
            throw AIError(message: "No fields found in the reply – copy Claude's whole JSON answer")
        }
        do { return try JSONDecoder().decode(CookPatch.self, from: Data(text[a...b].utf8)) }
        catch { throw AIError(message: "Couldn't read the reply as cook fields – try again") }
    }

    /// Merges a patch into the cook; returns a short description of what changed.
    func apply(_ p: CookPatch, to c: inout Cook) -> String {
        func t(_ s: String?) -> Date? {
            guard let s = s?.trimmingCharacters(in: .whitespaces) else { return nil }
            let v = String(s.prefix(19))
            return Self.iso.date(from: v) ?? Self.isoNoSeconds.date(from: String(v.prefix(16)))
        }
        func str(_ new: String?, _ old: String) -> String { (new?.isEmpty == false) ? new! : old }
        func en<E: RawRepresentable>(_ s: String?, _: E.Type) -> E? where E.RawValue == String {
            s.flatMap { E(rawValue: $0.trimmingCharacters(in: .whitespaces).uppercased()) }
        }
        c.title = str(p.title, c.title); c.meat = str(p.meat, c.meat); c.cut = str(p.cut, c.cut)
        c.weightLb = p.weightLb ?? c.weightLb
        c.smoker = str(p.smoker, c.smoker); c.fuel = str(p.fuel, c.fuel); c.smokerTempF = p.smokerTempF ?? c.smokerTempF
        c.placement = en(p.placement, Placement.self) ?? c.placement; c.placementNotes = str(p.placementNotes, c.placementNotes)
        c.fatCap = en(p.fatCap, FatCap.self) ?? c.fatCap
        c.waterPan = p.waterPan ?? c.waterPan; c.waterPanNotes = str(p.waterPanNotes, c.waterPanNotes)
        c.trimNotes = str(p.trimNotes, c.trimNotes); c.binder = str(p.binder, c.binder); c.seasoning = str(p.seasoning, c.seasoning)
        c.seasonedHoursBefore = p.seasonedHoursBefore ?? c.seasonedHoursBefore
        c.dryBrine = str(p.dryBrine, c.dryBrine); c.dryBrineHours = p.dryBrineHours ?? c.dryBrineHours
        c.wetBrine = str(p.wetBrine, c.wetBrine); c.wetBrineHours = p.wetBrineHours ?? c.wetBrineHours
        c.injection = str(p.injection, c.injection); c.injectionHoursBefore = p.injectionHoursBefore ?? c.injectionHoursBefore
        c.prepNotes = str(p.prepNotes, c.prepNotes)
        c.startTime = t(p.startTime) ?? c.startTime; c.startInternalF = p.startInternalF ?? c.startInternalF
        c.wrapTime = t(p.wrapTime) ?? c.wrapTime; c.wrapInternalF = p.wrapInternalF ?? c.wrapInternalF
        c.wrapMethod = en(p.wrapMethod, WrapMethod.self) ?? c.wrapMethod; c.wrapNotes = str(p.wrapNotes, c.wrapNotes)
        c.offTime = t(p.offTime) ?? c.offTime; c.offInternalF = p.offInternalF ?? c.offInternalF
        c.restLocation = en(p.restLocation, RestLocation.self) ?? c.restLocation
        c.restMinutes = p.restMinutes ?? c.restMinutes; c.restNotes = str(p.restNotes, c.restNotes)
        c.rating = p.rating.map { min(5, max(1, $0)) } ?? c.rating
        c.resultNotes = str(p.resultNotes, c.resultNotes); c.nextCookNotes = str(p.nextCookNotes, c.nextCookNotes)

        let events = (p.events ?? []).compactMap { e -> CookEvent? in
            guard let time = t(e.time) else { return nil }
            return CookEvent(time: time, type: en(e.type, EventType.self) ?? .note, note: e.note ?? "")
        }
        let readings = (p.readings ?? []).compactMap { r -> TempReading? in
            guard let time = t(r.time), r.grillF != nil || r.probeF != nil else { return nil }
            return TempReading(time: time, grillF: r.grillF, probeF: r.probeF)
        }
        c.events += events
        c.readings += readings
        var extras: [String] = []
        if !events.isEmpty { extras.append("\(events.count) timeline events") }
        if !readings.isEmpty { extras.append("\(readings.count) temp readings") }
        return (p.summary ?? "Fields updated.") + (extras.isEmpty ? "" : " Added \(extras.joined(separator: " and ")).") + " Review, then Save."
    }

    // MARK: Analysis

    func analyze(_ c: Cook, past: [Cook], question: String?) async throws -> String {
        try await ask(system: Self.analyzeSystem, user: analyzeUserPrompt(c, past, question), effort: nil)
    }

    func analyzeRequestForClaudeApp(_ c: Cook, past: [Cook], question: String?) -> String {
        Self.analyzeSystem + "\n\n" + analyzeUserPrompt(c, past, question)
    }

    private func analyzeUserPrompt(_ c: Cook, _ past: [Cook], _ question: String?) -> String {
        var s = "<cook>\n\(CookShare.summary(c, includeReadings: false))\n</cook>\n"
        let rs = c.readings.sorted { $0.time < $1.time }
        if let first = rs.first {
            let t0 = c.startTime ?? first.time
            func v(_ d: Double?) -> String { d.map { String(format: "%.0f", $0) } ?? "" }
            let step = max(1, rs.count / 240)
            s += "\n<temperature_log minutes_since_start,pit_F,pit_set_F,probe_F,probe2_F>\n"
            for (i, r) in rs.enumerated() where i % step == 0 || i == rs.count - 1 {
                s += "\(Int(r.time.timeIntervalSince(t0) / 60)),\(v(r.grillF)),\(v(r.grillSetF)),\(v(r.probeF)),\(v(r.probe2F))\n"
            }
            s += "</temperature_log>\n"
        }
        if !past.isEmpty {
            s += "\n<past_cooks_of_similar_meat>\n"
            for p in past { s += CookShare.summary(p, includeEvents: false, includeReadings: false) + "\n---\n" }
            s += "</past_cooks_of_similar_meat>\n"
        }
        let q = question?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return s + "\n" + (q.isEmpty ? "Give me a debrief of this cook." : q)
    }

    // MARK: Prompts (same as Android)

    private static func names<E: CaseIterable & RawRepresentable>(_: E.Type) -> String where E.RawValue == String {
        E.allCases.map(\.rawValue).joined(separator: " | ")
    }

    static let parseSystem = """
    You turn a pitmaster's free-form notes about a barbecue/smoking cook into structured data for a cook log.
    The notes may be dictated by voice, so expect run-ons, shorthand ("pink paper" = butcher paper, "IT" = internal temp,
    "butt" = pork shoulder, "packer" = whole brisket, "SPG" = salt/pepper/garlic).

    Reply with ONE JSON object and nothing else. Include only the keys the notes actually give you information about;
    omit everything else. Never invent values. Temperatures are °F (convert °C). Times are local ISO-8601
    "yyyy-MM-ddTHH:mm:ss", resolved against the current date/time and cook start you are given
    (e.g. "wrapped at 1pm" on a cook that started this morning = today 13:00; "5 hours in" = start + 5h).

    Keys:
      title, meat, cut, smoker, fuel, placementNotes, waterPanNotes, trimNotes, binder, seasoning, dryBrine,
      wetBrine, injection, prepNotes, wrapNotes, restNotes, resultNotes, nextCookNotes, summary: string
      (nextCookNotes = things the cook says to do differently or remember next time)
      weightLb, seasonedHoursBefore, dryBrineHours, wetBrineHours, injectionHoursBefore: number
      smokerTempF, startInternalF, wrapInternalF, offInternalF, restMinutes, rating (1-5): integer
      waterPan: boolean
      startTime, wrapTime, offTime: datetime string
      meat / cut: use these exact names when one fits, otherwise free text:
    \(MeatCatalog.describe())
      placement: one of \(names(Placement.self))
      fatCap: one of \(names(FatCap.self))
      wrapMethod: one of \(names(WrapMethod.self))
      restLocation: one of \(names(RestLocation.self))
      events: array of {time, type, note} for things that happened mid-cook (spritzing, temp changes, flips,
        stall observations...). type is one of \(names(EventType.self))
      readings: array of {time, grillF, probeF} for any temperature readings mentioned with a time
      summary: one short sentence describing what you captured, shown to the user.
    """

    static let analyzeSystem = """
    You are an experienced pitmaster coaching a home cook through their smoking log. You get one cook's details,
    its temperature log (pit and meat probe over time) when available, and their past cooks of similar meat.

    Ground everything in their numbers: total time and hours per pound, when and how long the stall lasted
    (probe climbing under ~1°F per 10 min, typically 150–170°F), how steady the pit was, wrap timing, final
    internal temp and rest. When past cooks exist, compare and point out which variable changes lined up with
    better or worse results. Give concrete, specific suggestions for next time rather than generic advice, and
    say plainly when the data is too thin to support a conclusion. Mention food safety only if something in the
    data warrants it (e.g. a long time below 140°F internal). If they left "next time" notes on earlier cooks,
    say whether this cook followed them and how it turned out.

    Write for a phone screen: short paragraphs or brief bulleted lists, plain text, no markdown headers or tables.
    Keep it under about 300 words unless the user asks for more.
    """
}
