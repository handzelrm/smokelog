package com.smokelog

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.smokelog.ai.AiAssistant
import com.smokelog.data.AppDatabase
import com.smokelog.data.PhotoStore
import com.smokelog.data.Settings
import com.smokelog.traeger.TraegerClient
import kotlinx.coroutines.flow.MutableStateFlow

/** Minimal manual DI container. */
class SmokeLogApp : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var settings: Settings; private set
    lateinit var traeger: TraegerClient; private set
    lateinit var ai: AiAssistant; private set
    lateinit var photos: PhotoStore; private set

    /** Cook id currently recorded by [com.smokelog.traeger.CookMonitorService], or null. */
    val monitoringCookId = MutableStateFlow<Long?>(null)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.create(this)
        settings = Settings(this)
        traeger = TraegerClient(settings)
        ai = AiAssistant(settings)
        photos = PhotoStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Cook monitoring", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_MONITOR = "monitor"
        lateinit var instance: SmokeLogApp; private set
    }
}
