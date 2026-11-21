import Foundation

// All temperatures are stored in °F. Enum raw values match the Android app so shared
// cook files work across both.

enum FatCap: String, CaseIterable, Codable, Identifiable {
    case up = "UP", down = "DOWN", na = "NA"
    var id: String { rawValue }
    var label: String { switch self { case .up: "Fat cap up"; case .down: "Fat cap down"; case .na: "N/A" } }
}

enum Placement: String, CaseIterable, Codable, Identifiable {
    case topRack = "TOP_RACK", middleRack = "MIDDLE_RACK", bottomRack = "BOTTOM_RACK"
    case left = "LEFT", center = "CENTER", right = "RIGHT"
    case nearFirepot = "NEAR_FIREPOT", nearChimney = "NEAR_CHIMNEY", hanging = "HANGING", other = "OTHER"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .topRack: "Top rack"; case .middleRack: "Middle rack"; case .bottomRack: "Bottom rack"
        case .left: "Left side"; case .center: "Center"; case .right: "Right side"
        case .nearFirepot: "Near fire pot / hot zone"; case .nearChimney: "Near chimney / exhaust"
        case .hanging: "Hanging"; case .other: "Other"
        }
    }
}

enum WrapMethod: String, CaseIterable, Codable, Identifiable {
    case none = "NONE", butcherPaper = "BUTCHER_PAPER", foil = "FOIL", foilBoat = "FOIL_BOAT"
    case panCovered = "PAN_COVERED", parchment = "PARCHMENT", other = "OTHER"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .none: "Not wrapped"; case .butcherPaper: "Butcher paper"; case .foil: "Foil"; case .foilBoat: "Foil boat"
        case .panCovered: "Covered pan"; case .parchment: "Parchment"; case .other: "Other"
        }
    }
}

enum RestLocation: String, CaseIterable, Codable, Identifiable {
    case cooler = "COOLER", oven = "OVEN", counter = "COUNTER", warmingDrawer = "WARMING_DRAWER"
    case smokerOff = "SMOKER_OFF", other = "OTHER"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .cooler: "Cooler"; case .oven: "Oven (warm/hold)"; case .counter: "Counter / tented"
        case .warmingDrawer: "Warming drawer"; case .smokerOff: "In smoker, off"; case .other: "Other"
        }
    }
}

enum EventType: String, CaseIterable, Codable, Identifiable {
    case note = "NOTE", spritz = "SPRITZ", tempChange = "TEMP_CHANGE", woodAdded = "WOOD_ADDED"
    case flipped = "FLIPPED", stall = "STALL", wrapped = "WRAPPED", pulled = "PULLED"
    case restStart = "REST_START", restEnd = "REST_END"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .note: "Note"; case .spritz: "Spritz / mop"; case .tempChange: "Smoker temp change"
        case .woodAdded: "Wood / pellets added"; case .flipped: "Flipped / rotated"; case .stall: "Stall observed"
        case .wrapped: "Wrapped"; case .pulled: "Taken off"; case .restStart: "Rest started"; case .restEnd: "Rest ended / sliced"
        }
    }
}

struct TempReading: Codable, Hashable, Identifiable {
    var id = UUID()
    var time: Date
    var grillF: Double?
    var grillSetF: Double?
    var probeF: Double?
    var probe2F: Double?
    var probeTargetF: Double?
    var source: String = "manual"
}

/// A picture attached to a cook; the JPEG lives in Application Support/photos/[fileName].
struct CookPhoto: Codable, Hashable, Identifiable {
    var id = UUID()
    var time: Date
    var fileName: String
    var caption: String = ""
}

struct CookEvent: Codable, Hashable, Identifiable {
    var id = UUID()
    var time: Date
    var type: EventType
    var note: String = ""
}

struct Cook: Codable, Hashable, Identifiable {
    var id = UUID()
    var title = ""
    var meat = ""
    var cut = ""
    var weightLb: Double?
    var createdAt = Date()

    // Smoker setup
    var smoker = ""
    var fuel = ""
    var smokerTempF: Int?
    var placement: Placement?
    var placementNotes = ""
    var fatCap: FatCap?
    var waterPan: Bool?
    var waterPanNotes = ""

    // Preparation
    var trimNotes = ""
    var binder = ""
    var seasoning = ""
    var seasonedHoursBefore: Double?
    var dryBrine = ""
    var dryBrineHours: Double?
    var wetBrine = ""
    var wetBrineHours: Double?
    var injection = ""
    var injectionHoursBefore: Double?
    var prepNotes = ""

    // Timeline
    var startTime: Date?
    var startInternalF: Int?
    var wrapTime: Date?
    var wrapInternalF: Int?
    var wrapMethod: WrapMethod?
    var wrapNotes = ""
    var offTime: Date?
    var offInternalF: Int?
    var restLocation: RestLocation?
    var restEndTime: Date?
    var restMinutes: Int?
    var restNotes = ""

    // Outcome
    var rating: Int?
    var resultNotes = ""
    var aiAnalysis = ""
    var nextCookNotes = ""

    var traegerThing: String?
    var readings: [TempReading] = []
    var events: [CookEvent] = []
    /// Optional so cook files saved before photos existed still load.
    var photos: [CookPhoto]?

    var photoList: [CookPhoto] { (photos ?? []).sorted { $0.time < $1.time } }

    var sortDate: Date { startTime ?? createdAt }

    var cookMinutes: Int? {
        guard let s = startTime, let o = offTime else { return nil }
        return Int(o.timeIntervalSince(s) / 60)
    }
    var wrapElapsedMinutes: Int? {
        guard let s = startTime, let w = wrapTime else { return nil }
        return Int(w.timeIntervalSince(s) / 60)
    }
    var effectiveRestMinutes: Int? {
        if let r = restMinutes { return r }
        guard let o = offTime, let e = restEndTime else { return nil }
        return Int(e.timeIntervalSince(o) / 60)
    }
    var displayTitle: String {
        if !title.isEmpty { return title }
        let t = [meat, cut].filter { !$0.isEmpty }.joined(separator: " – ")
        return t.isEmpty ? "Untitled cook" : t
    }
}
