package com.smokelog.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.smokelog.data.Cook
import com.smokelog.data.CookDao
import com.smokelog.data.CookEvent
import com.smokelog.data.CookPhoto
import com.smokelog.data.PhotoStore
import com.smokelog.data.Fmt
import com.smokelog.data.TempReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** File format for sharing a cook with another SmokeLog user. */
@Serializable
data class SharedCook(
    val format: String = FORMAT,
    val version: Int = 1,
    val cook: Cook,
    val readings: List<TempReading>,
    val events: List<CookEvent>,
    val photos: List<SharedPhoto> = emptyList(),
) {
    companion object { const val FORMAT = "smokelog.cook" }
}

/** A cook photo embedded in a shared file (JPEG, base64). Older app versions ignore it. */
@Serializable
data class SharedPhoto(val time: Long, val caption: String = "", val jpegBase64: String)

object CookShare {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** Human-readable recap anyone can read (text message, email, forum post). */
    fun summary(cook: Cook, readings: List<TempReading>, events: List<CookEvent>): String = buildString {
        appendLine("🔥 ${cook.displayTitle}")
        if (cook.startTime != null) appendLine(Fmt.date(cook.startTime))
        val meat = listOfNotNull(
            cook.meat.ifBlank { null }, cook.cut.ifBlank { null },
            cook.weightLb?.let { "${Fmt.num(it)} lb" },
        ).joinToString(", ")
        if (meat.isNotBlank()) appendLine(meat)
        appendLine()

        appendLine("SETUP")
        line("Smoker", listOf(cook.smoker, cook.smokerTempF?.let { "${it}°F" } ?: "").filter { it.isNotBlank() }.joinToString(" @ "))
        line("Fuel / wood", cook.fuel)
        line("Placement", listOfNotNull(cook.placement?.label, cook.placementNotes.ifBlank { null }).joinToString(" – "))
        line("Fat cap", cook.fatCap?.label)
        line("Water pan", cook.waterPan?.let { if (it) "Yes" + cook.waterPanNotes.prefixed() else "No" })
        appendLine()

        appendLine("PREP")
        line("Trim", cook.trimNotes)
        line("Binder", cook.binder)
        line("Seasoning", cook.seasoning + (cook.seasonedHoursBefore?.let { " (${Fmt.num(it)} h before)" } ?: ""))
        line("Dry brine", cook.dryBrine + (cook.dryBrineHours?.let { " (${Fmt.num(it)} h)" } ?: ""))
        line("Wet brine", cook.wetBrine + (cook.wetBrineHours?.let { " (${Fmt.num(it)} h)" } ?: ""))
        line("Injection", cook.injection + (cook.injectionHoursBefore?.let { " (${Fmt.num(it)} h before)" } ?: ""))
        line("Notes", cook.prepNotes)
        appendLine()

        appendLine("COOK")
        line("On", cook.startTime?.let { Fmt.dateTime(it) + (cook.startInternalF?.let { t -> " at ${t}°F internal" } ?: "") })
        if (cook.wrapMethod != null && cook.wrapMethod != com.smokelog.data.WrapMethod.NONE) {
            line("Wrapped", listOfNotNull(
                cook.wrapMethod.label,
                cook.wrapInternalF?.let { "at ${it}°F" },
                cook.wrapElapsedMinutes?.let { "after ${Fmt.duration(it)}" },
                cook.wrapNotes.ifBlank { null },
            ).joinToString(", "))
        } else if (cook.wrapMethod == com.smokelog.data.WrapMethod.NONE) line("Wrapped", "No (naked)")
        line("Off", cook.offTime?.let { Fmt.dateTime(it) + (cook.offInternalF?.let { t -> " at ${t}°F internal" } ?: "") })
        line("Total cook", cook.cookMinutes?.let { Fmt.duration(it) })
        line("Rest", listOfNotNull(
            cook.effectiveRestMinutes?.let { Fmt.duration(it.toLong()) },
            cook.restLocation?.label, cook.restNotes.ifBlank { null },
        ).joinToString(", "))

        val pit = readings.mapNotNull { it.grillF }
        if (pit.isNotEmpty()) {
            line("Pit temp", "avg ${Fmt.temp(pit.average())} (range ${Fmt.temp(pit.min())}–${Fmt.temp(pit.max())}), ${readings.size} readings")
        }
        if (events.isNotEmpty()) {
            appendLine()
            appendLine("TIMELINE")
            events.forEach { appendLine("• ${Fmt.time(it.time)} ${it.type.label}${it.note.prefixed()}") }
        }
        if (cook.rating != null || cook.resultNotes.isNotBlank()) {
            appendLine()
            appendLine("RESULT")
            line("Rating", cook.rating?.let { "★".repeat(it) + "☆".repeat(5 - it) })
            line("Notes", cook.resultNotes)
        }
        if (cook.nextCookNotes.isNotBlank()) {
            appendLine()
            appendLine("NEXT TIME")
            appendLine(cook.nextCookNotes.trim())
        }
        appendLine()
        append("Logged with SmokeLog")
    }

    private fun StringBuilder.line(label: String, value: String?) {
        if (!value.isNullOrBlank()) appendLine("$label: ${value.trim()}")
    }
    private fun String.prefixed() = if (isBlank()) "" else " – $this"

    fun toJson(cook: Cook, readings: List<TempReading>, events: List<CookEvent>, photos: List<SharedPhoto> = emptyList()): String =
        json.encodeToString(SharedCook(cook = cook.copy(traegerThing = null), readings = readings, events = events, photos = photos))

    private fun embed(photos: List<CookPhoto>, store: PhotoStore): List<SharedPhoto> = photos.mapNotNull { p ->
        val f = store.file(p.fileName)
        if (!f.exists()) null else SharedPhoto(p.time, p.caption, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
    }

    /** Text recap, with the cook's photos attached when there are any. */
    fun shareText(
        context: Context, cook: Cook, readings: List<TempReading>, events: List<CookEvent>,
        photos: List<CookPhoto> = emptyList(), store: PhotoStore? = null,
    ) {
        val text = summary(cook, readings, events)
        val uris = if (store == null) emptyList() else photos.filter { store.file(it.fileName).exists() }.map { store.shareUri(it.fileName) }
        val send = if (uris.isEmpty()) {
            Intent(Intent.ACTION_SEND).setType("text/plain")
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
            .putExtra(Intent.EXTRA_SUBJECT, cook.displayTitle)
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share cook summary"))
    }

    /** Shares the full cook (every temperature reading and photo) as a file another SmokeLog can import. */
    suspend fun shareFile(
        context: Context, cook: Cook, readings: List<TempReading>, events: List<CookEvent>,
        photos: List<CookPhoto> = emptyList(), store: PhotoStore? = null,
    ) {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val safe = cook.displayTitle.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40).ifBlank { "cook" }
            val file = File(dir, "$safe.smokelog.json")
            file.writeText(toJson(cook, readings, events, if (store == null) emptyList() else embed(photos, store)))
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, cook.displayTitle)
            .putExtra(Intent.EXTRA_TEXT, summary(cook, readings, events))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share cook file"))
    }

    /** Imports a shared cook file; returns the new cook id. */
    suspend fun import(context: Context, uri: Uri, dao: CookDao): Long = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Couldn't open the shared file")
        val shared = json.decodeFromString<SharedCook>(text)
        require(shared.format == SharedCook.FORMAT) { "Not a SmokeLog cook file" }
        val store = (context.applicationContext as com.smokelog.SmokeLogApp).photos
        val photos = shared.photos.mapNotNull { p ->
            runCatching { CookPhoto(cookId = 0, time = p.time, caption = p.caption,
                fileName = store.importBytes(Base64.decode(p.jpegBase64, Base64.DEFAULT))) }.getOrNull()
        }
        dao.importCook(shared.cook.copy(title = shared.cook.displayTitle + " (shared)"), shared.readings, shared.events, photos)
    }
}
