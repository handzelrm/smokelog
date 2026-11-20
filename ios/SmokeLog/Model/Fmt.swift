import Foundation

enum Fmt {
    private static let dateTimeF: DateFormatter = { let f = DateFormatter(); f.dateFormat = "EEE MMM d, h:mm a"; return f }()
    private static let timeF: DateFormatter = { let f = DateFormatter(); f.dateFormat = "h:mm a"; return f }()
    private static let dateF: DateFormatter = { let f = DateFormatter(); f.dateFormat = "MMM d, yyyy"; return f }()

    static func dateTime(_ d: Date?) -> String { d.map(dateTimeF.string(from:)) ?? "–" }
    static func time(_ d: Date?) -> String { d.map(timeF.string(from:)) ?? "–" }
    static func date(_ d: Date?) -> String { d.map(dateF.string(from:)) ?? "–" }

    static func duration(_ minutes: Int?) -> String {
        guard let m = minutes else { return "–" }
        return m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m"
    }
    static func temp(_ f: Double?) -> String { f.map { "\(Int($0.rounded()))°F" } ?? "–" }
    static func temp(_ f: Int?) -> String { f.map { "\($0)°F" } ?? "–" }
    static func num(_ d: Double?) -> String {
        guard let d else { return "" }
        return d.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(d)) : String(format: "%.1f", d)
    }
    static func stars(_ n: Int) -> String { String(repeating: "★", count: n) + String(repeating: "☆", count: max(0, 5 - n)) }
}
