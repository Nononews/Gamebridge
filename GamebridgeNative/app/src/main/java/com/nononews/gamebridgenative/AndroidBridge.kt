package com.nononews.gamebridgenative

import android.bluetooth.BluetoothAdapter
import android.webkit.JavascriptInterface

class AndroidBridge(private val activity: MainActivity, private val hidManager: HidManager) {

    /** Called by JS when user picks a controller type (ps, xbox, generic, racing) */
    @JavascriptInterface
    fun setConfig(profileType: String) {
        hidManager.setProfile(profileType)
        // Request Bluetooth permissions NOW (after user explicitly chose a controller)
        activity.runOnUiThread {
            activity.requestPermissionsIfNeeded()
        }
    }

    /** Rotate screen: landscape=true for gamepad, false=portrait for menu */
    @JavascriptInterface
    fun setOrientation(landscape: Boolean) {
        activity.runOnUiThread {
            activity.setOrientation(landscape)
        }
    }

    /** Activate Bluetooth if off, then start HID registration (Discoverable mode triggered AFTER SDP registration) */
    @JavascriptInterface
    fun conectarBluetooth() {
        if (!hidManager.hasPermissions()) {
            activity.runOnUiThread {
                activity.webView.evaluateJavascript("window.onBluetoothError && window.onBluetoothError('NO_PERMISSIONS_GRANTED')", null)
            }
            return
        }
        
        activity.runOnUiThread {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && !adapter.isEnabled) {
                // Pedir que se encienda Bluetooth explícitamente
                activity.requestEnableBluetoothThenStart()
                return@runOnUiThread
            }
            
            // Arrancar el motor HID (El modo discoverable se lanzará tras registro SDP exitoso)
            hidManager.start()
        }
    }

    /** Start scanning for nearby Bluetooth devices */
    @JavascriptInterface
    fun iniciarEscaneo() {
        if (!hidManager.hasPermissions()) {
            activity.runOnUiThread {
                activity.webView.evaluateJavascript("window.onBluetoothError && window.onBluetoothError('NO_PERMISSIONS_GRANTED')", null)
            }
            return
        }
        hidManager.startDiscovery()
    }

    /** Stop Bluetooth scan */
    @JavascriptInterface
    fun detenerEscaneo() {
        hidManager.stopDiscovery()
    }

    /** Connect to a specific device by MAC address */
    @JavascriptInterface
    fun conectarDispositivo(address: String) {
        hidManager.connectToDevice(address)
    }

    private val udpManager = UdpManager()

    /** Connect to PC Server via UDP Wi-Fi */
    @JavascriptInterface
    fun conectarRedLocal(ip: String, pairingCode: String) {
        val tipo = when (hidManager.currentProfile) {
            "xbox" -> 1
            "ps" -> 2
            "racing" -> 3
            else -> 0
        }

        udpManager.connect(ip, pairingCode, tipo) { success, errorMsg, slot ->
            activity.runOnUiThread {
                if (success) {
                    activity.webView.evaluateJavascript("window.onNetworkConnected && window.onNetworkConnected('${escapeJs(ip)}', $slot)", null)
                } else {
                    val safeError = escapeJs(errorMsg ?: "Unknown error")
                    activity.webView.evaluateJavascript("window.onNetworkError && window.onNetworkError('$safeError')", null)
                }
            }
        }
    }

    /** Clear current Wi-Fi input state without tearing down Bluetooth. */
    @JavascriptInterface
    fun resetRedLocal() {
        val tipo = when (hidManager.currentProfile) {
            "xbox" -> 1
            "ps" -> 2
            "racing" -> 3
            else -> 0
        }
        udpManager.sendBinary(tipo, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
    }

    /** Send binary structs directly to PC via Coroutines (And Bluetooth HID simultaneously) */
    @JavascriptInterface
    fun enviarUDPBinario(tipo: Int, btnBitmask: Int, dpad: Int, lt: Float, rt: Float, lsX: Float, lsY: Float, rsX: Float, rsY: Float) {
        // Send via Wi-Fi UDP
        udpManager.sendBinary(tipo, btnBitmask, dpad, lt, rt, lsX, lsY, rsX, rsY)
        
        // Send via Protocolo Bluetooth Nativo si esta conectado
        if (hidManager.isConnected) {
            hidManager.sendGamepadState(btnBitmask, dpad, lt, rt, lsX, lsY, rsX, rsY)
        }
    }

    /** Send JSON state to PC Server via UDP for Wi-Fi Mode */
    @JavascriptInterface
    fun enviarEstadoRedLocal(jsonPayload: String) {
        udpManager.sendPayload(jsonPayload)
    }

    /** Send gamepad report: 16-bit buttons + 4 analog axes (0-255) */
    @JavascriptInterface
    fun enviarReporte(buttons: Int, leftX: Int, leftY: Int, rightX: Int, rightY: Int) {
        if (!hidManager.isConnected) return
        val report = ByteArray(9)
        report[0] = (buttons and 0xFF).toByte()
        report[1] = ((buttons shr 8) and 0xFF).toByte()
        report[2] = 8.toByte()
        report[3] = leftX.toByte()
        report[4] = leftY.toByte()
        report[5] = rightX.toByte()
        report[6] = rightY.toByte()
        report[7] = 0.toByte()
        report[8] = 0.toByte()
        hidManager.sendReport(report)
    }

    private fun escapeJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\r", " ")
    }
}
