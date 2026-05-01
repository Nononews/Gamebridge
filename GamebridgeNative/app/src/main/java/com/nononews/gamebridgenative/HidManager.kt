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
import java.util.concurrent.Executors

class HidManager(private val context: Context, private val webView: WebView) {
    private val TAG = "GamepadHID"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    var connectedHost: BluetoothDevice? = null
        private set
    private var targetDevice: BluetoothDevice? = null  // device we want to connect to

    // Device profile: "ps", "xbox", "generic", "racing"
    var currentProfile: String = "xbox"
    private var deviceName: String = "Xbox Wireless Controller"

    val isConnected: Boolean
        get() = connectedHost != null && hidDevice != null

    // HID Descriptor: Standard gamepad (16 buttons + 4 axes)
    private val HID_DESCRIPTOR = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x05.toByte(),
        0xa1.toByte(), 0x01.toByte(),
        0x85.toByte(), 0x01.toByte(),
        0x05.toByte(), 0x09.toByte(),
        0x19.toByte(), 0x01.toByte(),
        0x29.toByte(), 0x10.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x10.toByte(),
        0x81.toByte(), 0x02.toByte(),
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x30.toByte(),
        0x09.toByte(), 0x31.toByte(),
        0x09.toByte(), 0x32.toByte(),
        0x09.toByte(), 0x35.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x26.toByte(), 0xff.toByte(), 0x00.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x04.toByte(),
        0x81.toByte(), 0x02.toByte(),
        0xc0.toByte()
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
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
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

        bluetoothAdapter!!.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
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
        }, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
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
        context.registerReceiver(discoveryReceiver, filter)

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
            val sdp = BluetoothHidDeviceAppSdpSettings(
                deviceName,
                "Virtual Gamepad",
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

            device.registerApp(sdp, inQos, outQos, Executors.newSingleThreadExecutor(), object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    Log.i(TAG, "Bluetooth HID Profile Status: Registered=$registered | Dispositivo listo para ser hosteado.")
                    if (registered) {
                        notifyJS("window.onHidRegistered && window.onHidRegistered()")
                        
                        // Solo cuando el SDP está registrado exitosamente, habilitamos la visibilidad (Pairing Mode)
                        if (context is MainActivity) {
                            context.makeDiscoverable()
                        }
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

    fun cleanup() {
        try { context.unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        bluetoothAdapter?.cancelDiscovery()
    }

    private fun notifyJS(jsCode: String) {
        webView.post {
            webView.evaluateJavascript(jsCode, null)
        }
    }

    private fun escapeJS(s: String): String = "'${s.replace("'", "\\'")}'"
}
