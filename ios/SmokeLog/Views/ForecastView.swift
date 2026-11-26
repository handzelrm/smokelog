import SwiftUI

/// "About 1h 25m to 203°F · done around 2:40 PM" for each probe with a goal.
struct ForecastView: View {
    let forecasts: [CookForecast.ProbeForecast]
    let meat: String
    var now = Date()

    var body: some View {
        ForEach(forecasts, id: \.slot) { pf in
            let goal = Fmt.temp(pf.forecast.goalF) + (pf.goalIsTypical ? " (typical for \(meat.isEmpty ? "this meat" : meat.lowercased()))" : "")
            let t = Self.text(pf.label, pf.forecast, goal, now)
            VStack(alignment: .leading, spacing: 2) {
                Text(t.headline).font(.subheadline.weight(.semibold))
                if let d = t.detail { Text(d).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }

    static func text(_ label: String, _ f: CookForecast.Forecast, _ goal: String, _ now: Date) -> (headline: String, detail: String?) {
        let cur = Fmt.temp(f.currentF)
        var basis: [String] = []
        if let r = f.ratePerHour { basis.append("rising \(Int(r.rounded()))°F/h") }
        if let p = f.pitF, let c = f.currentF { basis.append("pit \(Fmt.temp(p)), \(Int((p - c).rounded()))° hotter than the probe") }
        switch f.kind {
        case .onTrack:
            return ("\(label): about \(Fmt.duration(f.minutesLeft(now))) to \(goal) · done around \(clock(f.doneAt!, now))",
                    (["Now \(cur)"] + basis).joined(separator: " · "))
        case .stalled:
            return ("\(label) is in the stall at \(cur) (since \(Fmt.time(f.stallSince)))",
                    f.afterStallMinutes.map { "Once it climbs again: about \(Fmt.duration($0)) more to \(goal)." }
                        ?? "The estimate comes back once it climbs again.")
        case .notRising: return ("\(label): no estimate yet for \(goal)", "It isn't rising fast enough to project (now \(cur)).")
        case .pitTooLow: return ("\(label): the pit can't reach \(goal)", "Pit \(Fmt.temp(f.pitF)) needs to be hotter than the goal.")
        case .done: return ("\(label) reached \(goal)", "Now \(cur).")
        case .needMoreData:
            return ("\(label): estimate to \(goal) in about \(f.needMinutes ?? CookForecast.minSpanMinutes) min",
                    "Needs \(CookForecast.minSpanMinutes) minutes of readings.")
        }
    }

    /// "2:40 PM" today, else with the day.
    static func clock(_ d: Date, _ now: Date) -> String {
        Calendar.current.isDate(d, inSameDayAs: now) ? Fmt.time(d) : Fmt.dateTime(d)
    }
}
