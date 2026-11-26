package com.smokelog.traeger

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.smokelog.SmokeLogApp
import com.smokelog.data.Probes
import com.smokelog.data.TempReading
import com.smokelog.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that keeps receiving grill updates for the whole cook (brisket can
 * run 12–18 h) and writes a [TempReading] at the configured interval.
 */
class CookMonitorService : LifecycleService() {
    private var job: Job? = null
    private val alertedTargets = arrayOfNulls<Double>(Probes.COUNT)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopMonitoring(); return START_NOT_STICKY }

        val cookId = intent?.getLongExtra(EXTRA_COOK, -1L) ?: -1L
        val thing = intent?.getStringExtra(EXTRA_THING)
        if (cookId < 0 || thing == null) { stopSelf(); return START_NOT_STICKY }

        ServiceCompat.startForeground(
            this, NOTIF_ID, notification("Connecting to grill…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        start(cookId, thing)
        return START_REDELIVER_INTENT
    }

    private fun start(cookId: Long, thing: String) {
        val app = application as SmokeLogApp
        job?.cancel()
        alertedTargets.fill(null)
        app.monitoringCookId.value = cookId
        _live.value = LiveState(cookId, null, "Connecting…")
        val source = if (thing == TraegerClient.DEMO_THING) "demo" else "traeger"
        // Demo runs at one sim-minute per second, so record every update.
        val intervalMs = if (source == "demo") 0L else app.settings.state.value.readingIntervalSec * 1000L
        var lastWrite = 0L

        job = lifecycleScope.launch {
            app.traeger.updates(thing).collect { u ->
                when (u) {
                    is GrillUpdate.Problem -> { _live.value = _live.value.copy(message = u.message) }
                    is GrillUpdate.Status -> {
                        val s = u.status
                        _live.value = LiveState(cookId, s, if (s.connected) null else "Grill reports offline")
                        updateNotification(s)
                        if (s.time - lastWrite >= intervalMs) {
                            lastWrite = s.time
                            app.db.cookDao().insertReading(
                                TempReading(
                                    cookId = cookId, time = s.time, grillF = s.grillF, grillSetF = s.setF,
                                    probeF = s.probes[0]?.tempF, probe2F = s.probes[1]?.tempF,
                                    probe3F = s.probes[2]?.tempF, probe4F = s.probes[3]?.tempF,
                                    probeTargetF = s.probeTargetF, source = source,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun stopMonitoring() {
        job?.cancel()
        (application as SmokeLogApp).monitoringCookId.value = null
        _live.value = LiveState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        (application as SmokeLogApp).monitoringCookId.value = null
        super.onDestroy()
    }

    private fun updateNotification(s: GrillStatus) {
        val nm = getSystemService(NotificationManager::class.java)
        val text = buildString {
            append("Pit ${s.grillF.f()}")
            s.setF?.let { append(" (set ${it.f()})") }
            s.probes.forEachIndexed { i, p ->
                if (p?.tempF == null) return@forEachIndexed
                append(" · ${Probes.labels[i]} ${p.tempF.f()}")
                p.targetF?.let { append(" → ${it.f()}") }
            }
        }
        nm.notify(NOTIF_ID, notification(text))
        // One alert per probe; re-arms if that probe's target is changed.
        s.probes.forEachIndexed { i, p ->
            val probe = p?.tempF; val target = p?.targetF
            if (probe == null || target == null) return@forEachIndexed
            if (alertedTargets[i] == target || probe < target) return@forEachIndexed
            alertedTargets[i] = target
            nm.notify(
                NOTIF_ID + 1 + i,
                NotificationCompat.Builder(this, SmokeLogApp.CHANNEL_MONITOR)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle("${Probes.labels[i]} reached its target")
                    .setContentText("${Probes.labels[i]} at ${probe.f()} (target ${target.f()}). Time to check for probe-tender.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, SmokeLogApp.CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Recording cook")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(
                0, "Stop recording",
                PendingIntent.getService(this, 1, Intent(this, CookMonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            )
            .build()

    data class LiveState(val cookId: Long? = null, val status: GrillStatus? = null, val message: String? = null)

    companion object {
        private const val NOTIF_ID = 42
        private const val EXTRA_COOK = "cook"
        private const val EXTRA_THING = "thing"
        private const val ACTION_STOP = "stop"

        private val _live = MutableStateFlow(LiveState())
        val live: StateFlow<LiveState> = _live.asStateFlow()

        fun start(context: Context, cookId: Long, thing: String) {
            context.startForegroundService(
                Intent(context, CookMonitorService::class.java).putExtra(EXTRA_COOK, cookId).putExtra(EXTRA_THING, thing)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CookMonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}

fun Double?.f(): String = if (this == null) "–" else "${roundToInt()}°F"
