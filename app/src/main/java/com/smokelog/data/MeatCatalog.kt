package com.smokelog.data

/** Common smoked meats, grouped by category, each with its usual cuts / styles. */
object MeatCatalog {
    data class Meat(val name: String, val cuts: List<String> = emptyList())
    data class Category(val name: String, val meats: List<Meat>)

    val categories = listOf(
        Category("Beef", listOf(
            Meat("Brisket", listOf("Whole packer", "Flat", "Point", "Point – burnt ends")),
            Meat("Beef ribs", listOf("Plate short ribs", "Chuck short ribs", "Back ribs")),
            Meat("Chuck roast", listOf("Whole", "Burnt ends (poor man's)")),
            Meat("Tri-tip", listOf("Whole", "Reverse sear")),
            Meat("Prime rib / ribeye roast", listOf("Bone-in", "Boneless")),
            Meat("Beef tenderloin", listOf("Whole", "Center cut")),
            Meat("Picanha", listOf("Whole", "Steaks")),
            Meat("Pastrami", listOf("Brisket flat", "Navel / plate")),
            Meat("Beef cheeks"),
            Meat("Beef jerky", listOf("Eye of round", "Top round", "Flank")),
        )),
        Category("Pork", listOf(
            Meat("Pork butt / shoulder", listOf("Bone-in butt", "Boneless butt", "Picnic", "Whole shoulder")),
            Meat("Pork ribs", listOf("Spare ribs", "St. Louis", "Baby back", "Country-style")),
            Meat("Pork belly", listOf("Whole slab", "Burnt ends", "Bacon (cured)")),
            Meat("Pork loin", listOf("Bone-in", "Boneless")),
            Meat("Pork tenderloin"),
            Meat("Ham", listOf("Fresh", "Cured / double-smoked", "Spiral")),
            Meat("Sausage", listOf("Fresh links", "Cured / summer sausage", "Fattie")),
            Meat("Pork chops", listOf("Bone-in", "Boneless")),
            Meat("Whole hog"),
        )),
        Category("Poultry", listOf(
            Meat("Whole chicken", listOf("Whole", "Spatchcocked", "Beer can", "Halves")),
            Meat("Chicken wings", listOf("Whole", "Flats & drums")),
            Meat("Chicken thighs", listOf("Bone-in skin-on", "Boneless")),
            Meat("Chicken drumsticks"),
            Meat("Chicken breast", listOf("Bone-in", "Boneless")),
            Meat("Whole turkey", listOf("Whole", "Spatchcocked")),
            Meat("Turkey breast", listOf("Bone-in", "Boneless")),
            Meat("Turkey legs"),
            Meat("Duck", listOf("Whole", "Breast")),
        )),
        Category("Lamb & game", listOf(
            Meat("Leg of lamb", listOf("Bone-in", "Boneless")),
            Meat("Lamb shoulder", listOf("Bone-in", "Boneless")),
            Meat("Rack of lamb"),
            Meat("Venison", listOf("Backstrap", "Roast", "Jerky", "Sausage")),
        )),
        Category("Seafood", listOf(
            Meat("Salmon", listOf("Fillet", "Whole side", "Hot-smoked", "Cold-smoked")),
            Meat("Trout", listOf("Whole", "Fillet")),
            Meat("Shrimp"),
            Meat("Other fish"),
        )),
        Category("Other", listOf(
            Meat("Meatloaf"),
            Meat("Meatballs"),
        )),
    )

    val allMeats: List<Meat> = categories.flatMap { it.meats }

    fun find(name: String): Meat? = allMeats.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    const val OTHER = "Other"
    const val UNCATEGORIZED = "Uncategorized"
    val categoryNames: List<String> = categories.map { it.name }

    /** Hints for meats typed by hand ("Beef brisket", "Elk roast"); matched on whole words, first hit wins. */
    private val keywords: List<Pair<String, List<String>>> = listOf(
        "Poultry" to listOf("chicken", "turkey", "duck", "goose", "hen", "quail", "wing", "wings", "drumstick", "drumsticks", "poultry"),
        "Seafood" to listOf("salmon", "trout", "fish", "shrimp", "prawn", "prawns", "oyster", "oysters", "lobster", "crab", "tuna", "cod", "mackerel", "scallops", "seafood"),
        "Lamb & game" to listOf("lamb", "mutton", "goat", "venison", "deer", "elk", "bison", "boar", "rabbit", "moose", "antelope", "game"),
        "Pork" to listOf("pork", "ham", "bacon", "sausage", "sausages", "hog", "pig", "butt", "spareribs", "bratwurst", "brats"),
        "Beef" to listOf("beef", "brisket", "chuck", "steak", "ribeye", "tri-tip", "tritip", "picanha", "pastrami", "sirloin", "wagyu", "jerky"),
    )

    /** Category for a meat: from the catalog, else guessed from the words, else Other / Uncategorized. */
    fun categoryFor(meat: String): String {
        val name = meat.trim()
        if (name.isEmpty()) return UNCATEGORIZED
        categories.firstOrNull { c -> c.meats.any { it.name.equals(name, ignoreCase = true) } }?.let { return it.name }
        val words = name.lowercase().split(Regex("[^a-z-]+")).toSet()
        return keywords.firstOrNull { (_, hints) -> hints.any { it in words } }?.first ?: OTHER
    }

    /** Catalog text for the AI prompt. */
    fun describe(): String = allMeats.joinToString("\n") { m ->
        "- ${m.name}" + if (m.cuts.isNotEmpty()) " (cuts: ${m.cuts.joinToString("; ")})" else ""
    }

    /**
     * Usual pull temperature (°F) for a meat, used for the finish-time forecast when the grill
     * has no probe target. Null when there isn't a single sensible number (jerky, whole hog…).
     */
    fun typicalDoneF(meat: String, cut: String = ""): Double? {
        val m = meat.trim().lowercase(); val c = cut.trim().lowercase()
        return when (m) {
            "brisket", "beef ribs", "chuck roast", "pastrami" -> 203.0
            "beef cheeks" -> 205.0
            "tri-tip", "picanha" -> 135.0
            "prime rib / ribeye roast", "beef tenderloin" -> 130.0
            "pork butt / shoulder" -> 203.0
            "pork ribs" -> 198.0
            "pork belly" -> if ("bacon" in c) 150.0 else 200.0
            "pork loin", "pork tenderloin", "pork chops" -> 145.0
            "ham" -> 140.0
            "sausage" -> 160.0
            "whole chicken", "chicken breast", "whole turkey", "turkey breast" -> 165.0
            "chicken thighs", "chicken drumsticks", "chicken wings", "turkey legs" -> 175.0
            "duck" -> if ("breast" in c) 135.0 else 165.0
            "leg of lamb", "rack of lamb" -> 135.0
            "lamb shoulder" -> 200.0
            "venison" -> if ("backstrap" in c) 130.0 else null
            "salmon", "trout", "other fish" -> 145.0
            "meatloaf", "meatballs" -> 160.0
            else -> when (categoryFor(m)) {
                "Poultry" -> 165.0
                else -> null
            }
        }
    }
}
