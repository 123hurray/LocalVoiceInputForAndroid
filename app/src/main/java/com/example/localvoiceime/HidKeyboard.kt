package com.example.localvoiceime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.Future

/** Owned by the visible launcher activity. Bluetooth callbacks and connection state run on main. */
@SuppressLint("MissingPermission") // The owning activity checks and requests Bluetooth permissions.
internal class HidKeyboard(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val changed: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var profile: BluetoothHidDevice? = null
    private var closed = false
    private var visible = false
    private var profileRequested = false
    private var registrationRequestedAt: Long? = null
    private var registrationAttempts = 0
    private var reconnectAfter = Long.MAX_VALUE
    private var pluggedDevice: BluetoothDevice? = null
    private val preferences = context.getSharedPreferences("hid_host", Context.MODE_PRIVATE)
    private var lastAddress = preferences.getString("last_address", null)
    var connectionToken: Any? = null
        private set
    var status: String = "正在开启蓝牙键盘…"
        private set
    private val refreshTask = object : Runnable {
        override fun run() {
            if (!visible || closed) return
            refresh()
            main.postDelayed(this, 700)
        }
    }
    var registered = false
        private set
    private var host: BluetoothDevice? = null
    private var sending: Future<*>? = null
    private var sendEpoch = 0
    val connected get() = registered && host != null
    val ready get() = connected && sending == null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (closed || profile == null) return
            registrationRequestedAt = null
            this@HidKeyboard.registered = registered
            if (!registered) {
                clearHost()
                publish("正在恢复蓝牙键盘…")
                if (visible) main.post { refresh() }
                return
            }
            this@HidKeyboard.pluggedDevice = pluggedDevice
            if (reconnectAfter != Long.MAX_VALUE) reconnectAfter = SystemClock.elapsedRealtime() + 700
            refresh()
            if (host == null && status == "正在开启蓝牙键盘…") publish(WAITING_FOR_HOST)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (closed || profile == null || !registered) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> acceptHost(device)
                BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device != host) return
                    val interrupted = sending != null
                    clearHost()
                    publish(if (interrupted) "发送中断；正在等待 Mac 连接" else WAITING_FOR_HOST)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (!closed && device == host && type == BluetoothHidDevice.REPORT_TYPE_INPUT && id == 0.toByte()) {
                runCatching { profile?.replyReport(device, type, id, ByteArray(8)) }
            }
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(type: Int, proxy: BluetoothProfile) {
            val hid = proxy as? BluetoothHidDevice ?: return
            if (closed) {
                runCatching { adapter.closeProfileProxy(type, hid) }
                return
            }
            profile = hid
            refresh()
        }

        override fun onServiceDisconnected(type: Int) {
            if (closed) return
            clearHost()
            profile = null; registered = false; registrationRequestedAt = null
            publish("蓝牙键盘服务已断开")
        }
    }

    private fun requestProfile() {
        if (profile != null || profileRequested) return
        profileRequested = runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE) }.getOrDefault(false)
        if (!profileRequested) publish("此设备无法开启蓝牙键盘模式")
        else main.postDelayed({
            if (!closed && profile == null) {
                profileRequested = false
                publish("蓝牙键盘服务未响应，请在设置中重试")
            }
        }, 5000)
    }

    fun resume() {
        if (closed) return
        visible = true
        registrationAttempts = 0
        reconnectAfter = SystemClock.elapsedRealtime() + 700
        main.removeCallbacks(refreshTask)
        requestProfile()
        refresh()
        main.postDelayed(refreshTask, 700)
    }

    fun pause() {
        visible = false
        main.removeCallbacks(refreshTask)
        stopSend()
    }

    fun refresh() {
        if (!visible || closed) return
        val hid = profile ?: return
        val now = SystemClock.elapsedRealtime()
        if (!registered) {
            val pending = registrationRequestedAt
            if (pending != null && now - pending < 2200) return
            if (registrationAttempts >= 3) {
                publish("蓝牙键盘未就绪，请在设置中重试")
                return
            }
            registrationAttempts++
            registrationRequestedAt = now
            runCatching {
                hid.registerApp(
                    BluetoothHidDeviceAppSdpSettings("LocalVoice HID", "Local voice text", "LocalVoice",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD, descriptor),
                    null, null, Executor { main.post(it) }, callback,
                )
            }
            return
        }
        val current = host
        if (current != null) {
            when (runCatching { hid.getConnectionState(current) }.getOrNull()) {
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> clearHost()
                else -> return
            }
        }
        val bonded = runCatching { adapter.bondedDevices }.getOrDefault(emptySet())
        val known = bonded.firstOrNull { it.address == lastAddress } ?: pluggedDevice?.takeIf { it in bonded }
        val candidates = runCatching { hid.connectedDevices }.getOrDefault(emptyList())
        val connected = candidates.firstOrNull { it == known } ?: candidates.firstOrNull()
            ?: (listOfNotNull(known, pluggedDevice) + bonded).distinct().firstOrNull {
                runCatching { hid.getConnectionState(it) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
            }
        if (connected != null) acceptHost(connected)
        if (host != null) return
        if (known != null && now >= reconnectAfter) {
            val state = runCatching { hid.getConnectionState(known) }.getOrNull()
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                reconnectAfter = Long.MAX_VALUE
                if (runCatching { hid.connect(known) }.getOrDefault(false)) {
                    publish("正在恢复与 Mac 的连接…")
                    main.postDelayed({ if (visible && host == null && registered) publish(WAITING_FOR_HOST) }, 4000)
                    return
                }
            }
        }
        if (status != "正在恢复与 Mac 的连接…") publish(WAITING_FOR_HOST)
    }

    private fun clearHost() {
        stopSend()
        host = null
        connectionToken = null
    }

    private fun publish(message: String, force: Boolean = false) {
        if (!force && status == message) return
        status = message
        changed(message)
    }

    private fun acceptHost(device: BluetoothDevice) {
        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        if (!bonded || (host != null && host != device)) {
            runCatching { profile?.disconnect(device) }
            return
        }
        if (host == device) return
        host = device
        connectionToken = Any()
        reconnectAfter = Long.MAX_VALUE
        lastAddress = device.address
        preferences.edit().putString("last_address", lastAddress).apply()
        val name = runCatching { device.name ?: device.address }.getOrDefault("接收设备")
        publish("已连接：$name", force = true)
    }

    enum class MacSetupKey(val label: String, val usage: Int) {
        LEFT_SHIFT_NEIGHBOR("Z", 0x1d),
        RIGHT_SHIFT_NEIGHBOR("/", 0x38),
    }

    fun sendSetupKey(key: MacSetupKey, done: (Boolean) -> Unit): Boolean =
        sendKeys(intArrayOf(key.usage), done)

    fun sendEditKey(newline: Boolean, done: (Boolean) -> Unit): Boolean =
        sendKeys(intArrayOf(if (newline) 0x28 else 0x2a), done)

    fun send(text: String, done: (Boolean) -> Unit): Boolean =
        sendKeys(HidTextFrame.encode(text), done)

    private fun sendKeys(keys: IntArray, done: (Boolean) -> Unit): Boolean {
        val hid = profile ?: return false
        val device = host ?: return false
        if (!ready || !visible) return false
        val epoch = ++sendEpoch
        sending = worker.submit {
            val release = ByteArray(8)
            val ok = runCatching {
                for (key in keys) {
                    if (Thread.currentThread().isInterrupted) return@runCatching false
                    val press = ByteArray(8).apply { this[2] = key.toByte() }
                    if (!hid.sendReport(device, 0, press)) return@runCatching false
                    Thread.sleep(8)
                    if (!hid.sendReport(device, 0, release)) return@runCatching false
                    Thread.sleep(8)
                }
                true
            }.getOrDefault(false)
            if (!ok) runCatching { hid.sendReport(device, 0, release) }
            main.post {
                if (!closed && sendEpoch == epoch) {
                    sending = null
                    done(ok)
                }
            }
        }
        return true
    }

    private fun stopSend() {
        sendEpoch++
        sending?.cancel(true)
        sending = null
    }

    fun close() {
        closed = true
        pause()
        clearHost()
        worker.shutdownNow()
        profile?.let { hid ->
            runCatching { hid.unregisterApp() }
            runCatching { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
        }
        profile = null; registered = false
    }

    companion object {
        private const val WAITING_FOR_HOST = "等待连接：请在 Mac 的蓝牙设置中连接此手机"
        // Standard eight-byte keyboard input report, including usages F13–F24.
        private val descriptor = intArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x05, 0x07,
            0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x75, 0x08, 0x95, 0x01, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x73, 0x19, 0x00, 0x29, 0x73, 0x81, 0x00, 0xc0,
        ).map(Int::toByte).toByteArray()
    }
}
