import Foundation

/// Text summaries and the `smokelog.cook` file format shared with the Android app.
enum CookShare {
    static let format = "smokelog.cook"

    // MARK: Text summary

    static func summary(_ c: Cook, includeEvents: Bool = true, includeReadings: Bool = true) -> String {
        var out: [String] = []
        func line(_ label: String, _ value: String?) {
            if let v = value?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty { out.append("\(label): \(v)") }
        }
        func hrs(_ h: Double?, _ suffix: String) -> String { h.map { " (\(Fmt.num($0)) h\(suffix))" } ?? "" }
        func with(_ text: String, _ extra: String) -> String? { text.isEmpty ? nil : text + extra }

        out.append("🔥 \(c.displayTitle)")
        if let s = c.startTime { out.append(Fmt.date(s)) }
        let meat = [c.meat, c.cut, c.weightLb.map { "\(Fmt.num($0)) lb" } ?? ""].filter { !$0.isEmpty }.joined(separator: ", ")
        if !meat.isEmpty { out.append(meat) }
        line("Tags", c.tagList.joined(separator: ", "))
        out.append("")

        out.append("SETUP")
        line("Smoker", [c.smoker, c.smokerTempF.map { "\($0)°F" } ?? ""].filter { !$0.isEmpty }.joined(separator: " @ "))
        line("Fuel / wood", c.fuel)
        line("Placement", [c.placement?.label ?? "", c.placementNotes].filter { !$0.isEmpty }.joined(separator: " – "))
        line("Fat cap", c.fatCap?.label)
        line("Water pan", c.waterPan.map { $0 ? "Yes" + (c.waterPanNotes.isEmpty ? "" : " – \(c.waterPanNotes)") : "No" })
        out.append("")

        out.append("PREP")
        line("Trim", c.trimNotes)
        line("Binder", c.binder)
        line("Seasoning", with(c.seasoning, hrs(c.seasonedHoursBefore, " before")))
        line("Dry brine", with(c.dryBrine, hrs(c.dryBrineHours, "")))
        line("Wet brine", with(c.wetBrine, hrs(c.wetBrineHours, "")))
        line("Injection", with(c.injection, hrs(c.injectionHoursBefore, " before")))
        line("Notes", c.prepNotes)
        out.append("")

        out.append("COOK")
        line("On", c.startTime.map { Fmt.dateTime($0) + (c.startInternalF.map { " at \($0)°F internal" } ?? "") })
        if let w = c.wrapMethod, w != .none {
            line("Wrapped", [w.label, c.wrapInternalF.map { "at \($0)°F" } ?? "",
                             c.wrapElapsedMinutes.map { "after \(Fmt.duration($0))" } ?? "", c.wrapNotes]
                .filter { !$0.isEmpty }.joined(separator: ", "))
        } else if c.wrapMethod == WrapMethod.none { line("Wrapped", "No (naked)") }
        line("Off", c.offTime.map { Fmt.dateTime($0) + (c.offInternalF.map { " at \($0)°F internal" } ?? "") })
        line("Total cook", c.cookMinutes.map { Fmt.duration($0) })
        line("Rest", [c.effectiveRestMinutes.map { Fmt.duration($0) } ?? "", c.restLocation?.label ?? "", c.restNotes]
            .filter { !$0.isEmpty }.joined(separator: ", "))
        let pit = c.readings.compactMap(\.grillF)
        if includeReadings, !pit.isEmpty {
            line("Pit temp", "avg \(Fmt.temp(pit.reduce(0, +) / Double(pit.count))) (range \(Fmt.temp(pit.min()))–\(Fmt.temp(pit.max()))), \(c.readings.count) readings")
        }
        if includeEvents, !c.events.isEmpty {
            out.append(""); out.append("TIMELINE")
            for e in c.events.sorted(by: { $0.time < $1.time }) {
                out.append("• \(Fmt.time(e.time)) \(e.type.label)" + (e.note.isEmpty ? "" : " – \(e.note)"))
            }
        }
        if c.rating != nil || !c.resultNotes.isEmpty {
            out.append(""); out.append("RESULT")
            line("Rating", c.rating.map(Fmt.stars))
            line("Notes", c.resultNotes)
        }
        if !c.nextCookNotes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            out.append(""); out.append("NEXT TIME"); out.append(c.nextCookNotes.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        out.append(""); out.append("Logged with SmokeLog")
        return out.joined(separator: "\n")
    }

    // MARK: Cook file (same JSON as Android)

    struct FileDTO: Codable {
        var format: String
        var version: Int
        var cook: CookDTO
        var readings: [ReadingDTO]
        var events: [EventDTO]
        var photos: [PhotoDTO]?
    }

    /// A photo embedded in the cook file (JPEG, base64) – same as Android. Older versions ignore it.
    struct PhotoDTO: Codable {
        var time: Int64
        var caption: String?
        var jpegBase64: String
    }

    // ids are Android database keys: written for compatibility, optional when reading.
    struct CookDTO: Codable {
        var id: Int64? = 0
        var title, meat, cut: String?
        var weightLb: Double?
        var createdAt: Int64?
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
        var startTime: Int64?
        var startInternalF: Int?
        var wrapTime: Int64?
        var wrapInternalF: Int?
        var wrapMethod, wrapNotes: String?
        var offTime: Int64?
        var offInternalF: Int?
        var restLocation: String?
        var restEndTime: Int64?
        var restMinutes: Int?
        var restNotes: String?
        var rating: Int?
        var resultNotes, aiAnalysis, nextCookNotes: String?
        var tags, category: String?
    }

    struct ReadingDTO: Codable {
        var id: Int64? = 0, cookId: Int64? = 0
        var time: Int64
        var grillF, grillSetF, probeF, probe2F, probeTargetF: Double?
        var probe3F, probe4F: Double?
        var source: String?
    }

    struct EventDTO: Codable {
        var id: Int64? = 0, cookId: Int64? = 0
        var time: Int64
        var type: String
        var note: String?
    }

    private static func ms(_ d: Date?) -> Int64? { d.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) } }
    private static func date(_ ms: Int64?) -> Date? { ms.map { Date(timeIntervalSince1970: Double($0) / 1000) } }

    static func fileData(_ c: Cook) throws -> Data {
        let dto = CookDTO(
            title: c.title, meat: c.meat, cut: c.cut, weightLb: c.weightLb, createdAt: ms(c.createdAt),
            smoker: c.smoker, fuel: c.fuel, smokerTempF: c.smokerTempF, placement: c.placement?.rawValue,
            placementNotes: c.placementNotes, fatCap: c.fatCap?.rawValue, waterPan: c.waterPan, waterPanNotes: c.waterPanNotes,
            trimNotes: c.trimNotes, binder: c.binder, seasoning: c.seasoning, seasonedHoursBefore: c.seasonedHoursBefore,
            dryBrine: c.dryBrine, dryBrineHours: c.dryBrineHours, wetBrine: c.wetBrine, wetBrineHours: c.wetBrineHours,
            injection: c.injection, injectionHoursBefore: c.injectionHoursBefore, prepNotes: c.prepNotes,
            startTime: ms(c.startTime), startInternalF: c.startInternalF, wrapTime: ms(c.wrapTime), wrapInternalF: c.wrapInternalF,
            wrapMethod: c.wrapMethod?.rawValue, wrapNotes: c.wrapNotes, offTime: ms(c.offTime), offInternalF: c.offInternalF,
            restLocation: c.restLocation?.rawValue, restEndTime: ms(c.restEndTime), restMinutes: c.restMinutes,
            restNotes: c.restNotes, rating: c.rating, resultNotes: c.resultNotes, aiAnalysis: c.aiAnalysis,
            nextCookNotes: c.nextCookNotes, tags: c.tags ?? "", category: c.category ?? "")
        let file = FileDTO(
            format: format, version: 1, cook: dto,
            readings: c.readings.sorted { $0.time < $1.time }.map {
                ReadingDTO(time: ms($0.time)!, grillF: $0.grillF, grillSetF: $0.grillSetF, probeF: $0.probeF,
                           probe2F: $0.probe2F, probeTargetF: $0.probeTargetF, probe3F: $0.probe3F, probe4F: $0.probe4F, source: $0.source)
            },
            events: c.events.sorted { $0.time < $1.time }.map { EventDTO(time: ms($0.time)!, type: $0.type.rawValue, note: $0.note) },
            photos: c.photoList.compactMap { p in
                guard let data = try? Data(contentsOf: PhotoStore.shared.url(p.fileName)) else { return nil }
                return PhotoDTO(time: ms(p.time)!, caption: p.caption, jpegBase64: data.base64EncodedString())
            })
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try enc.encode(file)
    }

    /// Writes the cook file to a temporary URL for the share sheet.
    static func exportFile(_ c: Cook) throws -> URL {
        let safe = c.displayTitle.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: "_")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(safe.isEmpty ? "cook" : String(safe.prefix(40))).smokelog.json")
        try fileData(c).write(to: url, options: .atomic)
        return url
    }

    static func importCook(from data: Data) throws -> Cook {
        let f = try JSONDecoder().decode(FileDTO.self, from: data)
        guard f.format == format else { throw TraegerError(message: "Not a SmokeLog cook file") }
        let d = f.cook
        var c = Cook()
        c.title = d.title ?? ""; c.meat = d.meat ?? ""; c.cut = d.cut ?? ""; c.weightLb = d.weightLb
        c.createdAt = date(d.createdAt) ?? Date()
        c.smoker = d.smoker ?? ""; c.fuel = d.fuel ?? ""; c.smokerTempF = d.smokerTempF
        c.placement = d.placement.flatMap(Placement.init(rawValue:)); c.placementNotes = d.placementNotes ?? ""
        c.fatCap = d.fatCap.flatMap(FatCap.init(rawValue:)); c.waterPan = d.waterPan; c.waterPanNotes = d.waterPanNotes ?? ""
        c.trimNotes = d.trimNotes ?? ""; c.binder = d.binder ?? ""; c.seasoning = d.seasoning ?? ""
        c.seasonedHoursBefore = d.seasonedHoursBefore; c.dryBrine = d.dryBrine ?? ""; c.dryBrineHours = d.dryBrineHours
        c.wetBrine = d.wetBrine ?? ""; c.wetBrineHours = d.wetBrineHours; c.injection = d.injection ?? ""
        c.injectionHoursBefore = d.injectionHoursBefore; c.prepNotes = d.prepNotes ?? ""
        c.startTime = date(d.startTime); c.startInternalF = d.startInternalF
        c.wrapTime = date(d.wrapTime); c.wrapInternalF = d.wrapInternalF
        c.wrapMethod = d.wrapMethod.flatMap(WrapMethod.init(rawValue:)); c.wrapNotes = d.wrapNotes ?? ""
        c.offTime = date(d.offTime); c.offInternalF = d.offInternalF
        c.restLocation = d.restLocation.flatMap(RestLocation.init(rawValue:)); c.restEndTime = date(d.restEndTime)
        c.restMinutes = d.restMinutes; c.restNotes = d.restNotes ?? ""
        c.rating = d.rating; c.resultNotes = d.resultNotes ?? ""; c.aiAnalysis = d.aiAnalysis ?? ""
        c.nextCookNotes = d.nextCookNotes ?? ""
        c.tags = d.tags; c.category = d.category.flatMap { $0.isEmpty ? nil : $0 }
        c.title = c.displayTitle + " (shared)"
        c.readings = f.readings.map {
            TempReading(time: date($0.time)!, grillF: $0.grillF, grillSetF: $0.grillSetF, probeF: $0.probeF,
                        probe2F: $0.probe2F, probeTargetF: $0.probeTargetF, source: $0.source ?? "manual",
                        probe3F: $0.probe3F, probe4F: $0.probe4F)
        }
        c.events = f.events.map { CookEvent(time: date($0.time)!, type: EventType(rawValue: $0.type) ?? .note, note: $0.note ?? "") }
        c.photos = (f.photos ?? []).compactMap { p in
            guard let data = Data(base64Encoded: p.jpegBase64), let name = try? PhotoStore.shared.save(jpeg: data) else { return nil }
            return CookPhoto(time: date(p.time)!, fileName: name, caption: p.caption ?? "")
        }
        return c
    }
}
