package com.smokelog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smokelog.data.Cook
import com.smokelog.data.CookForecast
import com.smokelog.data.Fmt
import com.smokelog.data.Probes
import com.smokelog.data.TempReading
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Categorical slots 1–5 of the validated default palette, light / dark steps. */
private data class ChartColors(val pit: Color, val probe: Color, val probe2: Color, val bt1: Color, val bt2: Color, val ink: Color, val muted: Color, val grid: Color, val surface: Color)

@Composable
private fun chartColors(): ChartColors = if (isSystemInDarkTheme())
    ChartColors(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFFD55181), Color(0xFFF0EFEC), Color(0xFFC3C2B7), Color(0x33F0EFEC), Color(0xFF1A1A19))
else
    ChartColors(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4), Color(0xFF0B0B0B), Color(0xFF52514E), Color(0x1F0B0B0B), Color(0xFFFCFCFB))

private class Series(val name: String, val color: Color, val value: (TempReading) -> Double?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TempChart(
    cook: Cook, readings: List<TempReading>, modifier: Modifier = Modifier,
    /** Probe slot and its forecast, drawn as a dashed projection to the goal. */
    forecast: Pair<Int, CookForecast.Forecast>? = null,
) {
    val c = chartColors()
    val all = listOf(
        Series("Pit", c.pit) { it.grillF },
        Series(Probes.labels[0], c.probe) { it.probeF },
        Series(Probes.labels[1], c.probe2) { it.probe2F },
        Series(Probes.labels[2], c.bt1) { it.probe3F },
        Series(Probes.labels[3], c.bt2) { it.probe4F },
    )
    val series = all.filter { s -> readings.any { s.value(it) != null } }
    val hasSet = readings.any { it.grillSetF != null }
    val target = readings.lastOrNull { it.probeTargetF != null }?.probeTargetF ?: forecast?.second?.goalF
    val probeColors = listOf(c.probe, c.probe2, c.bt1, c.bt2)
    val targetColor = forecast?.first?.let { probeColors[it] } ?: c.probe

    if (readings.size < 2 || series.isEmpty()) {
        Text(
            "No temperature data yet. Start recording from a Traeger, or add readings manually.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val t0 = cook.startTime?.coerceAtMost(readings.first().time) ?: readings.first().time
    val tLast = max(readings.last().time, cook.offTime ?: 0L).coerceAtLeast(t0 + 60_000)
    // Room for the projection, but never so much that the cook so far gets squashed.
    val projEnd = forecast?.second?.doneAt?.let { done -> minOf(done, tLast + max(2 * 3_600_000L, (tLast - t0) * 3 / 2)) }
    val t1 = max(tLast, projEnd ?: 0L)
    fun projection(): List<Pair<Long, Double>> {
        val (_, f) = forecast ?: return emptyList()
        val end = projEnd ?: return emptyList()
        val n = 24
        return (0..n).mapNotNull { i -> val t = f.fromTime + (end - f.fromTime) * i / n; f.projectAt(t)?.let { t to it } }
    }
    val proj = projection()
    val values = readings.flatMap { r -> series.mapNotNull { it.value(r) } + listOfNotNull(r.grillSetF) } + listOfNotNull(target) + proj.map { it.second }
    val yMin = floor((values.min() - 10) / 25.0) * 25
    val yMax = ceil((values.max() + 10) / 25.0) * 25

    var hoverX by remember { mutableStateOf<Float?>(null) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = c.muted)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Legend: always shown for multiple series; identity never relies on color alone.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            series.forEach { LegendItem(it.name, it.color, dashed = false) }
            if (hasSet) LegendItem("Pit set", c.muted, dashed = true)
            if (target != null) LegendItem("Target", targetColor, dashed = true)
            if (proj.isNotEmpty()) LegendItem("Forecast", probeColors[forecast!!.first], dashed = true)
        }
        Canvas(
            modifier
                .fillMaxWidth()
                .height(240.dp)
                .pointerInput(readings) {
                    detectTapGestures(onPress = { hoverX = it.x; tryAwaitRelease(); })
                }
                .pointerInput(readings) {
                    // Horizontal only, so vertical swipes over the chart still scroll the page.
                    detectHorizontalDragGestures(onDragEnd = { hoverX = null }, onDragCancel = { hoverX = null }) { change, _ -> hoverX = change.position.x }
                }
        ) {
            val left = 40.dp.toPx(); val bottom = 22.dp.toPx(); val top = 8.dp.toPx(); val right = 8.dp.toPx()
            val w = size.width - left - right; val h = size.height - top - bottom
            fun x(t: Long) = left + (t - t0).toFloat() / (t1 - t0) * w
            fun y(v: Double) = top + ((yMax - v) / (yMax - yMin)).toFloat() * h

            // Recessive grid + y labels
            var v = yMin
            val yStep = if (yMax - yMin > 200) 50.0 else 25.0
            while (v <= yMax) {
                val yy = y(v)
                drawLine(c.grid, Offset(left, yy), Offset(left + w, yy), 1f)
                val tl = measurer.measure("${v.roundToInt()}°", labelStyle)
                drawText(tl, topLeft = Offset(left - tl.size.width - 6f, yy - tl.size.height / 2f))
                v += yStep
            }
            // x labels: elapsed hours
            val totalH = (t1 - t0) / 3_600_000.0
            val hStep = when { totalH > 12 -> 3; totalH > 6 -> 2; totalH > 1.5 -> 1; else -> 0 }
            if (hStep > 0) {
                var hr = 0
                while (hr <= totalH) {
                    val xx = x(t0 + hr * 3_600_000L)
                    val tl = measurer.measure("${hr}h", labelStyle)
                    drawText(tl, topLeft = Offset(xx - tl.size.width / 2f, top + h + 4f))
                    hr += hStep
                }
            } else {
                listOf(t0, t1).forEach { t ->
                    val tl = measurer.measure("${(t - t0) / 60_000}m", labelStyle)
                    drawText(tl, topLeft = Offset((x(t) - tl.size.width / 2f).coerceIn(left, left + w - tl.size.width), top + h + 4f))
                }
            }

            // Milestones
            fun milestone(t: Long?, label: String) {
                if (t == null || t < t0 || t > t1) return
                val xx = x(t)
                drawLine(c.muted, Offset(xx, top), Offset(xx, top + h), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val tl = measurer.measure(label, labelStyle.copy(color = c.ink))
                drawText(tl, topLeft = Offset((xx + 4f).coerceAtMost(left + w - tl.size.width), top))
            }
            milestone(cook.wrapTime, "Wrap")
            milestone(cook.offTime, "Off")

            if (target != null) {
                drawLine(targetColor, Offset(left, y(target)), Offset(left + w, y(target)), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            if (hasSet) drawSeries(readings, { it.grillSetF }, c.muted, ::x, ::y, dashed = true)
            series.forEach { s -> drawSeries(readings, s.value, s.color, ::x, ::y, dashed = false) }

            // Forecast: dashed curve from the latest reading to the goal, with a ring at the finish.
            if (proj.size > 1) {
                val color = probeColors[forecast!!.first]
                val path = Path().apply {
                    moveTo(x(proj[0].first), y(proj[0].second))
                    proj.drop(1).forEach { (t, v) -> lineTo(x(t), y(v)) }
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))))
                val done = forecast.second.doneAt
                if (done != null && done <= t1) {
                    val p = Offset(x(done), y(forecast.second.goalF))
                    drawCircle(c.surface, 7.dp.toPx(), p)
                    drawCircle(color, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                    val tl = measurer.measure(clock(done, System.currentTimeMillis()), labelStyle.copy(color = c.ink))
                    drawText(tl, topLeft = Offset((p.x - tl.size.width - 8f).coerceAtLeast(left), p.y + 8f))
                }
            }

            // Crosshair + tooltip
            hoverX?.let { hx ->
                val tAt = t0 + ((hx - left) / w * (t1 - t0)).toLong()
                val r = readings.minByOrNull { abs(it.time - tAt) } ?: return@let
                val xx = x(r.time)
                drawLine(c.ink.copy(alpha = 0.5f), Offset(xx, top), Offset(xx, top + h), 1f)
                series.forEach { s -> s.value(r)?.let { vv ->
                    drawCircle(c.surface, 6.dp.toPx(), Offset(xx, y(vv)))
                    drawCircle(s.color, 4.dp.toPx(), Offset(xx, y(vv)))
                } }
                val lines = buildList {
                    add("${Fmt.time(r.time)} · ${Fmt.duration((r.time - t0) / 60_000)}")
                    series.forEach { s -> s.value(r)?.let { add("${s.name}  ${Fmt.temp(it)}") } }
                    r.grillSetF?.let { add("Pit set  ${Fmt.temp(it)}") }
                }
                val tl = measurer.measure(lines.joinToString("\n"), TextStyle(fontSize = 12.sp, color = c.ink))
                val pad = 8.dp.toPx()
                val boxW = tl.size.width + pad * 2; val boxH = tl.size.height + pad * 2
                val bx = if (xx + 12f + boxW < size.width) xx + 12f else xx - 12f - boxW
                drawRoundRect(c.surface, Offset(bx, top), Size(boxW, boxH), CornerRadius(8.dp.toPx()))
                drawRoundRect(c.grid, Offset(bx, top), Size(boxW, boxH), CornerRadius(8.dp.toPx()), style = Stroke(1f))
                drawText(tl, topLeft = Offset(bx + pad, top + pad))
            }
        }
    }
}

private fun DrawScope.drawSeries(
    readings: List<TempReading>, value: (TempReading) -> Double?, color: Color,
    x: (Long) -> Float, y: (Double) -> Float, dashed: Boolean,
) {
    val path = Path()
    var penDown = false
    var lastT = 0L
    readings.forEach { r ->
        val v = value(r)
        // Break the line on missing data or gaps over 15 min (e.g. lost connection).
        if (v == null || (penDown && r.time - lastT > 15 * 60_000)) penDown = false
        if (v != null) {
            if (penDown) path.lineTo(x(r.time), y(v)) else path.moveTo(x(r.time), y(v))
            penDown = true; lastT = r.time
        }
    }
    drawPath(
        path, color,
        style = Stroke(
            width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null,
        ),
    )
}

@Composable
private fun LegendItem(name: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dashed) {
            Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
                drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
            }
        } else {
            Box(Modifier.size(10.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
