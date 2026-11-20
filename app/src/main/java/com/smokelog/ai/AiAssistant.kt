package com.smokelog.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.smokelog.data.Cook
import com.smokelog.data.CookEvent
import com.smokelog.data.EventType
import com.smokelog.data.FatCap
import com.smokelog.data.MeatCatalog
import com.smokelog.data.Placement
import com.smokelog.data.RestLocation
import com.smokelog.data.Settings
import com.smokelog.data.TempReading
import com.smokelog.data.WrapMethod
import com.smokelog.share.CookShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AiException(message: String) : Exception(message)

/** Fields Claude may fill from free-form notes. Every field is optional; null = leave unchanged. */
@Serializable
data class CookPatch(
    val title: String? = null, val meat: String? = null, val cut: String? = null, val weightLb: Double? = null,
    val smoker: String? = null, val fuel: String? = null, val smokerTempF: Int? = null,
    val placement: String? = null, val placementNotes: String? = null, val fatCap: String? = null,
    val waterPan: Boolean? = null, val waterPanNotes: String? = null,
    val trimNotes: String? = null, val binder: String? = null, val seasoning: String? = null,
    val seasonedHoursBefore: Double? = null, val dryBrine: String? = null, val dryBrineHours: Double? = null,
    val wetBrine: String? = null, val wetBrineHours: Double? = null, val injection: String? = null,
    val injectionHoursBefore: Double? = null, val prepNotes: String? = null,
    val startTime: String? = null, val startInternalF: Int? = null,
    val wrapTime: String? = null, val wrapInternalF: Int? = null, val wrapMethod: String? = null, val wrapNotes: String? = null,
    val offTime: String? = null, val offInternalF: Int? = null,
    val restLocation: String? = null, val restMinutes: Int? = null, val restNotes: String? = null,
    val rating: Int? = null, val resultNotes: String? = null, val nextCookNotes: String? = null,
    val events: List<PatchEvent> = emptyList(),
    val readings: List<PatchReading> = emptyList(),
    val summary: String? = null,
)

@Serializable data class PatchEvent(val time: String, val type: String, val note: String = "")
@Serializable data class PatchReading(val time: String, val grillF: Double? = null, val probeF: Double? = null)

class AiAssistant(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    private fun client(): AnthropicClient {
        val key = settings.state.value.anthropicKey
        if (key.isBlank()) throw AiException("Add an Anthropic API key in Settings to use the AI assistant")
        if (client == null || clientKey != key) {
            client = AnthropicOkHttpClient.builder().apiKey(key).build()
            clientKey = key
        }
        return client!!
    }

    private suspend fun ask(system: String, user: String, effort: OutputConfig.Effort?): String = withContext(Dispatchers.IO) {
        val builder = MessageCreateParams.builder()
            .model(settings.state.value.aiModel)
            .maxTokens(16000L)
            .system(system)
            .addUserMessage(user)
            // Server-side fallback if a request is ever declined by a safety classifier.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        if (effort != null) builder.outputConfig(OutputConfig.builder().effort(effort).build())
        val msg = try {
            client().messages().create(builder.build())
        } catch (e: com.anthropic.errors.UnauthorizedException) {
            throw AiException("The Anthropic API key was rejected – check it in Settings")
        } catch (e: com.anthropic.errors.RateLimitException) {
            throw AiException("Rate limited by the AI service – try again in a minute")
        } catch (e: com.anthropic.errors.AnthropicServiceException) {
            throw AiException("AI service error (${e.statusCode()}): ${e.message}")
        } catch (e: com.anthropic.errors.AnthropicIoException) {
            throw AiException("Couldn't reach the AI service – check your connection")
        }
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop == "refusal") throw AiException("The AI declined this request")
        msg.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("").trim()
    }

    // ---------------------------------------------------------------- note parsing

    /**
     * Turns free-form notes ("put a 14 lb prime packer on at 6am, 250, fat side down,
     * wrapped in pink paper at 165 around 1pm…") into structured fields.
     */
    suspend fun parseNotes(notes: String, current: Cook): CookPatch =
        parseReply(ask(PARSE_SYSTEM, parseUserPrompt(notes, current), OutputConfig.Effort.LOW))

    /** One self-contained message for the Claude app (it has no separate system prompt). */
    fun parseRequestForClaudeApp(notes: String, current: Cook): String =
        PARSE_SYSTEM + "\n\n" + parseUserPrompt(notes, current) +
            "\nReply with only the JSON object, in one code block, so it can be copied back into SmokeLog."

    private fun parseUserPrompt(notes: String, current: Cook): String {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone).format(ISO)
        val start = current.startTime?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone).format(ISO) }
        return buildString {
            appendLine("Current local date/time: $now")
            if (start != null) appendLine("Cook start (meat on): $start")
            appendLine("Fields already recorded (don't repeat unless the notes change them):")
            appendLine(CookShare.summary(current, emptyList(), emptyList()))
            appendLine()
            appendLine("<notes>")
            appendLine(notes)
            appendLine("</notes>")
        }
    }

    /** Extracts the JSON fields from a reply – from the API or pasted from the Claude app. */
    fun parseReply(text: String): CookPatch {
        val start0 = text.indexOf('{'); val end0 = text.lastIndexOf('}')
        if (start0 < 0 || end0 <= start0) throw AiException("No fields found in the reply – copy Claude's whole JSON answer")
        val body = text.substring(start0, end0 + 1)
        return runCatching { json.decodeFromString<CookPatch>(body) }
            .getOrElse { throw AiException("Couldn't read the reply as cook fields – try again") }
    }

    fun apply(patch: CookPatch, c: Cook): Triple<Cook, List<CookEvent>, List<TempReading>> {
        fun t(s: String?): Long? = s?.let { v ->
            runCatching { LocalDateTime.parse(v.trim().take(19)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        }
        fun s(new: String?, old: String) = new?.takeIf { it.isNotBlank() } ?: old
        val cook = c.copy(
            title = s(patch.title, c.title), meat = s(patch.meat, c.meat), cut = s(patch.cut, c.cut),
            weightLb = patch.weightLb ?: c.weightLb,
            smoker = s(patch.smoker, c.smoker), fuel = s(patch.fuel, c.fuel), smokerTempF = patch.smokerTempF ?: c.smokerTempF,
            placement = enumOrNull<Placement>(patch.placement) ?: c.placement,
            placementNotes = s(patch.placementNotes, c.placementNotes),
            fatCap = enumOrNull<FatCap>(patch.fatCap) ?: c.fatCap,
            waterPan = patch.waterPan ?: c.waterPan, waterPanNotes = s(patch.waterPanNotes, c.waterPanNotes),
            trimNotes = s(patch.trimNotes, c.trimNotes), binder = s(patch.binder, c.binder),
            seasoning = s(patch.seasoning, c.seasoning), seasonedHoursBefore = patch.seasonedHoursBefore ?: c.seasonedHoursBefore,
            dryBrine = s(patch.dryBrine, c.dryBrine), dryBrineHours = patch.dryBrineHours ?: c.dryBrineHours,
            wetBrine = s(patch.wetBrine, c.wetBrine), wetBrineHours = patch.wetBrineHours ?: c.wetBrineHours,
            injection = s(patch.injection, c.injection), injectionHoursBefore = patch.injectionHoursBefore ?: c.injectionHoursBefore,
            prepNotes = s(patch.prepNotes, c.prepNotes),
            startTime = t(patch.startTime) ?: c.startTime, startInternalF = patch.startInternalF ?: c.startInternalF,
            wrapTime = t(patch.wrapTime) ?: c.wrapTime, wrapInternalF = patch.wrapInternalF ?: c.wrapInternalF,
            wrapMethod = enumOrNull<WrapMethod>(patch.wrapMethod) ?: c.wrapMethod, wrapNotes = s(patch.wrapNotes, c.wrapNotes),
            offTime = t(patch.offTime) ?: c.offTime, offInternalF = patch.offInternalF ?: c.offInternalF,
            restLocation = enumOrNull<RestLocation>(patch.restLocation) ?: c.restLocation,
            restMinutes = patch.restMinutes ?: c.restMinutes, restNotes = s(patch.restNotes, c.restNotes),
            rating = patch.rating?.coerceIn(1, 5) ?: c.rating, resultNotes = s(patch.resultNotes, c.resultNotes),
            nextCookNotes = s(patch.nextCookNotes, c.nextCookNotes),
        )
        val events = patch.events.mapNotNull { e ->
            val time = t(e.time) ?: return@mapNotNull null
            CookEvent(cookId = c.id, time = time, type = enumOrNull<EventType>(e.type) ?: EventType.NOTE, note = e.note)
        }
        val readings = patch.readings.mapNotNull { r ->
            val time = t(r.time) ?: return@mapNotNull null
            if (r.grillF == null && r.probeF == null) null
            else TempReading(cookId = c.id, time = time, grillF = r.grillF, probeF = r.probeF, source = "manual")
        }
        return Triple(cook, events, readings)
    }

    // ---------------------------------------------------------------- analysis

    /** Reviews the cook (and similar past cooks) and answers [question], or gives a general debrief. */
    suspend fun analyze(
        cook: Cook, readings: List<TempReading>, events: List<CookEvent>,
        pastCooks: List<Cook>, question: String?,
    ): String = ask(ANALYZE_SYSTEM, analyzeUserPrompt(cook, readings, events, pastCooks, question), null)

    fun analyzeRequestForClaudeApp(
        cook: Cook, readings: List<TempReading>, events: List<CookEvent>,
        pastCooks: List<Cook>, question: String?,
    ): String = ANALYZE_SYSTEM + "\n\n" + analyzeUserPrompt(cook, readings, events, pastCooks, question)

    private fun analyzeUserPrompt(
        cook: Cook, readings: List<TempReading>, events: List<CookEvent>,
        pastCooks: List<Cook>, question: String?,
    ): String {
        return buildString {
            appendLine("<cook>")
            appendLine(CookShare.summary(cook, emptyList(), events))
            appendLine("</cook>")
            if (readings.isNotEmpty()) {
                appendLine()
                appendLine("<temperature_log minutes_since_start,pit_F,pit_set_F,probe_F,probe2_F>")
                val t0 = cook.startTime ?: readings.first().time
                downsample(readings, 240).forEach { r ->
                    appendLine(listOf(((r.time - t0) / 60_000).toString(), r.grillF.c(), r.grillSetF.c(), r.probeF.c(), r.probe2F.c()).joinToString(","))
                }
                appendLine("</temperature_log>")
            }
            if (pastCooks.isNotEmpty()) {
                appendLine()
                appendLine("<past_cooks_of_similar_meat>")
                pastCooks.forEach { appendLine(CookShare.summary(it, emptyList(), emptyList())); appendLine("---") }
                appendLine("</past_cooks_of_similar_meat>")
            }
            appendLine()
            appendLine(question?.takeIf { it.isNotBlank() } ?: "Give me a debrief of this cook.")
        }
    }

    private fun Double?.c() = this?.let { "%.0f".format(it) } ?: ""

    private fun downsample(r: List<TempReading>, max: Int): List<TempReading> =
        if (r.size <= max) r else { val step = r.size.toDouble() / max; (0 until max).map { r[(it * step).toInt()] } + r.last() }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        name?.let { n -> enumValues<E>().firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }

    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        private fun names(values: Array<out Enum<*>>) = values.joinToString(" | ") { it.name }

        private val PARSE_SYSTEM = """
            You turn a pitmaster's free-form notes about a barbecue/smoking cook into structured data for a cook log.
            The notes may be dictated by voice, so expect run-ons, shorthand ("pink paper" = butcher paper, "IT" = internal temp,
            "butt" = pork shoulder, "packer" = whole brisket, "SPG" = salt/pepper/garlic).

            Reply with ONE JSON object and nothing else. Include only the keys the notes actually give you information about;
            omit everything else. Never invent values. Temperatures are °F (convert °C). Times are local ISO-8601
            "yyyy-MM-ddTHH:mm:ss", resolved against the current date/time and cook start you are given
            (e.g. "wrapped at 1pm" on a cook that started this morning = today 13:00; "5 hours in" = start + 5h).

            Keys:
              title, meat, cut, smoker, fuel, placementNotes, waterPanNotes, trimNotes, binder, seasoning, dryBrine,
              wetBrine, injection, prepNotes, wrapNotes, restNotes, resultNotes, nextCookNotes, summary: string
              (nextCookNotes = things the cook says to do differently or remember next time)
              weightLb, seasonedHoursBefore, dryBrineHours, wetBrineHours, injectionHoursBefore: number
              smokerTempF, startInternalF, wrapInternalF, offInternalF, restMinutes, rating (1-5): integer
              waterPan: boolean
              startTime, wrapTime, offTime: datetime string
              meat / cut: use these exact names when one fits, otherwise free text:
            ${MeatCatalog.describe().prependIndent("                ")}
              placement: one of ${names(Placement.entries.toTypedArray())}
              fatCap: one of ${names(FatCap.entries.toTypedArray())}
              wrapMethod: one of ${names(WrapMethod.entries.toTypedArray())}
              restLocation: one of ${names(RestLocation.entries.toTypedArray())}
              events: array of {time, type, note} for things that happened mid-cook (spritzing, temp changes, flips,
                stall observations...). type is one of ${names(EventType.entries.toTypedArray())}
              readings: array of {time, grillF, probeF} for any temperature readings mentioned with a time
              summary: one short sentence describing what you captured, shown to the user.
        """.trimIndent()

        private val ANALYZE_SYSTEM = """
            You are an experienced pitmaster coaching a home cook through their smoking log. You get one cook's details,
            its temperature log (pit and meat probe over time) when available, and their past cooks of similar meat.

            Ground everything in their numbers: total time and hours per pound, when and how long the stall lasted
            (probe climbing under ~1°F per 10 min, typically 150–170°F), how steady the pit was, wrap timing, final
            internal temp and rest. When past cooks exist, compare and point out which variable changes lined up with
            better or worse results. Give concrete, specific suggestions for next time rather than generic advice, and
            say plainly when the data is too thin to support a conclusion. Mention food safety only if something in the
            data warrants it (e.g. a long time below 140°F internal). If they left "next time" notes on earlier cooks,
            say whether this cook followed them and how it turned out.

            Write for a phone screen: short paragraphs or brief bulleted lists, plain text, no markdown headers or tables.
            Keep it under about 300 words unless the user asks for more.
        """.trimIndent()
    }
}
