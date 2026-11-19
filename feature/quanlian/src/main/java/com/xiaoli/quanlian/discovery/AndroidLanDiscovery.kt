package com.xiaoli.quanlian.discovery

import com.xiaoli.transfer.api.DeviceDiscovery
import com.xiaoli.transfer.api.DeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives the existing QuanLian v3 LAN discovery announcements.
 *
 * Wire compatibility: UDP 38889, multicast 224.0.0.167, JSON app=QUANLIAN,
 * v=3, id/name/port/fp. A discovered fingerprint is carried forward so the
 * connection layer can pin the peer certificate before transferring data.
 */
class AndroidLanDiscovery : DeviceDiscovery {
    companion object {
        private const val PORT = 38889
        private const val GROUP = "224.0.0.167"
        private const val APP = "QUANLIAN"
        private const val VERSION = 3
        private const val DEVICE_TTL_MS = 15_000L
    }

    private val running = AtomicBoolean(false)
    private val latest = ConcurrentHashMap<String, DeviceInfo>()
    private val state = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private var socket: MulticastSocket? = null
    private var worker: Thread? = null

    override val devices: Flow<List<DeviceInfo>> = state

    @Synchronized
    override fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ receiveLoop() }, "XiaoLi-QuanLian-Discovery").apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop() {
        var receiver: MulticastSocket? = null
        try {
            receiver = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                soTimeout = 1_000
            }
            socket = receiver
            joinMulticastOnAvailableInterfaces(receiver)

            val buffer = ByteArray(4096)
            while (running.get() && !receiver.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    receiver.receive(packet)
                    parseAnnouncement(packet)?.let { device ->
                        latest[device.id] = device
                        publishFreshDevices()
                    }
                } catch (_: SocketTimeoutException) {
                    publishFreshDevices()
                } catch (error: Exception) {
                    if (running.get()) android.util.Log.w("XiaoLiDiscovery", "Discovery packet rejected", error)
                }
            }
        } catch (error: Exception) {
            if (running.get()) android.util.Log.e("XiaoLiDiscovery", "Unable to listen on UDP $PORT", error)
        } finally {
            try { receiver?.close() } catch (_: Exception) {}
            socket = null
            running.set(false)
            latest.clear()
            state.value = emptyList()
        }
    }

    private fun joinMulticastOnAvailableInterfaces(receiver: MulticastSocket) {
        val group = InetAddress.getByName(GROUP)
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            try {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val hasIpv4 = networkInterface.interfaceAddresses.any { it.address is Inet4Address }
                if (hasIpv4 && networkInterface.supportsMulticast()) {
                    receiver.joinGroup(InetSocketAddress(group, PORT), networkInterface)
                    android.util.Log.i("XiaoLiDiscovery", "Joined $GROUP on ${networkInterface.name}")
                }
            } catch (error: Exception) {
                android.util.Log.d("XiaoLiDiscovery", "Multicast unavailable on ${networkInterface.name}", error)
            }
        }
    }

    private fun parseAnnouncement(packet: DatagramPacket): DeviceInfo? {
        val json = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
        if (json.optString("app") != APP || json.optInt("v", -1) != VERSION) return null

        val id = json.optString("id")
        val name = json.optString("name")
        val fingerprint = json.optString("fp")
        val port = json.optInt("port", -1)
        if (id.length !in 8..128 || name.isBlank() || name.length > 128) return null
        if (!fingerprint.matches(Regex("(?i)[0-9a-f]{64}"))) return null
        if (port !in 1..65535 || packet.address !is Inet4Address) return null

        return DeviceInfo(
            id = id,
            name = name,
            host = packet.address.hostAddress ?: return null,
            port = port,
            lastSeenEpochMillis = System.currentTimeMillis(),
            fingerprint = fingerprint.lowercase()
        )
    }

    private fun publishFreshDevices() {
        val now = System.currentTimeMillis()
        latest.entries.removeAll { now - it.value.lastSeenEpochMillis > DEVICE_TTL_MS }
        state.value = latest.values.sortedBy { it.name.lowercase() }
    }

    @Synchronized
    override fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        worker?.interrupt()
        worker = null
        latest.clear()
        state.value = emptyList()
    }
}
