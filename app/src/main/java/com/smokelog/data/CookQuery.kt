package com.smokelog.data

import java.text.Normalizer
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The person's own labels on a cook, stored as a comma-separated string. */
object Tags {
    fun parse(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** Adds what was typed (several if separated by commas), reusing the spelling of tags that already exist. */
    fun add(current: String, typed: String, known: Collection<String> = emptyList()): String {
        val new = parse(typed).map { t -> known.firstOrNull { it.equals(t, ignoreCase = true) } ?: t }
        return (parse(current) + new).distinctBy { it.lowercase() }.joinToString(", ")
    }

    fun remove(current: String, tag: String): String = parse(current).filterNot { it.equals(tag, ignoreCase = true) }.joinToString(", ")
}

enum class CookSort(val label: String) {
    NEWEST("Newest first"), OLDEST("Oldest first"), RATING("Highest rated"), LONGEST("Longest cook"), NAME("Name (A–Z)")
}

enum class CookGroup(val label: String) { NONE("No grouping"), CATEGORY("Category"), MEAT("Meat"), MONTH("Month") }

/** A cook in the list; [snippet] says where a search matched when the title doesn't show it. */
data class CookHit(val cook: Cook, val snippet: String? = null)
data class CookSection(val title: String?, val hits: List<CookHit>)

/** What the list is showing: search text, filters, order and grouping. Pure logic, no Android. */
data class CookQuery(
    val text: String = "",
    val categories: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val sort: CookSort = CookSort.NEWEST,
    val group: CookGroup = CookGroup.NONE,
) {
    val isFiltering: Boolean get() = text.isNotBlank() || categories.isNotEmpty() || tags.isNotEmpty()

    fun run(cooks: List<Cook>, events: Map<Long, List<CookEvent>> = emptyMap(), photos: Map<Long, List<CookPhoto>> = emptyMap()): List<CookSection> {
        val words = fold(text).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wantedTags = tags.map { it.lowercase() }
        val hits = cooks.mapNotNull { c ->
            if (categories.isNotEmpty() && c.effectiveCategory !in categories) return@mapNotNull null
            val own = c.tagList.map { it.lowercase() }
            if (!own.containsAll(wantedTags)) return@mapNotNull null
            if (words.isEmpty()) CookHit(c) else match(c, words, events[c.id].orEmpty(), photos[c.id].orEmpty())
        }
        val byDate = compareByDescending<CookHit> { it.cook.startTime ?: it.cook.createdAt }
        val order: Comparator<CookHit> = when (sort) {
            CookSort.NEWEST -> byDate
            CookSort.OLDEST -> byDate.reversed()
            CookSort.RATING -> compareByDescending<CookHit> { it.cook.rating ?: 0 }.then(byDate)
            CookSort.LONGEST -> compareByDescending<CookHit> { it.cook.cookMinutes ?: -1L }.then(byDate)
            CookSort.NAME -> compareBy<CookHit> { it.cook.displayTitle.lowercase() }.then(byDate)
        }
        val sorted = hits.sortedWith(order)
        if (sorted.isEmpty()) return emptyList()
        return when (group) {
            CookGroup.NONE -> listOf(CookSection(null, sorted))
            CookGroup.CATEGORY -> sorted.groupBy { it.cook.effectiveCategory }.toList()
                .sortedBy { (name, _) -> categoryOrder(name) }.map { CookSection(it.first, it.second) }
            CookGroup.MEAT -> sorted.groupBy { it.cook.meat.trim().lowercase() }.toList()
                .sortedWith(compareBy({ it.first.isEmpty() }, { it.first }))
                .map { (_, h) -> CookSection(h.first().cook.meat.trim().ifEmpty { "No meat chosen" }, h) }
            CookGroup.MONTH -> {
                val months = sorted.groupBy { month(it.cook.startTime ?: it.cook.createdAt) }.toList().sortedByDescending { it.first }
                (if (sort == CookSort.OLDEST) months.reversed() else months).map { CookSection(MONTH.format(it.first), it.second) }
            }
        }
    }

    companion object {
        private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        private fun month(ms: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

        fun categoryOrder(name: String): Int =
            MeatCatalog.categoryNames.indexOf(name).let { if (it >= 0) it else MeatCatalog.categoryNames.size }

        /** Lowercase without accents, so "jalapeno" finds "Jalapeño". */
        fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

        /** Everything searchable about a cook, as label → text. Headline fields (already visible in the row) have a blank label. */
        fun fields(c: Cook, events: List<CookEvent>, photos: List<CookPhoto>): List<Pair<String, String>> = buildList {
            add("" to listOf(c.displayTitle, c.meat, c.cut, c.effectiveCategory, c.tagList.joinToString(" "), Fmt.date(c.startTime ?: c.createdAt)).joinToString(" "))
            add("Smoker" to c.smoker)
            add("Pellets / wood" to c.fuel)
            add("Smoker temp" to (c.smokerTempF?.let { "$it°F" } ?: ""))
            add("Placement" to listOf(c.placement?.label.orEmpty(), c.placementNotes).joinToString(" ").trim())
            add("Fat cap" to (c.fatCap?.takeIf { it != FatCap.NA }?.label ?: ""))
            add("Water pan" to (if (c.waterPan == true) "Water pan ${c.waterPanNotes}".trim() else ""))
            add("Trim" to c.trimNotes)
            add("Binder" to c.binder)
            add("Seasoning" to c.seasoning)
            add("Dry brine" to (if (c.dryBrine.isBlank()) "" else "Dry brine: ${c.dryBrine}"))
            add("Wet brine" to (if (c.wetBrine.isBlank()) "" else "Wet brine: ${c.wetBrine}"))
            add("Injection" to (if (c.injection.isBlank()) "" else "Injection: ${c.injection}"))
            add("Prep notes" to c.prepNotes)
            add("Wrap" to listOf(c.wrapMethod?.let { if (it == WrapMethod.NONE) "Naked" else it.label }.orEmpty(), c.wrapNotes).joinToString(" ").trim())
            add("Rest" to listOf(c.restLocation?.label.orEmpty(), c.restNotes).joinToString(" ").trim())
            add("Result" to c.resultNotes)
            add("Next time" to c.nextCookNotes)
            events.forEach { add("Timeline" to "${it.type.label} ${it.note}".trim()) }
            photos.forEach { add("Photo" to it.caption) }
            add("AI debrief" to c.aiAnalysis)
        }.filter { it.second.isNotBlank() }

        /** Every word must appear somewhere in the cook. */
        private fun match(c: Cook, words: List<String>, events: List<CookEvent>, photos: List<CookPhoto>): CookHit? {
            val fields = fields(c, events, photos).map { (label, text) -> Triple(label, text, fold(text)) }
            if (!words.all { w -> fields.any { w in it.third } }) return null
            val headline = fields.filter { it.first.isEmpty() }.joinToString(" ") { it.third }
            val missing = words.firstOrNull { it !in headline } ?: return CookHit(c)
            val f = fields.first { it.first.isNotEmpty() && missing in it.third }
            val text = f.second.replace(Regex("\\s+"), " ").trim()
            val body = text.removePrefix("${f.first}: ")
            return CookHit(c, "${f.first}: ${excerpt(body, missing)}")
        }

        private fun excerpt(text: String, word: String, around: Int = 36): String {
            if (text.length <= around * 2) return text
            val at = fold(text).indexOf(word).coerceIn(0, text.length)
            val from = (at - around).coerceAtLeast(0)
            val to = (at + word.length + around).coerceAtMost(text.length)
            return (if (from > 0) "…" else "") + text.substring(from, to).trim() + (if (to < text.length) "…" else "")
        }
    }
}
