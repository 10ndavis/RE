package dev.myotv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import java.util.ArrayDeque
import java.util.UUID

/**
 * Finds a Myo over Bluetooth LE, configures it and streams its events.
 *
 * All callbacks are delivered on [handler]'s thread. GATT operations must run one at a time
 * on Android, so they go through a small queue that advances on each completion callback.
 */
@SuppressLint("MissingPermission") // Permissions are checked by MainActivity before the service starts.
class MyoConnection(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(message: String)
        fun onConnected(address: String)
        fun onDisconnected()
        fun onClassifierEvent(event: MyoProtocol.ClassifierEvent)
        fun onImu(sample: MyoProtocol.ImuSample)
        fun onBattery(percent: Int)
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val prefs = context.getSharedPreferences("myo", Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var stopped = true
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var opInFlight = false

    fun start() {
        stopped = false
        if (adapter == null || !adapter.isEnabled) {
            listener.onStatus("Bluetooth is off")
            return
        }
        val saved = prefs.getString(KEY_ADDRESS, null)
        if (saved != null) {
            listener.onStatus("Connecting to Myo $saved")
            connect(adapter.getRemoteDevice(saved))
        } else {
            scan()
        }
    }

    fun stop() {
        stopped = true
        stopScan()
        val g = gatt ?: return
        gatt = null
        ops.clear()
        opInFlight = false
        // Let the armband sleep normally again once we're done with it, then hang up.
        runCatching { write(g, MyoProtocol.COMMAND_CHAR, MyoProtocol.normalSleepCommand()) }
        handler.postDelayed({
            g.disconnect()
            g.close()
        }, 300)
    }

    /** Forget the paired armband and scan for one again. */
    fun forget() {
        prefs.edit().remove(KEY_ADDRESS).apply()
        stop()
        start()
    }

    fun vibrate(kind: MyoProtocol.Vibration) {
        enqueue { write(it, MyoProtocol.COMMAND_CHAR, MyoProtocol.vibrateCommand(kind)) }
    }

    private fun scan() {
        val scanner = adapter.bluetoothLeScanner ?: return listener.onStatus("Bluetooth LE unavailable")
        listener.onStatus("Searching for a Myo. Wake it by moving it or plugging it in briefly.")
        scanning = true
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        handler.postDelayed({
            if (scanning) {
                stopScan()
                if (!stopped) scan()
            }
        }, 30_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
            val isMyo = MyoProtocol.CONTROL_SERVICE in uuids || result.scanRecord?.deviceName?.contains("Myo", true) == true
            if (!isMyo || !scanning) return
            handler.post {
                if (!scanning) return@post
                stopScan()
                listener.onStatus("Found Myo ${result.device.address}")
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { listener.onStatus("Bluetooth scan failed ($errorCode)") }
        }
    }

    private fun connect(device: BluetoothDevice) {
        gatt?.close()
        // autoConnect=true makes Android reconnect on its own whenever the armband comes back in range.
        gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE, 0, handler)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onStatus("Connected, setting up armband")
                ops.clear()
                opInFlight = false
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ops.clear()
                opInFlight = false
                listener.onDisconnected()
                if (!stopped) listener.onStatus("Myo disconnected; waiting for it to come back")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g.getService(MyoProtocol.CONTROL_SERVICE) == null) {
                listener.onStatus("That device isn't a Myo; searching again")
                prefs.edit().remove(KEY_ADDRESS).apply()
                g.close()
                gatt = null
                scan()
                return
            }
            prefs.edit().putString(KEY_ADDRESS, g.device.address).apply()
            enqueue { subscribe(it, MyoProtocol.CLASSIFIER_SERVICE, MyoProtocol.CLASSIFIER_EVENT_CHAR, indicate = true) }
            enqueue { subscribe(it, MyoProtocol.IMU_SERVICE, MyoProtocol.IMU_DATA_CHAR, indicate = false) }
            enqueue { subscribe(it, MyoProtocol.BATTERY_SERVICE, MyoProtocol.BATTERY_LEVEL_CHAR, indicate = false) }
            enqueue { write(it, MyoProtocol.COMMAND_CHAR, MyoProtocol.neverSleepCommand()) }
            enqueue { write(it, MyoProtocol.COMMAND_CHAR, MyoProtocol.setModeCommand()) }
            enqueue { write(it, MyoProtocol.COMMAND_CHAR, MyoProtocol.unlockHoldCommand()) }
            enqueue { g2 -> g2.getService(MyoProtocol.BATTERY_SERVICE)?.getCharacteristic(MyoProtocol.BATTERY_LEVEL_CHAR)?.let { g2.readCharacteristic(it) } ?: false }
            enqueue {
                listener.onConnected(g.device.address)
                false
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = next()

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = next()

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onValue(c.uuid, value)
            next()
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                onValue(c.uuid, c.value ?: ByteArray(0))
                next()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onValue(c.uuid, value)

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) onValue(c.uuid, c.value ?: ByteArray(0))
        }
    }

    private fun onValue(uuid: UUID, value: ByteArray) {
        when (uuid) {
            MyoProtocol.CLASSIFIER_EVENT_CHAR -> MyoProtocol.parseClassifierEvent(value)?.let(listener::onClassifierEvent)
            MyoProtocol.IMU_DATA_CHAR -> MyoProtocol.parseImu(value)?.let(listener::onImu)
            MyoProtocol.BATTERY_LEVEL_CHAR -> if (value.isNotEmpty()) listener.onBattery(value[0].toInt() and 0xff)
        }
    }

    /** Each op returns true if it started an asynchronous GATT operation whose callback will call [next]. */
    private fun enqueue(op: (BluetoothGatt) -> Boolean) {
        ops.add(op)
        if (!opInFlight) next()
    }

    private fun next() {
        opInFlight = false
        val g = gatt ?: return
        while (ops.isNotEmpty()) {
            val op = ops.poll() ?: return
            if (runCatching { op(g) }.getOrDefault(false)) {
                opInFlight = true
                return
            }
        }
    }

    private fun subscribe(g: BluetoothGatt, service: UUID, char: UUID, indicate: Boolean): Boolean {
        val c = g.getService(service)?.getCharacteristic(char) ?: return false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(MyoProtocol.CCCD) ?: return false
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun write(g: BluetoothGatt, char: UUID, value: ByteArray): Boolean {
        val c = g.getService(MyoProtocol.CONTROL_SERVICE)?.getCharacteristic(char) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    companion object {
        private const val KEY_ADDRESS = "address"
    }
}
