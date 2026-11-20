import Foundation
import Observation

/// Keeps every cook as its own JSON file in Application Support/cooks.
@Observable
final class CookStore {
    private(set) var cooks: [Cook] = []
    private let dir: URL
    private let encoder: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .millisecondsSince1970; return e }()
    private let decoder: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .millisecondsSince1970; return d }()

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cooks", isDirectory: true)
        dir = base
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        load()
    }

    private func load() {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        cooks = files.filter { $0.pathExtension == "json" }
            .compactMap { try? decoder.decode(Cook.self, from: Data(contentsOf: $0)) }
        sort()
    }

    private func sort() { cooks.sort { $0.sortDate > $1.sortDate } }
    private func file(_ id: UUID) -> URL { dir.appendingPathComponent("\(id.uuidString).json") }

    private func persist(_ cook: Cook) {
        if let data = try? encoder.encode(cook) { try? data.write(to: file(cook.id), options: .atomic) }
    }

    func cook(_ id: UUID) -> Cook? { cooks.first { $0.id == id } }

    @discardableResult
    func add(_ cook: Cook = Cook()) -> Cook {
        cooks.append(cook); sort(); persist(cook)
        return cook
    }

    func save(_ cook: Cook) {
        if let i = cooks.firstIndex(where: { $0.id == cook.id }) { cooks[i] = cook } else { cooks.append(cook) }
        sort(); persist(cook)
    }

    func update(_ id: UUID, _ change: (inout Cook) -> Void) {
        guard var c = cook(id) else { return }
        change(&c)
        save(c)
    }

    func delete(_ id: UUID) {
        cooks.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: file(id))
    }

    func addReading(_ r: TempReading, to id: UUID) {
        update(id) { $0.readings.append(r) }
    }

    /// Most recent "notes for next cook" from other cooks of the same meat.
    func nextCookNotes(meat: String, excluding id: UUID) -> [Cook] {
        let m = meat.trimmingCharacters(in: .whitespaces)
        guard !m.isEmpty else { return [] }
        return cooks.filter {
            $0.id != id && $0.meat.caseInsensitiveCompare(m) == .orderedSame
                && !$0.nextCookNotes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }.prefix(3).map { $0 }
    }

    func similarCooks(meat: String, excluding id: UUID) -> [Cook] {
        let m = meat.trimmingCharacters(in: .whitespaces)
        guard !m.isEmpty else { return [] }
        return cooks.filter { $0.id != id && $0.meat.localizedCaseInsensitiveContains(m) }.prefix(5).map { $0 }
    }
}
