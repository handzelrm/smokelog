package com.smokelog

import com.smokelog.data.*
import org.junit.Assert.*
import org.junit.Test

class CookQueryTest {
    private val day = 86_400_000L
    private fun cook(id: Long, meat: String, start: Long = id * day, f: (Cook) -> Cook = { it }) =
        f(Cook(id = id, meat = meat, createdAt = start, startTime = start))

    private val brisket = cook(1, "Brisket") { it.copy(cut = "Flat", seasoning = "SPG", tags = "Competition, Thanksgiving", rating = 4, offTime = it.startTime!! + 12 * 3_600_000) }
    private val ribs = cook(2, "Pork ribs") { it.copy(fuel = "Cherry", wrapMethod = WrapMethod.FOIL, tags = "competition", rating = 5, offTime = it.startTime!! + 6 * 3_600_000) }
    private val elk = cook(3, "Elk roast") { it.copy(resultNotes = "Jalapeño rub was great") }
    private val blank = cook(4, "")
    private val all = listOf(brisket, ribs, elk, blank)
    private fun ids(q: CookQuery, e: Map<Long, List<CookEvent>> = emptyMap(), p: Map<Long, List<CookPhoto>> = emptyMap()) =
        q.run(all, e, p).flatMap { s -> s.hits.map { it.cook.id } }

    @Test fun everyCatalogMeatKeepsItsOwnCategory() {
        MeatCatalog.categories.forEach { c -> c.meats.forEach { assertEquals(it.name, c.name, MeatCatalog.categoryFor(it.name)) } }
    }
    @Test fun typedMeatsAreGuessed() {
        assertEquals("Beef", MeatCatalog.categoryFor("Beef brisket"))
        assertEquals("Lamb & game", MeatCatalog.categoryFor("elk roast"))
        assertEquals("Poultry", MeatCatalog.categoryFor("Turkey sausage"))
        assertEquals("Pork", MeatCatalog.categoryFor("Boston butt"))
        assertEquals("Seafood", MeatCatalog.categoryFor("smoked tuna"))
        assertEquals("Other", MeatCatalog.categoryFor("Tofu"))
        assertEquals("Other", MeatCatalog.categoryFor("Hamburger helper"))   // "ham" is not a whole word here
        assertEquals("Uncategorized", MeatCatalog.categoryFor("  "))
    }
    @Test fun handPickedCategoryWins() = assertEquals("Other", elk.copy(category = "Other").effectiveCategory)
    @Test fun tagsIgnoreCaseAndReuseSpelling() {
        assertEquals("A, b", Tags.add("A", "a, b"))
        assertEquals("Competition", Tags.add("", "competition", listOf("Competition")))
        assertEquals("b", Tags.remove("A, b", "a"))
        assertEquals(emptyList<String>(), Tags.parse(" , ,"))
    }
    @Test fun noFiltersShowsAllNewestFirst() = assertEquals(listOf(4L, 3L, 2L, 1L), ids(CookQuery()))
    @Test fun categoriesCombineWithOr() = assertEquals(listOf(2L, 1L), ids(CookQuery(categories = setOf("Beef", "Pork"))))
    @Test fun tagsCombineWithAnd() {
        assertEquals(listOf(2L, 1L), ids(CookQuery(tags = setOf("COMPETITION"))))
        assertEquals(listOf(1L), ids(CookQuery(tags = setOf("Competition", "Thanksgiving"))))
        assertEquals(listOf(2L), ids(CookQuery(tags = setOf("Competition"), categories = setOf("Pork"))))
    }
    @Test fun searchNeedsEveryWordAnywhere() {
        assertEquals(listOf(1L), ids(CookQuery(text = "brisket spg")))
        assertEquals(listOf(2L), ids(CookQuery(text = " CHERRY  foil ")))
        assertEquals(emptyList<Long>(), ids(CookQuery(text = "brisket cherry")))
        assertEquals(listOf(1L), ids(CookQuery(text = "thanksgiving")))
        assertEquals(listOf(2L), ids(CookQuery(text = "pork")))
    }
    @Test fun searchIgnoresAccents() = assertEquals(listOf(3L), ids(CookQuery(text = "jalapeno")))
    @Test fun searchCoversTimelineAndPhotos() {
        val e = mapOf(1L to listOf(CookEvent(cookId = 1, time = 0, type = EventType.SPRITZ, note = "apple cider")))
        val p = mapOf(2L to listOf(CookPhoto(cookId = 2, time = 0, fileName = "x", caption = "Smoke ring")))
        assertEquals(listOf(1L), ids(CookQuery(text = "cider"), e, p))
        assertEquals(listOf(2L), ids(CookQuery(text = "smoke ring"), e, p))
    }
    @Test fun snippetSaysWhereItMatched() {
        fun snip(t: String) = CookQuery(text = t).run(all).single().hits.single().snippet
        assertNull(snip("flat"))
        assertEquals("Seasoning: SPG", snip("spg"))
        assertEquals("Result: Jalapeño rub was great", snip("jalapeno"))
    }
    @Test fun longSnippetsAreCutAroundTheWord() {
        val c = cook(9, "Brisket") { it.copy(resultNotes = "x ".repeat(100) + "tallow" + " y".repeat(100)) }
        val s = CookQuery(text = "tallow").run(listOf(c)).single().hits.single().snippet!!
        assertTrue(s, s.startsWith("Result: …") && s.endsWith("…") && "tallow" in s && s.length < 110)
    }
    @Test fun regexCharactersAreJustText() = assertEquals(emptyList<Long>(), ids(CookQuery(text = "(*[")))
    @Test fun sorts() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(CookQuery(sort = CookSort.OLDEST)))
        assertEquals(listOf(2L, 1L, 4L, 3L), ids(CookQuery(sort = CookSort.RATING)))
        assertEquals(listOf(1L, 2L, 4L, 3L), ids(CookQuery(sort = CookSort.LONGEST)))
        assertEquals(listOf(1L, 3L, 2L, 4L), ids(CookQuery(sort = CookSort.NAME)))
    }
    @Test fun groups() {
        fun titles(q: CookQuery) = q.run(all).map { it.title }
        assertEquals(listOf("Beef", "Pork", "Lamb & game", "Uncategorized"), titles(CookQuery(group = CookGroup.CATEGORY)))
        assertEquals(listOf("Brisket", "Elk roast", "Pork ribs", "No meat chosen"), titles(CookQuery(group = CookGroup.MEAT)))
        val two = listOf(cook(1, "Brisket", 20 * day), cook(2, "Brisket", 60 * day))
        assertEquals(2L, CookQuery(group = CookGroup.MONTH).run(two).first().hits.single().cook.id)
        assertEquals(1L, CookQuery(group = CookGroup.MONTH, sort = CookSort.OLDEST).run(two).first().hits.single().cook.id)
    }
    @Test fun emptyResultHasNoSections() = assertTrue(CookQuery(text = "zzz", group = CookGroup.CATEGORY).run(all).isEmpty())
}
