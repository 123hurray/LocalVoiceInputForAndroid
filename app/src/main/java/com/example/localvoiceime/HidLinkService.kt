package com.example.localvoiceime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder

/** Owns only the Bluetooth link, never recording or ASR. */
class HidLinkService : Service() {
    internal inner class LocalBinder : Binder() {
        val service get() = this@HidLinkService
    }
    private val binder = LocalBinder()
    internal var keyboard: HidKeyboard? = null
        private set
    private val listeners = linkedSetOf<(String) -> Unit>()
    private var lastStatus = "正在开启蓝牙连接…"

    internal fun listen(listener: (String) -> Unit) {
        listeners.add(listener)
        listener(keyboard?.status ?: lastStatus)
    }
    internal fun removeListener(listener: (String) -> Unit) { listeners.remove(listener) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "蓝牙输入连接", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            keyboard?.close(); keyboard = null
            lastStatus = "蓝牙输入已停用"
            listeners.toList().forEach { it(lastStatus) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, HidInputActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HidLinkService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bluetooth_link)
            .setContentTitle("蓝牙输入连接")
            .setContentText("保持键盘和触控板连接")
            .setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "断开", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(ID, notification)
        if (keyboard == null) {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter != null) {
                keyboard = HidKeyboard(applicationContext, adapter) { message ->
                    lastStatus = message
                    listeners.toList().forEach { it(message) }
                }.also { it.resume() }
                listeners.toList().forEach { it(keyboard?.status ?: lastStatus) }
            }
        } else keyboard?.resume()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keyboard?.close(); keyboard = null
        listeners.clear()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "hid_link"
        private const val ID = 31
        private const val STOP = "com.example.localvoiceime.STOP_HID_LINK"
    }
}
