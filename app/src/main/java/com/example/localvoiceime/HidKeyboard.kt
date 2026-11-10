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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Owned by the visible launcher activity. Bluetooth callbacks and connection state run on main. */
@SuppressLint("MissingPermission") // The owning activity checks and requests Bluetooth permissions.
internal class HidKeyboard(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    val mode: HidProfile,
    var allowReconnect: Boolean,
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
    @Volatile private var mouseEpoch = 0
    private var mouseMayBePressed = false
    private class AwaitingAck(val device: BluetoothDevice) {
        val reply = ArrayBlockingQueue<HidReportProtocol.Attempt>(1)
    }
    @Volatile private var awaitingAck: AwaitingAck? = null
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
            if (closed || device != host) return
            val size = when {
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == mode.keyboardId -> 8
                mode.composite && type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == HidReportProtocol.MOUSE_ID -> 4
                mode.composite && type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id.toInt() == HidReportProtocol.ACK_ID -> 1
                else -> return
            }
            runCatching { profile?.replyReport(device, type, id, ByteArray(size)) }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            if (!mode.composite || closed || device != host) return
            val result = if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) {
                HidReportProtocol.parseAck(id.toInt(), data)
            } else null
            runCatching {
                profile?.reportError(device, if (result != null) BluetoothHidDevice.ERROR_RSP_SUCCESS
                    else BluetoothHidDevice.ERROR_RSP_INVALID_PARAM)
            }
            if (result != null) acceptAck(device, result)
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            if (!mode.composite || closed || device != host) return
            HidReportProtocol.parseAck(reportId.toInt(), data)?.let { acceptAck(device, it) }
        }
    }

    private fun acceptAck(device: BluetoothDevice, result: HidReportProtocol.Attempt) {
        if (!visible) return
        awaitingAck?.takeIf { it.device == device }?.reply?.offer(result)
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
        resetMouse()
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
                    BluetoothHidDeviceAppSdpSettings(
                        if (mode.composite) "LocalVoice HID Combo" else "LocalVoice HID",
                        "Local voice input", "LocalVoice",
                        if (mode.composite) BluetoothHidDevice.SUBCLASS1_COMBO else BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                        HidReportProtocol.descriptor(mode),
                    ),
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
        if (allowReconnect && known != null && now >= reconnectAfter) {
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
        mouseEpoch++
        mouseMayBePressed = false
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

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int, click: Int = 0) {
        val hid = profile ?: return
        val device = host ?: return
        if (!mode.composite || !visible || !ready) return
        mouseMayBePressed = mouseMayBePressed || buttons != 0 || click != 0
        val epoch = mouseEpoch
        worker.execute {
            if (epoch != mouseEpoch) return@execute
            runCatching {
                if (click != 0) {
                    hid.sendReport(device, HidReportProtocol.MOUSE_ID, HidReportProtocol.mouseReport(buttons or click, 0, 0, 0))
                    Thread.sleep(16)
                }
                if (epoch == mouseEpoch) {
                    hid.sendReport(device, HidReportProtocol.MOUSE_ID, HidReportProtocol.mouseReport(buttons, dx, dy, wheel))
                }
            }
        }
    }

    fun resetMouse() {
        val epoch = ++mouseEpoch
        val release = mouseMayBePressed
        mouseMayBePressed = false
        if (!mode.composite || !release) return
        val hid = profile ?: return
        val device = host ?: return
        if (!visible || closed) return
        worker.execute {
            if (epoch == mouseEpoch) runCatching {
                hid.sendReport(device, HidReportProtocol.MOUSE_ID, ByteArray(4))
            }
        }
    }

    enum class MacSetupKey(val label: String, val usage: Int) {
        LEFT_SHIFT_NEIGHBOR("Z", 0x1d),
        RIGHT_SHIFT_NEIGHBOR("/", 0x38),
    }

    fun sendSetupKey(key: MacSetupKey, done: (Boolean) -> Unit): Boolean = sendKey(key.usage, done)

    fun sendEditKey(newline: Boolean, done: (Boolean) -> Unit): Boolean =
        sendKey(if (newline) 0x28 else 0x2a, done)

    private fun sendKey(key: Int, done: (Boolean) -> Unit): Boolean = enqueue({ hid, device, _ ->
        if (writeKeys(hid, device, intArrayOf(key, 0), 8L)) InputDelivery.CONFIRMED else InputDelivery.FAILED
    }, { done(it == InputDelivery.CONFIRMED) })

    fun send(text: String, progress: (String) -> Unit, done: (InputDelivery) -> Unit): Boolean {
        val reports = HidReportProtocol.reportUsages(HidTextFrame.encode(text))
        return enqueue({ hid, device, epoch ->
            if (!mode.composite) return@enqueue if (writeKeys(hid, device, reports, 4L)) {
                InputDelivery.SENT_WITHOUT_ACK
            } else InputDelivery.FAILED
            fun update(message: String) = main.post {
                if (!closed && sendEpoch == epoch && sending != null) progress(message)
            }
            HidReportProtocol.deliver { retry ->
                if (retry > 0) update("校验失败，正在重试 $retry/${HidReportProtocol.MAX_RETRIES}…")
                val ack = AwaitingAck(device)
                try {
                    // Arm immediately before F19, including a reply received before F19 is released.
                    val gap = minOf(4L + retry * 2L, 8L)
                    if (!writeKeys(hid, device, reports, gap) { usage ->
                        if (usage == HidTextFrame.END) awaitingAck = ack
                    }) HidReportProtocol.Attempt.WRITE_FAILED
                    else {
                        update("等待 Mac 确认…")
                        ack.reply.poll(2500, TimeUnit.MILLISECONDS) ?: HidReportProtocol.Attempt.TIMEOUT
                    }
                } finally {
                    if (awaitingAck === ack) awaitingAck = null
                }
            }
        }, done)
    }

    private fun writeKeys(
        hid: BluetoothHidDevice, device: BluetoothDevice, reports: IntArray, gap: Long,
        before: (Int) -> Unit = {},
    ): Boolean {
        val report = ByteArray(8)
        for (usage in reports) {
            if (Thread.currentThread().isInterrupted) return false
            before(usage)
            report[2] = usage.toByte()
            if (!hid.sendReport(device, mode.keyboardId, report)) return false
            Thread.sleep(gap)
        }
        return true
    }

    private fun enqueue(
        operation: (BluetoothHidDevice, BluetoothDevice, Int) -> InputDelivery,
        done: (InputDelivery) -> Unit,
    ): Boolean {
        val hid = profile ?: return false
        val device = host ?: return false
        if (!ready || !visible) return false
        val epoch = ++sendEpoch
        sending = worker.submit {
            val result = runCatching { operation(hid, device, epoch) }.getOrDefault(InputDelivery.FAILED)
            if (result == InputDelivery.FAILED) runCatching {
                hid.sendReport(device, mode.keyboardId, ByteArray(8))
            }
            main.post {
                if (!closed && sendEpoch == epoch) {
                    sending = null
                    done(result)
                }
            }
        }
        return true
    }

    private fun stopSend() {
        sendEpoch++
        awaitingAck = null
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
    }
}
