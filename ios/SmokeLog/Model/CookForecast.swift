import Foundation

/// Predicts when a probe reaches its goal temperature (same model as Android).
///
/// Meat heats roughly per Newton's law: dT/dt = k · (pit − T). The probe's rise over the last
/// hour divided by the average pit-minus-meat gap gives k for this piece of meat; the curve is
/// then projected against the pit's set (or recent) temperature. A stall is reported rather
/// than projected. Without pit data it falls back to the recent straight-line rise.
enum CookForecast {
    struct Point { var time: Date; var meatF: Double; var pitF: Double?; var setF: Double? }

    enum Kind { case onTrack, stalled, notRising, pitTooLow, done, needMoreData }

    struct Forecast {
        var kind: Kind
        var currentF: Double?
        var goalF: Double
        var pitF: Double? = nil
        var ratePerHour: Double? = nil
        var k: Double? = nil
        var from = Date.distantPast
        var doneAt: Date? = nil
        var stallSince: Date? = nil
        var afterStallMinutes: Int? = nil
        var needMinutes: Int? = nil

        func minutesLeft(_ now: Date = Date()) -> Int? { doneAt.map { max(0, Int($0.timeIntervalSince(now) / 60)) } }

        func project(at t: Date) -> Double? {
            guard let cur = currentF else { return nil }
            let h = t.timeIntervalSince(from) / 3600
            if let k, let pitF { return pitF - (pitF - cur) * exp(-k * h) }
            if let ratePerHour { return cur + ratePerHour * h }
            return nil
        }
    }

    static let minSpanMinutes = 15
    private static let maxHours = 48.0

    static func forecast(_ points: [Point], goal: Double) -> Forecast {
        let pts = points.sorted { $0.time < $1.time }
        guard let last = pts.last else { return Forecast(kind: .needMoreData, currentF: nil, goalF: goal, needMinutes: minSpanMinutes) }
        let cur = last.meatF
        if cur >= goal { return Forecast(kind: .done, currentF: cur, goalF: goal, from: last.time) }

        let window = pts.filter { $0.time >= last.time - 3600 }
        let span = Int(last.time.timeIntervalSince(window[0].time) / 60)
        if window.count < 4 || span < minSpanMinutes {
            return Forecast(kind: .needMoreData, currentF: cur, goalF: goal, from: last.time, needMinutes: minSpanMinutes - span)
        }

        let recentPits = pts.filter { $0.time >= last.time - 15 * 60 }.compactMap(\.pitF)
        let pit = last.setF ?? (recentPits.isEmpty ? nil : recentPits.reduce(0, +) / Double(recentPits.count))
        let rate = slopePerHour(pts.filter { $0.time >= last.time - 30 * 60 })

        if (140...185).contains(cur), rate < 3 {
            let since = stallStart(pts, cur)
            if last.time.timeIntervalSince(since) >= 20 * 60 {
                let kBefore = heatingK(pts.filter { $0.time >= since - 3600 && $0.time <= since })
                var after: Int?
                if let pit, let kBefore, pit > goal + 2 {
                    let h = hoursTo(cur, goal, pit, kBefore)
                    if h <= maxHours { after = Int(h * 60) }
                }
                return Forecast(kind: .stalled, currentF: cur, goalF: goal, pitF: pit, ratePerHour: rate, from: last.time,
                                stallSince: since, afterStallMinutes: after)
            }
        }

        if let pit {
            if pit <= goal + 2 { return Forecast(kind: .pitTooLow, currentF: cur, goalF: goal, pitF: pit, ratePerHour: rate, from: last.time) }
            if let k = heatingK(window) {
                let h = hoursTo(cur, goal, pit, k)
                if h > maxHours { return Forecast(kind: .notRising, currentF: cur, goalF: goal, pitF: pit, ratePerHour: rate, k: k, from: last.time) }
                return Forecast(kind: .onTrack, currentF: cur, goalF: goal, pitF: pit, ratePerHour: rate, k: k, from: last.time,
                                doneAt: last.time + h * 3600)
            }
        } else if rate > 0.5 {
            let h = (goal - cur) / rate
            if h <= maxHours {
                return Forecast(kind: .onTrack, currentF: cur, goalF: goal, ratePerHour: rate, from: last.time, doneAt: last.time + h * 3600)
            }
        }
        return Forecast(kind: .notRising, currentF: cur, goalF: goal, pitF: pit, ratePerHour: rate, from: last.time)
    }

    private static func heatingK(_ pts: [Point]) -> Double? {
        let withPit = pts.filter { $0.pitF != nil }
        guard withPit.count >= 4, let f = withPit.first, let l = withPit.last,
              l.time.timeIntervalSince(f.time) >= Double(minSpanMinutes * 60) else { return nil }
        let gap = withPit.map { $0.pitF! - $0.meatF }.reduce(0, +) / Double(withPit.count)
        guard gap > 5 else { return nil }
        let k = slopePerHour(withPit) / gap
        return k > 1e-4 ? k : nil
    }

    private static func hoursTo(_ cur: Double, _ goal: Double, _ pit: Double, _ k: Double) -> Double {
        log((pit - cur) / (pit - goal)) / k
    }

    private static func slopePerHour(_ pts: [Point]) -> Double {
        guard pts.count >= 2 else { return 0 }
        let t0 = pts[0].time
        let xs = pts.map { $0.time.timeIntervalSince(t0) / 3600 }, ys = pts.map(\.meatF)
        let mx = xs.reduce(0, +) / Double(xs.count), my = ys.reduce(0, +) / Double(ys.count)
        let den = xs.reduce(0) { $0 + ($1 - mx) * ($1 - mx) }
        guard den > 0 else { return 0 }
        return zip(xs, ys).reduce(0) { $0 + ($1.0 - mx) * ($1.1 - my) } / den
    }

    private static func stallStart(_ pts: [Point], _ cur: Double) -> Date {
        var since = pts.last!.time
        for p in pts.reversed() {
            if p.meatF < cur - 5 || p.meatF > cur + 5 { break }
            since = p.time
        }
        return since
    }

    struct ProbeForecast { var slot: Int; var label: String; var forecast: Forecast; var goalIsTypical: Bool }

    /// A forecast for each reporting probe with a goal: its grill target ([liveTargets] while
    /// recording, else the stored target for the main probe), or the meat's usual pull temp
    /// for the main probe.
    static func forProbes(meat: String, cut: String, readings: [TempReading], liveTargets: [Double?]?) -> [ProbeForecast] {
        let rs = readings.sorted { $0.time < $1.time }
        guard let last = rs.last, let main = last.probeSlots.firstIndex(where: { $0 != nil }) else { return [] }
        return (0..<Probes.count).compactMap { slot in
            guard rs.contains(where: { $0.time >= last.time - 600 && $0.probeSlots[slot] != nil }) else { return nil }
            var grillGoal: Double?
            if let liveTargets { grillGoal = slot < liveTargets.count ? liveTargets[slot] : nil }
            else if slot == main { grillGoal = rs.last { $0.probeTargetF != nil }?.probeTargetF }
            let typical = slot == main && grillGoal == nil ? MeatCatalog.typicalDoneF(meat, cut: cut) : nil
            guard let goal = grillGoal ?? typical else { return nil }
            let pts = rs.compactMap { r in r.probeSlots[slot].map { Point(time: r.time, meatF: $0, pitF: r.grillF, setF: r.grillSetF) } }
            return ProbeForecast(slot: slot, label: Probes.labels[slot], forecast: forecast(pts, goal: goal), goalIsTypical: typical != nil)
        }
    }
}

extension MeatCatalog {
    /// Usual pull temperature (°F) for a meat; nil when there isn't one sensible number.
    static func typicalDoneF(_ meat: String, cut: String = "") -> Double? {
        let m = meat.trimmingCharacters(in: .whitespaces).lowercased(), c = cut.lowercased()
        switch m {
        case "brisket", "beef ribs", "chuck roast", "pastrami": return 203
        case "beef cheeks": return 205
        case "tri-tip", "picanha": return 135
        case "prime rib / ribeye roast", "beef tenderloin": return 130
        case "pork butt / shoulder": return 203
        case "pork ribs": return 198
        case "pork belly": return c.contains("bacon") ? 150 : 200
        case "pork loin", "pork tenderloin", "pork chops": return 145
        case "ham": return 140
        case "sausage": return 160
        case "whole chicken", "chicken breast", "whole turkey", "turkey breast": return 165
        case "chicken thighs", "chicken drumsticks", "chicken wings", "turkey legs": return 175
        case "duck": return c.contains("breast") ? 135 : 165
        case "leg of lamb", "rack of lamb": return 135
        case "lamb shoulder": return 200
        case "venison": return c.contains("backstrap") ? 130 : nil
        case "salmon", "trout", "other fish": return 145
        case "meatloaf", "meatballs": return 160
        default: return category(for: m) == "Poultry" ? 165 : nil
        }
    }
}
