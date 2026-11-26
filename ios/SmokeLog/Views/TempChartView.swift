import Charts
import SwiftUI

/// Pit, probe(s), pit set and probe target on one °F axis, with wrap / off markers and a
/// touch crosshair. Colors are slots 1–3 of the validated default categorical palette.
struct TempChartView: View {
    let cook: Cook
    @Environment(\.colorScheme) private var scheme
    @State private var selectedHours: Double?

    private struct Point: Identifiable {
        let id = UUID()
        let series: String
        let segment: Int
        let hours: Double
        let temp: Double
    }

    private var readings: [TempReading] { cook.readings.sorted { $0.time < $1.time } }
    private var t0: Date { min(cook.startTime ?? .distantFuture, readings.first?.time ?? Date()) }
    private func hours(_ d: Date) -> Double { d.timeIntervalSince(t0) / 3600 }

    private var pit: Color { scheme == .dark ? Color(hex: 0x3987E5) : Color(hex: 0x2A78D6) }
    private var probe: Color { scheme == .dark ? Color(hex: 0xD95926) : Color(hex: 0xEB6834) }
    private var probe2: Color { scheme == .dark ? Color(hex: 0x199E70) : Color(hex: 0x1BAF7A) }
    private var bt1: Color { scheme == .dark ? Color(hex: 0xC98500) : Color(hex: 0xEDA100) }
    private var bt2: Color { scheme == .dark ? Color(hex: 0xD55181) : Color(hex: 0xE87BA4) }
    private static let order = ["Pit"] + Probes.labels + ["Pit set"]

    private var seriesDefs: [(name: String, value: (TempReading) -> Double?)] {
        [("Pit", { $0.grillF }), (Probes.labels[0], { $0.probeF }), (Probes.labels[1], { $0.probe2F }),
         (Probes.labels[2], { $0.probe3F }), (Probes.labels[3], { $0.probe4F }), ("Pit set", { $0.grillSetF })]
    }

    /// Splits each series into segments at missing data or gaps over 15 minutes.
    private var points: [Point] {
        var out: [Point] = []
        for def in seriesDefs {
            var segment = 0, last: Date?
            for r in readings {
                guard let v = def.value(r) else { segment += 1; last = nil; continue }
                if let l = last, r.time.timeIntervalSince(l) > 15 * 60 { segment += 1 }
                out.append(Point(series: def.name, segment: segment, hours: hours(r.time), temp: v))
                last = r.time
            }
        }
        return out
    }

    var body: some View {
        let pts = points
        let present = Set(pts.map(\.series))
        let target = readings.last(where: { $0.probeTargetF != nil })?.probeTargetF
        if readings.count < 2 || present.subtracting(["Pit set"]).isEmpty {
            Text("No temperature data yet. Record from a Traeger, or add readings manually.")
                .font(.subheadline).foregroundStyle(.secondary)
        } else {
            let temps = pts.map(\.temp) + [target].compactMap { $0 }
            let lo = ((temps.min()! - 10) / 25).rounded(.down) * 25
            let hi = ((temps.max()! + 10) / 25).rounded(.up) * 25

            Chart {
                ForEach(pts) { p in
                    LineMark(x: .value("Hours", p.hours), y: .value("°F", p.temp), series: .value("Series", "\(p.series)#\(p.segment)"))
                        .foregroundStyle(by: .value("Series", p.series))
                        .lineStyle(p.series == "Pit set" ? StrokeStyle(lineWidth: 1.5, dash: [5, 5]) : StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                }
                if let target {
                    RuleMark(y: .value("Target", target))
                        .foregroundStyle(by: .value("Series", "Target"))
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [8, 6]))
                }
                ForEach(milestones, id: \.label) { m in
                    RuleMark(x: .value(m.label, m.hours))
                        .foregroundStyle(Color.gray)
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                        .annotation(position: .trailing, alignment: .top, spacing: 3) {
                            Text(m.label).font(.caption2.weight(.semibold)).foregroundStyle(.primary)
                        }
                }
                if let sel = selectedHours, let r = nearest(sel) {
                    RuleMark(x: .value("Selected", hours(r.time)))
                        .foregroundStyle(.primary.opacity(0.4))
                        .annotation(position: .top, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                            tooltip(r)
                        }
                }
            }
            .chartYScale(domain: lo...hi)
            .chartXAxis {
                AxisMarks { v in
                    AxisGridLine()
                    AxisValueLabel { if let h = v.as(Double.self) { Text(Self.axisLabel(hours: h, span: span)) } }
                }
            }
            .chartYAxis { AxisMarks(position: .leading) { v in AxisGridLine(); AxisValueLabel { if let t = v.as(Double.self) { Text("\(Int(t))°") } } } }
            .chartForegroundStyleScale(domain: legendNames(present, target != nil), range: legendColors(present, target != nil))
            .chartLegend(position: .top, alignment: .leading)
            .chartXSelection(value: $selectedHours)
            .frame(height: 260)
        }
    }

    private var milestones: [(label: String, hours: Double)] {
        var m: [(String, Double)] = []
        if let w = cook.wrapTime { m.append(("Wrap", hours(w))) }
        if let o = cook.offTime { m.append(("Off", hours(o))) }
        return m.map { (label: $0.0, hours: $0.1) }
    }

    private func legendNames(_ present: Set<String>, _ hasTarget: Bool) -> [String] {
        Self.order.filter(present.contains) + (hasTarget ? ["Target"] : [])
    }
    private func legendColors(_ present: Set<String>, _ hasTarget: Bool) -> [Color] {
        let all: [String: Color] = ["Pit": pit, Probes.labels[0]: probe, Probes.labels[1]: probe2,
                                    Probes.labels[2]: bt1, Probes.labels[3]: bt2, "Pit set": .secondary]
        return Self.order.filter(present.contains).map { all[$0]! } + (hasTarget ? [probe.opacity(0.8)] : [])
    }

    /// Total hours shown, used to pick axis label units.
    private var span: Double { readings.last.map { hours($0.time) } ?? 0 }

    /// Seconds for very short spans (e.g. the first minutes of the demo), minutes under ~2 h, then hours.
    static func axisLabel(hours h: Double, span: Double) -> String {
        if span < 3.0 / 60 { return "\(Int((h * 3600).rounded()))s" }
        if span < 2 { return "\(Int((h * 60).rounded()))m" }
        return h.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(h))h" : String(format: "%.1fh", h)
    }

    private func nearest(_ h: Double) -> TempReading? {
        readings.min { abs(hours($0.time) - h) < abs(hours($1.time) - h) }
    }

    private func tooltip(_ r: TempReading) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(Fmt.time(r.time)) · \(Fmt.duration(Int(r.time.timeIntervalSince(t0) / 60)))").font(.caption.weight(.semibold))
            if let v = r.grillF { Text("Pit  \(Fmt.temp(v))").font(.caption) }
            ForEach(Array(r.probeSlots.enumerated()), id: \.offset) { i, v in
                if let v { Text("\(Probes.labels[i])  \(Fmt.temp(v))").font(.caption) }
            }
            if let v = r.grillSetF { Text("Pit set  \(Fmt.temp(v))").font(.caption) }
        }
        .padding(8)
        .background(.background, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(.quaternary))
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}
