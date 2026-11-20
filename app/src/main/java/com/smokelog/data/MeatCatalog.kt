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

    /** Catalog text for the AI prompt. */
    fun describe(): String = allMeats.joinToString("\n") { m ->
        "- ${m.name}" + if (m.cuts.isNotEmpty()) " (cuts: ${m.cuts.joinToString("; ")})" else ""
    }
}
