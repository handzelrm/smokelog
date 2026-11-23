import XCTest
@testable import SmokeLog

final class CookQueryTests: XCTestCase {
    private func cook(_ n: Int, _ meat: String, day: Int? = nil, _ f: (inout Cook) -> Void = { _ in }) -> Cook {
        var c = Cook()
        c.title = "\(n)"; c.meat = meat
        c.startTime = Date(timeIntervalSince1970: Double((day ?? n) * 86_400)); c.createdAt = c.startTime!
        f(&c)
        return c
    }
    private lazy var all: [Cook] = [
        cook(1, "Brisket") { $0.cut = "Flat"; $0.seasoning = "SPG"; $0.tags = "Competition, Thanksgiving"; $0.rating = 4
            $0.offTime = $0.startTime! + 12 * 3600
            $0.events = [CookEvent(time: Date(), type: .spritz, note: "apple cider")] },
        cook(2, "Pork ribs") { $0.fuel = "Cherry"; $0.wrapMethod = .foil; $0.tags = "competition"; $0.rating = 5
            $0.offTime = $0.startTime! + 6 * 3600
            $0.photos = [CookPhoto(time: Date(), fileName: "x", caption: "Smoke ring")] },
        cook(3, "Elk roast") { $0.resultNotes = "Jalapeño rub was great" },
        cook(4, ""),
    ]
    private func ids(_ q: CookQuery) -> [String] { q.run(all).flatMap { $0.hits.map(\.cook.title) } }

    func testCategories() {
        for c in MeatCatalog.categories { for m in c.meats { XCTAssertEqual(MeatCatalog.category(for: m.name), c.name) } }
        XCTAssertEqual(MeatCatalog.category(for: "Beef brisket"), "Beef")
        XCTAssertEqual(MeatCatalog.category(for: "elk roast"), "Lamb & game")
        XCTAssertEqual(MeatCatalog.category(for: "Turkey sausage"), "Poultry")
        XCTAssertEqual(MeatCatalog.category(for: "Boston butt"), "Pork")
        XCTAssertEqual(MeatCatalog.category(for: "smoked tuna"), "Seafood")
        XCTAssertEqual(MeatCatalog.category(for: "Tofu"), "Other")
        XCTAssertEqual(MeatCatalog.category(for: "Hamburger helper"), "Other")
        XCTAssertEqual(MeatCatalog.category(for: "  "), "Uncategorized")
        var c = all[2]; c.category = "Other"
        XCTAssertEqual(c.effectiveCategory, "Other")
    }
    func testTags() {
        XCTAssertEqual(Tags.add("A", "a, b"), "A, b")
        XCTAssertEqual(Tags.add("", "competition", known: ["Competition"]), "Competition")
        XCTAssertEqual(Tags.remove("A, b", "a"), "b")
        XCTAssertEqual(Tags.parse(" , ,"), [])
    }
    func testFilters() {
        XCTAssertEqual(ids(CookQuery()), ["4", "3", "2", "1"])
        XCTAssertEqual(ids(CookQuery(categories: ["Beef", "Pork"])), ["2", "1"])
        XCTAssertEqual(ids(CookQuery(tags: ["COMPETITION"])), ["2", "1"])
        XCTAssertEqual(ids(CookQuery(tags: ["Competition", "Thanksgiving"])), ["1"])
        XCTAssertEqual(ids(CookQuery(categories: ["Pork"], tags: ["Competition"])), ["2"])
    }
    func testSearch() {
        XCTAssertEqual(ids(CookQuery(text: "brisket spg")), ["1"])
        XCTAssertEqual(ids(CookQuery(text: " CHERRY  foil ")), ["2"])
        XCTAssertEqual(ids(CookQuery(text: "brisket cherry")), [])
        XCTAssertEqual(ids(CookQuery(text: "thanksgiving")), ["1"])
        XCTAssertEqual(ids(CookQuery(text: "jalapeno")), ["3"])
        XCTAssertEqual(ids(CookQuery(text: "cider")), ["1"])
        XCTAssertEqual(ids(CookQuery(text: "smoke ring")), ["2"])
        XCTAssertEqual(ids(CookQuery(text: "(*[")), [])
    }
    func testSnippets() {
        func snip(_ t: String) -> String? { CookQuery(text: t).run(all)[0].hits[0].snippet }
        XCTAssertNil(snip("flat"))
        XCTAssertEqual(snip("spg"), "Seasoning: SPG")
        XCTAssertEqual(snip("jalapeno"), "Result: Jalapeño rub was great")
        let long = cook(9, "Brisket") { $0.resultNotes = String(repeating: "x ", count: 100) + "tallow" + String(repeating: " y", count: 100) }
        let s = CookQuery(text: "tallow").run([long])[0].hits[0].snippet!
        XCTAssertTrue(s.hasPrefix("Result: …") && s.hasSuffix("…") && s.contains("tallow") && s.count < 110, s)
    }
    func testSortAndGroup() {
        XCTAssertEqual(ids(CookQuery(sort: .oldest)), ["1", "2", "3", "4"])
        XCTAssertEqual(ids(CookQuery(sort: .rating)), ["2", "1", "4", "3"])
        XCTAssertEqual(ids(CookQuery(sort: .longest)), ["1", "2", "4", "3"])
        XCTAssertEqual(CookQuery(group: .category).run(all).map(\.title), ["Beef", "Pork", "Lamb & game", "Uncategorized"])
        XCTAssertEqual(CookQuery(group: .meat).run(all).map(\.title), ["Brisket", "Elk roast", "Pork ribs", "No meat chosen"])
        let two = [cook(1, "Brisket", day: 20), cook(2, "Brisket", day: 60)]
        XCTAssertEqual(CookQuery(group: .month).run(two).first?.hits.first?.cook.title, "2")
        XCTAssertEqual(CookQuery(sort: .oldest, group: .month).run(two).first?.hits.first?.cook.title, "1")
        XCTAssertTrue(CookQuery(text: "zzz", group: .category).run(all).isEmpty)
    }
    func testSharingKeepsTagsAndCategory() throws {
        var c = all[0]; c.category = "Other"
        let back = try CookShare.importCook(from: CookShare.fileData(c))
        XCTAssertEqual(back.tagList, ["Competition", "Thanksgiving"])
        XCTAssertEqual(back.category, "Other")
        XCTAssertTrue(CookShare.summary(c).contains("Tags: Competition, Thanksgiving"))
    }
    func testCooksSavedBeforeTagsStillLoad() throws {
        var json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(all[0])) as! [String: Any]
        json["tags"] = nil; json["category"] = nil; json["photos"] = nil
        let c = try JSONDecoder().decode(Cook.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(c.tagList, [])
        XCTAssertEqual(c.effectiveCategory, "Beef")
    }
}
