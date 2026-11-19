package com.xiaoli.quanlian.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.xiaoli.transfer.android.DeviceIdentity
import com.xiaoli.transfer.android.LanDiscovery
import com.xiaoli.transfer.api.DeviceDiscovery
import com.xiaoli.transfer.api.DeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapter from the existing QuanLian v3 UDP discovery wire protocol to the
 * launcher feature API. The receiver service advertises the same identity.
 */
class AndroidLanDiscovery(private val context: Context) : DeviceDiscovery {
    private val running = AtomicBoolean(false)
    private val latest = ConcurrentHashMap<String, DeviceInfo>()
    private val state = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val handler = Handler(Looper.getMainLooper())
    private val pruneDevices = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val cutoff = System.currentTimeMillis() - 15_000L
            val iterator = latest.entries.iterator()
            var changed = false
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.lastSeenEpochMillis < cutoff) {
                    latest.remove(entry.key)
                    changed = true
                }
            }
            if (changed) state.value = latest.values.sortedBy { it.name.lowercase() }
            handler.postDelayed(this, 3_000L)
        }
    }
    private var listener: LanDiscovery.Listener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override val devices: Flow<List<DeviceInfo>> = state

    @Synchronized
    override fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("XiaoLiQuanLianDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            listener = LanDiscovery.listen { announcement ->
                if (!running.get() || announcement == null ||
                    DeviceIdentity.id(context) == announcement.id) return@listen
                latest[announcement.id] = DeviceInfo(
                    id = announcement.id,
                    name = announcement.name,
                    host = announcement.host.hostAddress ?: return@listen,
                    port = announcement.port,
                    lastSeenEpochMillis = System.currentTimeMillis(),
                    fingerprint = announcement.fingerprint
                )
                state.value = latest.values.sortedBy { it.name.lowercase() }
            }
            handler.removeCallbacks(pruneDevices)
            handler.postDelayed(pruneDevices, 3_000L)
        } catch (error: Exception) {
            running.set(false)
            releaseMulticastLock()
            android.util.Log.e("XiaoLiDiscovery", "Unable to start LAN discovery", error)
        }
    }

    @Synchronized
    override fun stop() {
        running.set(false)
        handler.removeCallbacks(pruneDevices)
        listener?.close()
        listener = null
        latest.clear()
        state.value = emptyList()
        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            multicastLock = null
        }
    }
}
