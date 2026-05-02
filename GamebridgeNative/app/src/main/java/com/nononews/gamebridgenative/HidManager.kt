package com.nononews.gamebridgenative

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HidManager(private val context: Context, private val webView: WebView) {
    private val TAG = "GamepadHID"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var hidExecutor: ExecutorService? = null
    private var serviceListener: BluetoothProfile.ServiceListener? = null
    private var appRegistered = false
    var connectedHost: BluetoothDevice? = null
        private set
    private var targetDevice: BluetoothDevice? = null  // device we want to connect to

    // Device profile: "ps", "xbox", "generic", "racing"
    var currentProfile: String = "xbox"
    private var deviceName: String = "Xbox Wireless Controller"

    val isConnected: Boolean
        get() = connectedHost != null && hidDevice != null

    // HID Descriptor: Generic gamepad (16 buttons + D-pad hat + sticks + triggers)
    private val HID_DESCRIPTOR = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),       // Usage Page (Generic Desktop)
        0x09.toByte(), 0x05.toByte(),       // Usage (Game Pad)
        0xa1.toByte(), 0x01.toByte(),       // Collection (Application)
        0x85.toByte(), 0x01.toByte(),       //   Report ID (1)
        0x05.toByte(), 0x09.toByte(),       //   Usage Page (Button)
        0x19.toByte(), 0x01.toByte(),       //   Usage Minimum (1)
        0x29.toByte(), 0x10.toByte(),       //   Usage Maximum (16)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),       //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
        0x95.toByte(), 0x10.toByte(),       //   Report Count (16)
        0x81.toByte(), 0x02.toByte(),       //   Input (Data,Var,Abs)
        0x05.toByte(), 0x01.toByte(),       //   Usage Page (Generic Desktop)
        0x09.toByte(), 0x39.toByte(),       //   Usage (Hat switch)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x25.toByte(), 0x07.toByte(),       //   Logical Maximum (7)
        0x35.toByte(), 0x00.toByte(),       //   Physical Minimum (0)
        0x46.toByte(), 0x3b.toByte(), 0x01.toByte(), // Physical Maximum (315)
        0x65.toByte(), 0x14.toByte(),       //   Unit (English Rotation, Degrees)
        0x75.toByte(), 0x04.toByte(),       //   Report Size (4)
        0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
        0x81.toByte(), 0x42.toByte(),       //   Input (Data,Var,Abs,Null)
        0x65.toByte(), 0x00.toByte(),       //   Unit (None)
        0x75.toByte(), 0x04.toByte(),       //   Report Size (4)
        0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
        0x81.toByte(), 0x03.toByte(),       //   Input (Const,Var,Abs)
        0x09.toByte(), 0x30.toByte(),       //   Usage (X)
        0x09.toByte(), 0x31.toByte(),       //   Usage (Y)
        0x09.toByte(), 0x33.toByte(),       //   Usage (Rx)
        0x09.toByte(), 0x34.toByte(),       //   Usage (Ry)
        0x09.toByte(), 0x32.toByte(),       //   Usage (Z)
        0x09.toByte(), 0x35.toByte(),       //   Usage (Rz)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x26.toByte(), 0xff.toByte(), 0x00.toByte(), // Logical Maximum (255)
        0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
        0x95.toByte(), 0x06.toByte(),       //   Report Count (6)
        0x81.toByte(), 0x02.toByte(),       //   Input (Data,Var,Abs)
        0xc0.toByte()                       // End Collection
    )

    // BroadcastReceiver to catch discovered Bluetooth devices
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else
                            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                    device?.let { sendDeviceToJS(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    notifyJS("window.onBluetoothScanFinished && window.onBluetoothScanFinished()")
                }
            }
        }
    }

    fun setProfile(type: String) {
        currentProfile = type
        deviceName = when (type) {
            "ps"      -> "Wireless Controller"
            "xbox"    -> "Xbox Wireless Controller"
            "generic" -> "GameBridge Controller"
            "racing"  -> "GameBridge Wheel"
            else      -> "GameBridge Controller"
        }
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermissions()) {
            notifyJS("window.onBluetoothError && window.onBluetoothError('NO_PERMISSIONS_GRANTED')")
            return
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth no disponible o desactivado")
            notifyJS("window.onBluetoothError && window.onBluetoothError('BT_DISABLED')")
            return
        }

        // Renombrar el adaptador ahora que estamos seguros de tener permisos y estar iniciando
        try {
            bluetoothAdapter!!.name = deviceName
            Log.i(TAG, "BT adapter renamed to: $deviceName")
        } catch (e: Exception) {
            Log.w(TAG, "Could not rename adapter: ${e.message}")
        }

        cleanupHidRegistration()

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    registerApp()
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                }
            }
        }
        serviceListener = listener
        bluetoothAdapter!!.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!hasPermissions()) {
            notifyJS("window.onBluetoothError && window.onBluetoothError('NO_PERMISSIONS_GRANTED')")
            return
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            notifyJS("window.onBluetoothError && window.onBluetoothError('BT_DISABLED')")
            return
        }

        // Register receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try { context.unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(discoveryReceiver, filter)
        }

        if (bluetoothAdapter!!.isDiscovering) bluetoothAdapter!!.cancelDiscovery()
        bluetoothAdapter!!.startDiscovery()
        Log.i(TAG, "Discovery started")
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
        try { context.unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun sendDeviceToJS(device: BluetoothDevice) {
        val name = try { device.name ?: "Unknown" } catch (_: Exception) { "Unknown" }
        val address = device.address ?: ""
        val deviceClass = device.bluetoothClass?.majorDeviceClass ?: -1

        // BluetoothClass.Device.Major.COMPUTER = 0x0100
        val isComputer = deviceClass == 0x0100

        val jsCode = "window.onDeviceFound && window.onDeviceFound(${escapeJS(name)}, '${address}', ${isComputer})"
        notifyJS(jsCode)
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        // Obsoleto en Modo Pasivo
        notifyJS("window.onBluetoothError && window.onBluetoothError('DEVICE_NOT_FOUND')")
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        hidDevice?.let { device ->
            hidExecutor?.shutdownNow()
            val executor = Executors.newSingleThreadExecutor()
            hidExecutor = executor

            val sdp = BluetoothHidDeviceAppSdpSettings(
                deviceName,
                "Generic HID Gamepad",
                "GameBridge",
                BluetoothHidDevice.SUBCLASS2_GAMEPAD,
                HID_DESCRIPTOR
            )

            // QoS (L2CAP) configuration for low-latency input
            val inQos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
            )
            val outQos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
            )

            device.registerApp(sdp, inQos, outQos, executor, object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    appRegistered = registered
                    Log.i(TAG, "Bluetooth HID Profile Status: Registered=$registered | Dispositivo listo para ser hosteado.")
                    if (registered) {
                        notifyJS("window.onHidRegistered && window.onHidRegistered()")
                        
                        // Solo cuando el SDP está registrado exitosamente, habilitamos la visibilidad (Pairing Mode)
                        if (context is MainActivity) {
                            context.makeDiscoverable()
                        }
                    } else {
                        notifyJS("window.onBluetoothError && window.onBluetoothError('BT_REGISTER_FAILED')")
                    }
                }

                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                    Log.i(TAG, "Connection State Changed: $state for ${device.name}")
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connectedHost = device
                            val name = try { device.name ?: "PC" } catch (_: Exception) { "PC" }
                            notifyJS("window.onDeviceConnected && window.onDeviceConnected(${escapeJS(name)})")
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            sendNeutralReport()
                            connectedHost = null
                            notifyJS("window.onDeviceDisconnected && window.onDeviceDisconnected()")
                        }
                    }
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun sendReport(report: ByteArray) {
        if (isConnected) {
            hidDevice?.sendReport(connectedHost, 1, report)
        }
    }

    fun sendGamepadState(btnBitmask: Int, dpad: Int, lt: Float, rt: Float, lsX: Float, lsY: Float, rsX: Float, rsY: Float) {
        sendReport(buildGamepadReport(btnBitmask, dpad, lt, rt, lsX, lsY, rsX, rsY))
    }

    fun cleanup() {
        try { context.unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        bluetoothAdapter?.cancelDiscovery()
        cleanupHidRegistration()
    }

    @SuppressLint("MissingPermission")
    private fun cleanupHidRegistration() {
        sendNeutralReport()

        hidDevice?.let { device ->
            try {
                if (appRegistered) device.unregisterApp()
            } catch (e: Exception) {
                Log.w(TAG, "Could not unregister HID app: ${e.message}")
            }
            try {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
            } catch (e: Exception) {
                Log.w(TAG, "Could not close HID proxy: ${e.message}")
            }
        }

        appRegistered = false
        connectedHost = null
        hidDevice = null
        serviceListener = null
        hidExecutor?.shutdownNow()
        hidExecutor = null
    }

    private fun sendNeutralReport() {
        if (connectedHost != null && hidDevice != null) {
            sendReport(buildGamepadReport(0, 0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f))
        }
    }

    private fun buildGamepadReport(btnBitmask: Int, dpad: Int, lt: Float, rt: Float, lsX: Float, lsY: Float, rsX: Float, rsY: Float): ByteArray {
        val report = ByteArray(9)
        val hat = if (dpad in 1..8) dpad - 1 else 8
        report[0] = (btnBitmask and 0xFF).toByte()
        report[1] = ((btnBitmask shr 8) and 0xFF).toByte()
        report[2] = (hat and 0x0F).toByte()
        report[3] = axisToByte(lsX)
        report[4] = axisToByte(lsY)
        report[5] = axisToByte(rsX)
        report[6] = axisToByte(rsY)
        report[7] = triggerToByte(lt)
        report[8] = triggerToByte(rt)
        return report
    }

    private fun axisToByte(value: Float): Byte {
        return ((value.coerceIn(-1.0f, 1.0f) + 1.0f) * 127.5f).toInt().coerceIn(0, 255).toByte()
    }

    private fun triggerToByte(value: Float): Byte {
        return (value.coerceIn(0.0f, 1.0f) * 255.0f).toInt().coerceIn(0, 255).toByte()
    }

    private fun notifyJS(jsCode: String) {
        webView.post {
            webView.evaluateJavascript(jsCode, null)
        }
    }

    private fun escapeJS(s: String): String = "'${s.replace("'", "\\'")}'"
}
