package com.nononews.gamebridgenative

import android.webkit.JavascriptInterface

class AndroidBridge(private val activity: MainActivity) {
    private var currentProfile: String = "xbox"

    /** Called by JS when user picks a controller type (ps, xbox, generic, racing) */
    @JavascriptInterface
    fun setConfig(profileType: String) {
        currentProfile = profileType
    }

    /** Rotate screen: landscape=true for gamepad, false=portrait for menu */
    @JavascriptInterface
    fun setOrientation(landscape: Boolean) {
        activity.runOnUiThread {
            activity.setOrientation(landscape)
        }
    }

    private val udpManager = UdpManager()

    /** Connect to PC Server via UDP Wi-Fi */
    @JavascriptInterface
    fun conectarRedLocal(ip: String, pairingCode: String) {
        val tipo = when (currentProfile) {
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

    /** Clear current Wi-Fi input state. */
    @JavascriptInterface
    fun resetRedLocal() {
        val tipo = when (currentProfile) {
            "xbox" -> 1
            "ps" -> 2
            "racing" -> 3
            else -> 0
        }
        udpManager.sendBinary(tipo, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
    }

    /** Send binary structs directly to PC via Coroutines. */
    @JavascriptInterface
    fun enviarUDPBinario(tipo: Int, btnBitmask: Int, dpad: Int, lt: Float, rt: Float, lsX: Float, lsY: Float, rsX: Float, rsY: Float) {
        // Send via Wi-Fi UDP
        udpManager.sendBinary(tipo, btnBitmask, dpad, lt, rt, lsX, lsY, rsX, rsY)
    }

    private fun escapeJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\r", " ")
    }
}
