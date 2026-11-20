package com.smokelog.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object Fmt {
    private val dateTime = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")
    private val time = DateTimeFormatter.ofPattern("h:mm a")
    private val date = DateTimeFormatter.ofPattern("MMM d, yyyy")

    fun dateTime(ms: Long?): String = ms?.let { dateTime.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "–"
    fun time(ms: Long?): String = ms?.let { time.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "–"
    fun date(ms: Long?): String = ms?.let { date.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "–"

    fun duration(minutes: Long?): String {
        if (minutes == null) return "–"
        val h = minutes / 60; val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun temp(f: Number?): String = f?.let { "${it.toDouble().roundToInt()}°F" } ?: "–"
    fun num(d: Double?): String = d?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: ""
}
