package app.onesvpn

import android.os.SystemClock
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** These readings originate from libbox's native status stream, never Android UID estimates. */
internal data class TrafficSnapshot(
    val available: Boolean = false,
    val uploadBps: Long = 0L,
    val downloadBps: Long = 0L,
    val uploadedBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val connectionsIn: Int = 0,
    val connectionsOut: Int = 0,
    val measuredAtElapsedMs: Long = 0L,
) {
    fun isFresh(nowElapsedMs: Long, maxAgeMs: Long = 4_000L): Boolean =
        available && measuredAtElapsedMs > 0L && nowElapsedMs >= measuredAtElapsedMs &&
            nowElapsedMs - measuredAtElapsedMs <= maxAgeMs
}

internal object VpnTraffic {
    private val mutableState = MutableStateFlow(TrafficSnapshot())
    val state = mutableState.asStateFlow()

    fun reset() {
        mutableState.value = TrafficSnapshot()
    }

    fun update(value: TrafficSnapshot) {
        mutableState.value = value
    }
}

/**
 * Owns a single local libbox command client. All client calls run off the main and
 * native-engine threads. Epoch checks discard late callbacks after stop/reconnect.
 */
internal class VpnTrafficMonitor {
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong(0L)
    private var client: CommandClient? = null // worker only
    private var closed = false // synchronized on this

    @Synchronized fun start() {
        if (closed) return
        val session = epoch.incrementAndGet()
        VpnTraffic.reset()
        worker.execute {
            closeClient()
            if (session != epoch.get()) return@execute
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                statusInterval = 1_000_000_000L // libbox interval is nanoseconds
            }
            val handler = object : CommandClientHandler {
                override fun connected() {}
                override fun disconnected(message: String) {
                    synchronized(this@VpnTrafficMonitor) {
                        if (session == epoch.get()) VpnTraffic.reset()
                    }
                }
                override fun writeStatus(message: StatusMessage) {
                    synchronized(this@VpnTrafficMonitor) {
                        if (session != epoch.get()) return
                        if (!message.trafficAvailable) {
                            VpnTraffic.reset()
                            return
                        }
                        VpnTraffic.update(TrafficSnapshot(
                            available = true,
                            uploadBps = message.uplink.coerceAtLeast(0L),
                            downloadBps = message.downlink.coerceAtLeast(0L),
                            uploadedBytes = message.uplinkTotal.coerceAtLeast(0L),
                            downloadedBytes = message.downlinkTotal.coerceAtLeast(0L),
                            connectionsIn = message.connectionsIn.coerceAtLeast(0),
                            connectionsOut = message.connectionsOut.coerceAtLeast(0),
                            measuredAtElapsedMs = SystemClock.elapsedRealtime(),
                        ))
                    }
                }
                override fun clearLogs() {}
                override fun setDefaultLogLevel(level: Int) {}
                override fun writeLogs(message: LogIterator) {}
                override fun writeGroups(message: OutboundGroupIterator) {}
                override fun writeOutbounds(message: OutboundGroupItemIterator) {}
                override fun initializeClashMode(modes: StringIterator, currentMode: String) {}
                override fun updateClashMode(mode: String) {}
                override fun writeConnectionEvents(message: ConnectionEvents) {}
            }
            var pending: CommandClient? = null
            try {
                pending = Libbox.newCommandClient(handler, options)
                pending.connect()
                if (session == epoch.get()) {
                    client = pending
                } else {
                    pending.disconnect()
                }
            } catch (_: Exception) {
                runCatching { pending?.disconnect() }
                if (session == epoch.get()) VpnTraffic.reset()
            }
        }
    }

    @Synchronized fun stop() {
        epoch.incrementAndGet()
        VpnTraffic.reset()
        if (!closed) worker.execute { closeClient() }
    }

    @Synchronized fun close() {
        if (closed) return
        epoch.incrementAndGet()
        closed = true
        VpnTraffic.reset()
        worker.execute { closeClient() }
        worker.shutdown() // drain teardown; never run native disconnect on UI thread
    }

    private fun closeClient() {
        val previous = client
        client = null
        if (previous != null) runCatching { previous.disconnect() }
    }
}
