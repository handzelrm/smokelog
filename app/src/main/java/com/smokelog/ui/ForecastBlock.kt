package com.smokelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smokelog.data.CookForecast
import com.smokelog.data.CookForecast.Kind
import com.smokelog.data.Fmt
import kotlin.math.roundToInt

/** "About 1h 25m left, done around 2:40 PM" for each probe with a goal. */
@Composable
fun ForecastBlock(forecasts: List<CookForecast.ProbeForecast>, meat: String, now: Long = System.currentTimeMillis()) {
    if (forecasts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        forecasts.forEach { pf ->
            val f = pf.forecast
            val goal = Fmt.temp(f.goalF) + if (pf.goalIsTypical) " (typical for ${meat.lowercase().ifBlank { "this meat" }})" else ""
            val (headline, detail) = forecastText(pf.label, f, goal, now)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

internal fun forecastText(label: String, f: CookForecast.Forecast, goal: String, now: Long): Pair<String, String?> {
    val cur = Fmt.temp(f.currentF)
    val basis = listOfNotNull(
        f.ratePerHour?.let { "rising ${it.roundToInt()}°F/h" },
        f.pitF?.let { p -> f.currentF?.let { "pit ${Fmt.temp(p)}, ${(p - it).roundToInt()}° hotter than the probe" } },
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
    return when (f.kind) {
        Kind.ON_TRACK -> "$label: about ${Fmt.duration(f.minutesLeft(now))} to $goal · done around ${clock(f.doneAt!!, now)}" to
            listOfNotNull("Now $cur", basis).joinToString(" · ")
        Kind.STALLED -> "$label is in the stall at $cur (since ${Fmt.time(f.stallSince)})" to
            (f.afterStallMinutes?.let { "Once it climbs again: about ${Fmt.duration(it)} more to $goal." }
                ?: "The estimate comes back once it climbs again.")
        Kind.NOT_RISING -> "$label: no estimate yet for $goal" to "It isn't rising fast enough to project (now $cur)."
        Kind.PIT_TOO_LOW -> "$label: the pit can't reach $goal" to "Pit ${Fmt.temp(f.pitF)} needs to be hotter than the goal."
        Kind.DONE -> "$label reached $goal" to "Now $cur."
        Kind.NEED_MORE_DATA -> "$label: estimate to $goal in about ${f.needMinutes ?: CookForecast.MIN_SPAN_MIN} min" to
            "Needs ${CookForecast.MIN_SPAN_MIN} minutes of readings."
    }
}

/** "2:40 PM" today, else with the day. */
internal fun clock(t: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val sameDay = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate() == java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (sameDay) Fmt.time(t) else Fmt.dateTime(t)
}
