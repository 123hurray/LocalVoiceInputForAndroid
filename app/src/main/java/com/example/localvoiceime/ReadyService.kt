package com.example.localvoiceime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the shared recognizer ready without opening the microphone or holding a wake lock. */
class ReadyService : Service() {
    private var destroyed = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "快速语音输入", NotificationManager.IMPORTANCE_LOW),
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            val store = VoiceSettingsStore(this)
            store.save(store.load().copy(keepResident = false))
            SpeechEngine.release()
            stopSelf()
            return START_NOT_STICKY
        }
        val settings = VoiceSettingsStore(this).load()
        if (!settings.keepResident || !ModelRepository(this).isInstalled(settings.asrModel)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification("正在准备…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification("正在准备…"))
        SpeechEngine.prepare(this, settings.asrModel) { result ->
            if (!destroyed) {
                if (result.isSuccess) getSystemService(NotificationManager::class.java)
                    .notify(ID, notification("随时按住说话，松手输入"))
                else stopSelf()
            }
        }
        return START_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReadyService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("本地语音已就绪")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "关闭快速启动", stop).build()).build()
    }
    override fun onDestroy() { destroyed = true; super.onDestroy() }
    companion object {
        private const val CHANNEL = "voice_ready"
        private const val ID = 10
        private const val STOP = "com.example.localvoiceime.STOP_READY"
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, ReadyService::class.java)); true
        } catch (_: RuntimeException) { false }
        fun stop(context: Context) { context.stopService(Intent(context, ReadyService::class.java)); SpeechEngine.release() }
    }
}
