import Foundation

/// Common smoked meats, grouped by category, each with its usual cuts / styles (same list as Android).
enum MeatCatalog {
    struct Meat: Hashable { let name: String; var cuts: [String] = [] }
    struct Category: Hashable { let name: String; let meats: [Meat] }

    static let categories: [Category] = [
        Category(name: "Beef", meats: [
            Meat(name: "Brisket", cuts: ["Whole packer", "Flat", "Point", "Point – burnt ends"]),
            Meat(name: "Beef ribs", cuts: ["Plate short ribs", "Chuck short ribs", "Back ribs"]),
            Meat(name: "Chuck roast", cuts: ["Whole", "Burnt ends (poor man's)"]),
            Meat(name: "Tri-tip", cuts: ["Whole", "Reverse sear"]),
            Meat(name: "Prime rib / ribeye roast", cuts: ["Bone-in", "Boneless"]),
            Meat(name: "Beef tenderloin", cuts: ["Whole", "Center cut"]),
            Meat(name: "Picanha", cuts: ["Whole", "Steaks"]),
            Meat(name: "Pastrami", cuts: ["Brisket flat", "Navel / plate"]),
            Meat(name: "Beef cheeks"),
            Meat(name: "Beef jerky", cuts: ["Eye of round", "Top round", "Flank"]),
        ]),
        Category(name: "Pork", meats: [
            Meat(name: "Pork butt / shoulder", cuts: ["Bone-in butt", "Boneless butt", "Picnic", "Whole shoulder"]),
            Meat(name: "Pork ribs", cuts: ["Spare ribs", "St. Louis", "Baby back", "Country-style"]),
            Meat(name: "Pork belly", cuts: ["Whole slab", "Burnt ends", "Bacon (cured)"]),
            Meat(name: "Pork loin", cuts: ["Bone-in", "Boneless"]),
            Meat(name: "Pork tenderloin"),
            Meat(name: "Ham", cuts: ["Fresh", "Cured / double-smoked", "Spiral"]),
            Meat(name: "Sausage", cuts: ["Fresh links", "Cured / summer sausage", "Fattie"]),
            Meat(name: "Pork chops", cuts: ["Bone-in", "Boneless"]),
            Meat(name: "Whole hog"),
        ]),
        Category(name: "Poultry", meats: [
            Meat(name: "Whole chicken", cuts: ["Whole", "Spatchcocked", "Beer can", "Halves"]),
            Meat(name: "Chicken wings", cuts: ["Whole", "Flats & drums"]),
            Meat(name: "Chicken thighs", cuts: ["Bone-in skin-on", "Boneless"]),
            Meat(name: "Chicken drumsticks"),
            Meat(name: "Chicken breast", cuts: ["Bone-in", "Boneless"]),
            Meat(name: "Whole turkey", cuts: ["Whole", "Spatchcocked"]),
            Meat(name: "Turkey breast", cuts: ["Bone-in", "Boneless"]),
            Meat(name: "Turkey legs"),
            Meat(name: "Duck", cuts: ["Whole", "Breast"]),
        ]),
        Category(name: "Lamb & game", meats: [
            Meat(name: "Leg of lamb", cuts: ["Bone-in", "Boneless"]),
            Meat(name: "Lamb shoulder", cuts: ["Bone-in", "Boneless"]),
            Meat(name: "Rack of lamb"),
            Meat(name: "Venison", cuts: ["Backstrap", "Roast", "Jerky", "Sausage"]),
        ]),
        Category(name: "Seafood", meats: [
            Meat(name: "Salmon", cuts: ["Fillet", "Whole side", "Hot-smoked", "Cold-smoked"]),
            Meat(name: "Trout", cuts: ["Whole", "Fillet"]),
            Meat(name: "Shrimp"),
            Meat(name: "Other fish"),
        ]),
        Category(name: "Other", meats: [Meat(name: "Meatloaf"), Meat(name: "Meatballs")]),
    ]

    static let allMeats: [Meat] = categories.flatMap(\.meats)

    static func find(_ name: String) -> Meat? {
        let n = name.trimmingCharacters(in: .whitespaces)
        return allMeats.first { $0.name.caseInsensitiveCompare(n) == .orderedSame }
    }

    static let other = "Other"
    static let uncategorized = "Uncategorized"
    static let categoryNames: [String] = categories.map(\.name)

    /// Hints for meats typed by hand ("Beef brisket", "Elk roast"); matched on whole words, first hit wins.
    private static let keywords: [(String, Set<String>)] = [
        ("Poultry", ["chicken", "turkey", "duck", "goose", "hen", "quail", "wing", "wings", "drumstick", "drumsticks", "poultry"]),
        ("Seafood", ["salmon", "trout", "fish", "shrimp", "prawn", "prawns", "oyster", "oysters", "lobster", "crab", "tuna", "cod", "mackerel", "scallops", "seafood"]),
        ("Lamb & game", ["lamb", "mutton", "goat", "venison", "deer", "elk", "bison", "boar", "rabbit", "moose", "antelope", "game"]),
        ("Pork", ["pork", "ham", "bacon", "sausage", "sausages", "hog", "pig", "butt", "spareribs", "bratwurst", "brats"]),
        ("Beef", ["beef", "brisket", "chuck", "steak", "ribeye", "tri-tip", "tritip", "picanha", "pastrami", "sirloin", "wagyu", "jerky"]),
    ]

    /// Category for a meat: from the catalog, else guessed from the words, else Other / Uncategorized.
    static func category(for meat: String) -> String {
        let name = meat.trimmingCharacters(in: .whitespaces).lowercased()
        if name.isEmpty { return uncategorized }
        if let c = categories.first(where: { $0.meats.contains { $0.name.lowercased() == name } }) { return c.name }
        let words = Set(name.split { !($0.isASCII && $0.isLetter) && $0 != "-" }.map(String.init))
        return keywords.first { !$0.1.isDisjoint(with: words) }?.0 ?? other
    }

    static func describe() -> String {
        allMeats.map { m in "- \(m.name)" + (m.cuts.isEmpty ? "" : " (cuts: \(m.cuts.joined(separator: "; ")))") }
            .joined(separator: "\n")
    }
}
