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
            this@HidKeyboard.registered = registered
            if (!registered) {
                stopSend()
                host = null
                changed("蓝牙键盘已停用，请重新开启")
                return
            }
            // Reconcile an already established link if its callback preceded registration.
            val connected = runCatching { profile?.connectedDevices.orEmpty() }.getOrDefault(emptyList())
            connected.forEach(::acceptHost)
            if (host == null) changed(WAITING_FOR_HOST)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (closed || profile == null || !registered) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> acceptHost(device)
                BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device != host) return
                    val interrupted = sending != null
                    stopSend()
                    host = null
                    changed(if (interrupted) "发送中断；请从 Mac 重新连接" else WAITING_FOR_HOST)
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
            profile = null; registered = false; host = null
            changed("蓝牙键盘服务已断开")
        }
    }

    fun start() {
        val requested = runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE) }.getOrDefault(false)
        if (!requested) changed("此设备无法开启蓝牙键盘模式")
        else main.postDelayed({
            if (!closed && profile == null) changed("蓝牙键盘服务未响应，请重新开启")
        }, 5000)
    }

    private fun acceptHost(device: BluetoothDevice) {
        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        if (!bonded || (host != null && host != device)) {
            runCatching { profile?.disconnect(device) }
            return
        }
        if (host == device) return
        host = device
        val name = runCatching { device.name ?: device.address }.getOrDefault("接收设备")
        changed("已连接：$name")
    }

    enum class MacSetupKey(val label: String, val usage: Int) {
        LEFT_SHIFT_NEIGHBOR("Z", 0x1d),
        RIGHT_SHIFT_NEIGHBOR("/", 0x38),
    }

    fun sendSetupKey(key: MacSetupKey, done: (Boolean) -> Unit): Boolean =
        sendKeys(intArrayOf(key.usage), done)

    fun send(text: String, done: (Boolean) -> Unit): Boolean =
        sendKeys(HidTextFrame.encode(text), done)

    private fun sendKeys(keys: IntArray, done: (Boolean) -> Unit): Boolean {
        val hid = profile ?: return false
        val device = host ?: return false
        if (!ready) return false
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
        profile = null; registered = false; host = null
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
