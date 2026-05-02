package com.nononews.gamebridgenative

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

class UdpManager {
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private val targetPort = 9090
    private val sequence = AtomicLong(0)
    @Volatile private var sendFrequencyHz: Int = 60
    
    // Coroutine Scope strictly bound to IO dispatcher for non-blocking packet blasting
    private var udpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun ensureScope() {
        if (!udpScope.isActive) {
            udpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
    }

    fun connect(ip: String, pairingCode: String, tipo: Int, onConnected: (Boolean, String?, Int) -> Unit) {
        ensureScope()
        udpScope.launch {
            try {
                if (!isValidIpv4(ip)) {
                    onConnected(false, "IP invalida", 0)
                    return@launch
                }

                val status = fetchServerStatus(ip)
                if (status.optString("service") != "gamebridge" || !status.optBoolean("ready", false)) {
                    onConnected(false, "Servidor GameBridge no valido", 0)
                    return@launch
                }
                if (pairingCode.trim().length != 6) {
                    onConnected(false, "Codigo de emparejamiento invalido", 0)
                    return@launch
                }

                val pairResult = pairWithServer(ip, pairingCode.trim())
                if (!pairResult.optBoolean("ok", false) || pairResult.optString("service") != "gamebridge") {
                    val error = pairResult.optString("error")
                    val message = if (error == "RATE_LIMITED") {
                        "Demasiados intentos. Espera un minuto e intenta de nuevo"
                    } else {
                        "Codigo de emparejamiento incorrecto"
                    }
                    onConnected(false, message, 0)
                    return@launch
                }

                targetAddress = InetAddress.getByName(ip)
                sequence.set(0)
                if (socket == null || socket?.isClosed == true) {
                    socket = DatagramSocket()
                }

                // First packet is intentionally neutral: it proves UDP reachability and clears stale input.
                sendBinaryNow(tipo, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
                delay(80)

                val activeStatus = fetchServerStatus(ip)
                if (!activeStatus.optBoolean("connected", false)) {
                    onConnected(false, "No se confirmo recepcion UDP", 0)
                    return@launch
                }

                onConnected(true, null, activeStatus.optInt("slot", 0))
            } catch (e: Exception) {
                targetAddress = null
                onConnected(false, e.message ?: e.toString(), 0)
            }
        }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val value = part.toIntOrNull()
            value != null && value in 0..255
        }
    }

    private fun fetchServerStatus(ip: String): JSONObject {
        val url = URL("http://$ip:8080/api/status")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            useCaches = false
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Servidor respondio HTTP $code")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun pairWithServer(ip: String, code: String): JSONObject {
        val url = URL("http://$ip:8080/api/pair")
        val payload = JSONObject().put("code", code).toString().toByteArray(Charsets.UTF_8)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2500
            readTimeout = 2500
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(payload) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val result = JSONObject(body)
            if (connection.responseCode !in 200..299) {
                result.put("ok", false)
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    /** 
     * Packs the controller state into a tight 40-byte C-like struct and sends it via DatagramSocket.
     * Payload structure matches Python struct.unpack('<BHBIQffffff', data):
     * 1 byte: tipo
     * 2 bytes: btnBitmask
     * 1 byte: dpad
     * 4 bytes: sequence
     * 8 bytes: Unix timestamp in milliseconds
     * 4 bytes * 6: lt, rt, lsX, lsY, rsX, rsY (floats)
     */
    fun sendBinary(tipo: Int, btnBitmask: Int, dpad: Int, lt: Float, rt: Float, lsX: Float, lsY: Float, rsX: Float, rsY: Float) {
        ensureScope()
        udpScope.launch {
            try {
                sendBinaryNow(tipo, btnBitmask, dpad, lt, rt, lsX, lsY, rsX, rsY)
            } catch (e: Exception) {
                Log.e("UdpManager", "Error sending binary UDP packet", e)
            }
        }
    }

    private fun sendBinaryNow(tipo: Int, btnBitmask: Int, dpad: Int, lt: Float, rt: Float, lsX: Float, lsY: Float, rsX: Float, rsY: Float) {
        val ip = targetAddress ?: return
        val s = socket ?: return

        val seq = (sequence.getAndIncrement() % 4_294_967_296L).toInt()
        val timestampMs = System.currentTimeMillis()

        // 40 bytes total buffer configured as Little Endian natively to match struct.unpack('<')
        val buffer = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(tipo.toByte())
        buffer.putShort(btnBitmask.toShort())
        buffer.put(dpad.toByte())
        buffer.putInt(seq)
        buffer.putLong(timestampMs)
        buffer.putFloat(lt)
        buffer.putFloat(rt)
        buffer.putFloat(lsX)
        buffer.putFloat(lsY)
        buffer.putFloat(rsX)
        buffer.putFloat(rsY)

        val bytes = buffer.array()
        val packet = DatagramPacket(bytes, bytes.size, ip, targetPort)
        s.send(packet)
    }

    fun setSendFrequencyHz(hz: Int) {
        sendFrequencyHz = hz.coerceIn(30, 120)
    }

    fun getSendFrequencyHz(): Int = sendFrequencyHz

    /** Fallback for legacy JSON clients */
    fun sendPayload(jsonPayload: String) {
        val ip = targetAddress ?: return
        val s = socket ?: return
        
        ensureScope()
        udpScope.launch {
            try {
                val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(bytes, bytes.size, ip, targetPort)
                s.send(packet)
            } catch (e: Exception) {
                Log.e("UdpManager", "Error sending string UDP packet", e)
            }
        }
    }

    fun disconnect() {
        udpScope.cancel()
        socket?.close()
        socket = null
        targetAddress = null
        sequence.set(0)
    }
}
