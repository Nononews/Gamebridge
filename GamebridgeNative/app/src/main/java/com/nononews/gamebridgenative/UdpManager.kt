package com.nononews.gamebridgenative

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpManager {
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private val targetPort = 9090
    
    // Coroutine Scope strictly bound to IO dispatcher for non-blocking packet blasting
    private val udpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect(ip: String, onConnected: (Boolean, String?) -> Unit) {
        udpScope.launch {
            try {
                targetAddress = InetAddress.getByName(ip)
                if (socket == null || socket?.isClosed == true) {
                    socket = DatagramSocket()
                }
                onConnected(true, null)
            } catch (e: Exception) {
                targetAddress = null
                onConnected(false, e.toString())
            }
        }
    }

    /** 
     * Packs the controller state into a tight 28-byte C-like struct and sends it via DatagramSocket
     * Payload structure matches Python struct.unpack('<BHBffffff', data):
     * 1 byte: tipo
     * 2 bytes: btnBitmask
     * 1 byte: dpad
     * 4 bytes * 6: lt, rt, lsX, lsY, rsX, rsY (floats)
     */
    fun sendBinary(tipo: Int, btnBitmask: Int, dpad: Int, lt: Float, rt: Float, lsX: Float, lsY: Float, rsX: Float, rsY: Float) {
        val ip = targetAddress ?: return
        val s = socket ?: return
        
        udpScope.launch {
            try {
                // 28 bytes total buffer configured as Little Endian natively to match struct.unpack('<')
                val buffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                buffer.put(tipo.toByte())
                buffer.putShort(btnBitmask.toShort())
                buffer.put(dpad.toByte())
                buffer.putFloat(lt)
                buffer.putFloat(rt)
                buffer.putFloat(lsX)
                buffer.putFloat(lsY)
                buffer.putFloat(rsX)
                buffer.putFloat(rsY)
                
                val bytes = buffer.array()
                val packet = DatagramPacket(bytes, bytes.size, ip, targetPort)
                s.send(packet)
            } catch (e: Exception) {
                Log.e("UdpManager", "Error sending binary UDP packet", e)
            }
        }
    }

    /** Fallback for legacy JSON clients */
    fun sendPayload(jsonPayload: String) {
        val ip = targetAddress ?: return
        val s = socket ?: return
        
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
    }
}
