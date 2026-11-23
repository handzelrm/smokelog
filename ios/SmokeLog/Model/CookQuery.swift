import Foundation

/// The person's own labels on a cook, stored as a comma-separated string (same as Android).
enum Tags {
    static func unique(_ list: [String]) -> [String] {
        var seen = Set<String>()
        return list.filter { seen.insert($0.lowercased()).inserted }
    }

    static func parse(_ s: String) -> [String] {
        unique(s.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })
    }

    /// Adds what was typed (several if separated by commas), reusing the spelling of tags that already exist.
    static func add(_ current: String, _ typed: String, known: [String] = []) -> String {
        let new = parse(typed).map { t in known.first { $0.lowercased() == t.lowercased() } ?? t }
        return unique(parse(current) + new).joined(separator: ", ")
    }

    static func remove(_ current: String, _ tag: String) -> String {
        parse(current).filter { $0.lowercased() != tag.lowercased() }.joined(separator: ", ")
    }
}

enum CookSort: String, CaseIterable, Identifiable {
    case newest, oldest, rating, longest, name
    var id: String { rawValue }
    var label: String {
        switch self {
        case .newest: "Newest first"; case .oldest: "Oldest first"; case .rating: "Highest rated"
        case .longest: "Longest cook"; case .name: "Name (A–Z)"
        }
    }
}

enum CookGroup: String, CaseIterable, Identifiable {
    case none, category, meat, month
    var id: String { rawValue }
    var label: String {
        switch self { case .none: "No grouping"; case .category: "Category"; case .meat: "Meat"; case .month: "Month" }
    }
}

/// A cook in the list; `snippet` says where a search matched when the title doesn't show it.
struct CookHit: Identifiable {
    let cook: Cook
    var snippet: String?
    var id: UUID { cook.id }
}

struct CookSection: Identifiable {
    let title: String?
    let hits: [CookHit]
    var id: String { title ?? "" }
}

/// What the list is showing: search text, filters, order and grouping (same rules as Android).
struct CookQuery: Equatable {
    var text = ""
    var categories: Set<String> = []
    var tags: Set<String> = []
    var sort: CookSort = .newest
    var group: CookGroup = .none

    var isFiltering: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !categories.isEmpty || !tags.isEmpty
    }

    func run(_ cooks: [Cook]) -> [CookSection] {
        let words = Self.fold(text).split(whereSeparator: \.isWhitespace).map(String.init)
        let wanted = Set(tags.map { $0.lowercased() })
        let hits: [CookHit] = cooks.compactMap { c in
            if !categories.isEmpty && !categories.contains(c.effectiveCategory) { return nil }
            if !wanted.isSubset(of: c.tagList.map { $0.lowercased() }) { return nil }
            return words.isEmpty ? CookHit(cook: c) : Self.match(c, words)
        }
        let sorted = hits.sorted { inOrder($0.cook, $1.cook) }
        guard !sorted.isEmpty else { return [] }
        switch group {
        case .none:
            return [CookSection(title: nil, hits: sorted)]
        case .category:
            return Dictionary(grouping: sorted) { $0.cook.effectiveCategory }
                .sorted { Self.categoryOrder($0.key) < Self.categoryOrder($1.key) }
                .map { CookSection(title: $0.key, hits: $0.value) }
        case .meat:
            return Dictionary(grouping: sorted) { $0.cook.meat.trimmingCharacters(in: .whitespaces).lowercased() }
                .sorted { a, b in a.key.isEmpty != b.key.isEmpty ? b.key.isEmpty : a.key < b.key }
                .map { key, h in CookSection(title: key.isEmpty ? "No meat chosen" : h[0].cook.meat.trimmingCharacters(in: .whitespaces), hits: h) }
        case .month:
            let cal = Calendar.current
            return Dictionary(grouping: sorted) { cal.dateInterval(of: .month, for: $0.cook.sortDate)!.start }
                .sorted { sort == .oldest ? $0.key < $1.key : $0.key > $1.key }
                .map { CookSection(title: Self.monthF.string(from: $0.key), hits: $0.value) }
        }
    }

    private func inOrder(_ a: Cook, _ b: Cook) -> Bool {
        func newer() -> Bool { a.sortDate > b.sortDate }
        switch sort {
        case .newest: return newer()
        case .oldest: return a.sortDate < b.sortDate
        case .rating: return (a.rating ?? 0) != (b.rating ?? 0) ? (a.rating ?? 0) > (b.rating ?? 0) : newer()
        case .longest: return (a.cookMinutes ?? -1) != (b.cookMinutes ?? -1) ? (a.cookMinutes ?? -1) > (b.cookMinutes ?? -1) : newer()
        case .name:
            let x = a.displayTitle.lowercased(), y = b.displayTitle.lowercased()
            return x != y ? x < y : newer()
        }
    }

    private static let monthF: DateFormatter = { let f = DateFormatter(); f.dateFormat = "MMMM yyyy"; return f }()

    static func categoryOrder(_ name: String) -> Int {
        MeatCatalog.categoryNames.firstIndex(of: name) ?? MeatCatalog.categoryNames.count
    }

    /// Lowercase without accents, so "jalapeno" finds "Jalapeño".
    static func fold(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX")).lowercased()
    }

    /// Everything searchable about a cook, as label → text. Headline fields (already visible in the row) have a blank label.
    static func fields(_ c: Cook) -> [(label: String, text: String)] {
        func join(_ parts: String?...) -> String { parts.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ") }
        func named(_ name: String, _ text: String) -> String { text.isEmpty ? "" : "\(name): \(text)" }
        var out: [(label: String, text: String)] = [
            ("", join(c.displayTitle, c.meat, c.cut, c.effectiveCategory, c.tagList.joined(separator: " "), Fmt.date(c.sortDate))),
            ("Smoker", c.smoker),
            ("Pellets / wood", c.fuel),
            ("Smoker temp", c.smokerTempF.map { "\($0)°F" } ?? ""),
            ("Placement", join(c.placement?.label, c.placementNotes)),
            ("Fat cap", c.fatCap == .na ? "" : (c.fatCap?.label ?? "")),
            ("Water pan", c.waterPan == true ? join("Water pan", c.waterPanNotes) : ""),
            ("Trim", c.trimNotes),
            ("Binder", c.binder),
            ("Seasoning", c.seasoning),
            ("Dry brine", named("Dry brine", c.dryBrine)),
            ("Wet brine", named("Wet brine", c.wetBrine)),
            ("Injection", named("Injection", c.injection)),
            ("Prep notes", c.prepNotes),
            ("Wrap", join(c.wrapMethod.map { $0 == WrapMethod.none ? "Naked" : $0.label }, c.wrapNotes)),
            ("Rest", join(c.restLocation?.label, c.restNotes)),
            ("Result", c.resultNotes),
            ("Next time", c.nextCookNotes),
        ]
        out += c.events.sorted { $0.time < $1.time }.map { ("Timeline", join($0.type.label, $0.note)) }
        out += c.photoList.map { ("Photo", $0.caption) }
        out.append(("AI debrief", c.aiAnalysis))
        return out.filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    /// Every word must appear somewhere in the cook.
    private static func match(_ c: Cook, _ words: [String]) -> CookHit? {
        let all = fields(c).map { (label: $0.label, text: $0.text, folded: fold($0.text)) }
        guard words.allSatisfy({ w in all.contains { $0.folded.contains(w) } }) else { return nil }
        let headline = all.filter { $0.label.isEmpty }.map(\.folded).joined(separator: " ")
        guard let missing = words.first(where: { !headline.contains($0) }),
              let f = all.first(where: { !$0.label.isEmpty && $0.folded.contains(missing) }) else { return CookHit(cook: c) }
        var body = f.text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        if body.hasPrefix("\(f.label): ") { body.removeFirst(f.label.count + 2) }
        return CookHit(cook: c, snippet: "\(f.label): \(excerpt(body, missing))")
    }

    private static func excerpt(_ text: String, _ word: String, around: Int = 36) -> String {
        guard text.count > around * 2 else { return text }
        let folded = fold(text)
        let at = folded.range(of: word).map { folded.distance(from: folded.startIndex, to: $0.lowerBound) } ?? 0
        let chars = Array(text)
        let from = min(max(at - around, 0), chars.count), to = min(at + word.count + around, chars.count)
        return (from > 0 ? "…" : "") + String(chars[from..<max(from, to)]).trimmingCharacters(in: .whitespaces) + (to < chars.count ? "…" : "")
    }
}
