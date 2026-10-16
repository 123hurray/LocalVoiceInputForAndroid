package com.example.localvoiceime

import android.app.Application
import android.app.NotificationManager
import android.content.Intent

/** Remove the previous version's readiness notification without starting any service. */
class VoiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        stopService(Intent().setClassName(this, "$packageName.ReadyService"))
        getSystemService(NotificationManager::class.java).apply {
            cancel(10)
            deleteNotificationChannel("voice_ready")
        }
    }
}
