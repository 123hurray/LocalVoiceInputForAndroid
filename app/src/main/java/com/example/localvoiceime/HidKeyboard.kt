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
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.Future

/** Owned by the visible launcher activity. Bluetooth callbacks and selection run on main. */
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
    private var requested = false
    var registered = false
        private set
    private var selected: BluetoothDevice? = null
    private var connected = false
    private var sending: Future<*>? = null
    private var sendEpoch = 0
    val ready get() = registered && connected && sending == null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (closed || profile == null) return
            this@HidKeyboard.registered = registered
            if (!registered) {
                stopSend()
                connected = false
            }
            changed(if (registered) "请选择已配对的接收设备" else "蓝牙键盘已停用，请重新开启")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (closed || profile == null) return
            if (device != selected) {
                if (state == BluetoothProfile.STATE_CONNECTED) runCatching { profile?.disconnect(device) }
                return
            }
            connected = state == BluetoothProfile.STATE_CONNECTED
            if (!connected) stopSend()
            changed(when (state) {
                BluetoothProfile.STATE_CONNECTED -> "已连接：${deviceName(device)}"
                BluetoothProfile.STATE_CONNECTING -> "正在连接…"
                else -> "接收设备未连接"
            })
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (!closed && device == selected && type == BluetoothHidDevice.REPORT_TYPE_INPUT && id == 0.toByte()) {
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
            val accepted = runCatching {
                hid.registerApp(
                    BluetoothHidDeviceAppSdpSettings("LocalVoice HID", "Local voice text", "LocalVoice",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD, descriptor),
                    null, null, Executor { main.post(it) }, callback,
                )
            }.getOrDefault(false)
            if (!accepted) changed("无法注册蓝牙键盘，请检查设备支持情况后重试")
        }

        override fun onServiceDisconnected(type: Int) {
            if (closed) return
            stopSend()
            profile = null; registered = false; connected = false
            changed("蓝牙键盘服务已断开")
        }
    }

    fun start() {
        requested = runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE) }.getOrDefault(false)
        if (!requested) changed("此设备无法开启蓝牙键盘模式")
        else main.postDelayed({
            if (!closed && profile == null) changed("蓝牙键盘服务未响应，请重新开启")
        }, 5000)
    }

    fun devices(): List<BluetoothDevice> = runCatching { adapter.bondedDevices.toList().sortedBy(::deviceName) }.getOrDefault(emptyList())
    fun deviceName(device: BluetoothDevice): String = runCatching { device.name ?: device.address }.getOrDefault("蓝牙设备")

    fun connect(device: BluetoothDevice) {
        val hid = profile ?: return
        if (!registered || sending != null) return
        val previous = selected
        selected = device
        connected = false
        val status = runCatching {
            if (previous != null && previous != device) hid.disconnect(previous)
            if (hid.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                "已连接：${deviceName(device)}"
            } else if (hid.connect(device)) "正在连接…" else "无法连接，请确认接收设备支持蓝牙 HID"
        }.getOrDefault("无法连接接收设备")
        changed(status)
    }

    fun send(text: String, done: (Boolean) -> Unit): Boolean {
        val hid = profile ?: return false
        val device = selected ?: return false
        if (!ready) return false
        val keys = HidTextFrame.encode(text)
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
        stopSend()
        worker.shutdownNow()
        profile?.let { hid ->
            runCatching { hid.unregisterApp() }
            runCatching { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
        }
        profile = null; registered = false; connected = false
    }

    companion object {
        // Standard eight-byte keyboard input report, including usages F13–F24.
        private val descriptor = intArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x05, 0x07,
            0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x75, 0x08, 0x95, 0x01, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x73, 0x19, 0x00, 0x29, 0x73, 0x81, 0x00, 0xc0,
        ).map(Int::toByte).toByteArray()
    }
}
