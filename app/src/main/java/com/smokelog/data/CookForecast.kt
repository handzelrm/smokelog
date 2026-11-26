package com.smokelog.data

import kotlin.math.exp
import kotlin.math.ln

/**
 * Predicts when a probe reaches its goal temperature.
 *
 * Meat heats roughly per Newton's law: dT/dt = k · (pit − T). We measure how fast the probe
 * has been rising over the last hour and divide by the average pit-minus-meat gap over that
 * hour to get k for this piece of meat, then project forward against the pit's set (or
 * recent) temperature. That curve flattens as the meat closes on the pit, which a straight
 * line through the plot would miss. A stall (evaporative cooling, typically 145–175°F)
 * makes the probe flat; we report it instead of an absurd estimate. Without pit data the
 * forecast falls back to the recent straight-line rise.
 */
object CookForecast {
    data class Point(val time: Long, val meatF: Double, val pitF: Double?, val setF: Double?)

    enum class Kind { ON_TRACK, STALLED, NOT_RISING, PIT_TOO_LOW, DONE, NEED_MORE_DATA }

    data class Forecast(
        val kind: Kind,
        val currentF: Double?,
        val goalF: Double,
        /** Pit temp the projection assumes (set temp if known, else recent average). */
        val pitF: Double? = null,
        /** Recent probe rise, °F per hour. */
        val ratePerHour: Double? = null,
        /** Heating constant, per hour (null when using the straight-line fallback). */
        val k: Double? = null,
        val fromTime: Long = 0,
        /** Predicted time the probe reaches the goal. */
        val doneAt: Long? = null,
        /** STALLED: when the stall began, and the time it'll take after it breaks. */
        val stallSince: Long? = null,
        val afterStallMinutes: Long? = null,
        /** NEED_MORE_DATA: minutes of readings still needed. */
        val needMinutes: Long? = null,
    ) {
        fun minutesLeft(now: Long): Long? = doneAt?.let { ((it - now) / 60_000).coerceAtLeast(0) }

        /** Projected probe temp at [t] (for drawing the forecast on the chart). */
        fun projectAt(t: Long): Double? {
            val cur = currentF ?: return null
            val h = (t - fromTime) / 3_600_000.0
            return when {
                k != null && pitF != null -> pitF - (pitF - cur) * exp(-k * h)
                ratePerHour != null -> cur + ratePerHour * h
                else -> null
            }
        }
    }

    private const val HOUR = 3_600_000L
    private const val MIN = 60_000L
    const val MIN_SPAN_MIN = 15L
    private const val MAX_HOURS = 48.0

    fun forecast(points: List<Point>, goalF: Double): Forecast {
        val pts = points.sortedBy { it.time }
        val last = pts.lastOrNull() ?: return Forecast(Kind.NEED_MORE_DATA, null, goalF, needMinutes = MIN_SPAN_MIN)
        val cur = last.meatF
        if (cur >= goalF) return Forecast(Kind.DONE, cur, goalF, fromTime = last.time)

        val window = pts.filter { it.time >= last.time - HOUR }
        val span = (last.time - window.first().time) / MIN
        if (window.size < 4 || span < MIN_SPAN_MIN) {
            return Forecast(Kind.NEED_MORE_DATA, cur, goalF, fromTime = last.time, needMinutes = MIN_SPAN_MIN - span)
        }

        // Pit the meat will see from now on: the set temp if the grill reports one, else the recent average.
        val pit = last.setF ?: pts.filter { it.time >= last.time - 15 * MIN }.mapNotNull { it.pitF }.average().takeIf { !it.isNaN() }
        val recent = pts.filter { it.time >= last.time - 30 * MIN }
        val rate = slopePerHour(recent)

        // Stall: flat probe in the stall zone for a while.
        if (cur in 140.0..185.0 && rate < 3.0) {
            val since = stallStart(pts, cur)
            if (last.time - since >= 20 * MIN) {
                val before = pts.filter { it.time in (since - HOUR)..since }
                val kBefore = heatingK(before)
                val after = if (pit != null && kBefore != null && pit > goalF + 2) hoursTo(cur, goalF, pit, kBefore) else null
                return Forecast(Kind.STALLED, cur, goalF, pit, rate, fromTime = last.time, stallSince = since,
                    afterStallMinutes = after?.takeIf { it <= MAX_HOURS }?.let { (it * 60).toLong() })
            }
        }

        if (pit != null) {
            if (pit <= goalF + 2) return Forecast(Kind.PIT_TOO_LOW, cur, goalF, pit, rate, fromTime = last.time)
            val k = heatingK(window)
            if (k != null) {
                val h = hoursTo(cur, goalF, pit, k)
                return if (h > MAX_HOURS) Forecast(Kind.NOT_RISING, cur, goalF, pit, rate, k, last.time)
                else Forecast(Kind.ON_TRACK, cur, goalF, pit, rate, k, last.time, doneAt = last.time + (h * HOUR).toLong())
            }
        } else if (rate > 0.5) {
            // No pit data (manual readings): straight line through the recent rise.
            val h = (goalF - cur) / rate
            if (h <= MAX_HOURS) return Forecast(Kind.ON_TRACK, cur, goalF, null, rate, null, last.time, doneAt = last.time + (h * HOUR).toLong())
        }
        return Forecast(Kind.NOT_RISING, cur, goalF, pit, rate, fromTime = last.time)
    }

    /** k = (rise per hour) / (average pit − meat gap), from points that have pit temps. */
    private fun heatingK(pts: List<Point>): Double? {
        val withPit = pts.filter { it.pitF != null }
        if (withPit.size < 4 || withPit.last().time - withPit.first().time < MIN_SPAN_MIN * MIN) return null
        val gap = withPit.map { it.pitF!! - it.meatF }.average()
        if (gap <= 5) return null
        val k = slopePerHour(withPit) / gap
        return k.takeIf { it > 1e-4 }
    }

    /** Hours for meat at [cur] to reach [goal] in a pit at [pit] with heating constant [k]. */
    private fun hoursTo(cur: Double, goal: Double, pit: Double, k: Double) = ln((pit - cur) / (pit - goal)) / k

    /** Least-squares slope of meat temp over time, °F per hour. */
    private fun slopePerHour(pts: List<Point>): Double {
        if (pts.size < 2) return 0.0
        val t0 = pts.first().time
        val xs = pts.map { (it.time - t0) / HOUR.toDouble() }
        val ys = pts.map { it.meatF }
        val mx = xs.average(); val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        return if (den == 0.0) 0.0 else xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
    }

    /** Start of the run of readings, back from the latest, that stay within 5°F of [cur]. */
    private fun stallStart(pts: List<Point>, cur: Double): Long {
        var since = pts.last().time
        for (p in pts.asReversed()) {
            if (p.meatF < cur - 5 || p.meatF > cur + 5) break
            since = p.time
        }
        return since
    }

    data class ProbeForecast(val slot: Int, val label: String, val forecast: Forecast, val goalIsTypical: Boolean)

    /**
     * A forecast for each probe that's reporting and has a goal: its target on the grill
     * ([liveTargets] while recording, else the stored target for the main probe), or for the
     * main probe the meat's usual pull temp.
     */
    fun forProbes(meat: String, cut: String, readings: List<TempReading>, liveTargets: List<Double?>?): List<ProbeForecast> {
        val last = readings.lastOrNull() ?: return emptyList()
        val main = last.probeSlots.indexOfFirst { it != null }.takeIf { it >= 0 } ?: return emptyList()
        return (0 until Probes.COUNT).mapNotNull { slot ->
            // Only probes reporting in the last 10 minutes of readings.
            if (readings.none { it.time >= last.time - 10 * MIN && it.probeSlots[slot] != null }) return@mapNotNull null
            val grillGoal = liveTargets?.getOrNull(slot)
                ?: if (slot == main && liveTargets == null) readings.lastOrNull { it.probeTargetF != null }?.probeTargetF else null
            val typical = if (slot == main && grillGoal == null) MeatCatalog.typicalDoneF(meat, cut) else null
            val goal = grillGoal ?: typical ?: return@mapNotNull null
            ProbeForecast(slot, Probes.labels[slot], forecast(points(readings, slot), goal), typical != null)
        }
    }

    /** Points for probe slot [slot] (0–3) from stored readings. */
    fun points(readings: List<TempReading>, slot: Int): List<Point> = readings.mapNotNull { r ->
        r.probeSlots[slot]?.let { Point(r.time, it, r.grillF, r.grillSetF) }
    }
}
