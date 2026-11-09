package app.onesvpn

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class DiagnosticEvent {
    CONNECT_REQUESTED, CONFIG_VALID, CONFIG_INVALID, TUN_CREATED, ENGINE_STARTING, ENGINE_STARTED,
    PROBE_STARTED, PROBE_OK, CONNECT_INTENT_SAVE_FAILED, DNS_ERROR, TLS_ERROR, CONNECTION_TIMEOUT, CONNECTION_ERROR,
    UNDERLYING_NETWORK_READY, UNDERLYING_NETWORK_LOST, NETWORK_MONITOR_ERROR, SOCKET_PROTECT_FAILED,
    STOP_REQUESTED, ENGINE_CLOSE_FAILED, TUN_CLOSED, SERVICE_STOPPED, NATIVE_ERROR,
    PHYSICAL_NETWORK_WAIT, NETWORK_HANDOVER_WAIT, STALE_NETWORK_PROBE,
    HEALTH_PROBE_RECHECK, HEALTH_PROBE_TUN_HELD, VPN_RECOVERED
}

/** Only allowlisted codes leave this boundary. No exception messages, native text or profiles. */
object Diagnostics {
    private val mutableEvents = MutableStateFlow<List<String>>(emptyList())
    val events = mutableEvents.asStateFlow()
    @Synchronized fun record(event: DiagnosticEvent) {
        val line = "${System.currentTimeMillis()} ${event.name}"
        if (mutableEvents.value.lastOrNull()?.substringAfter(' ') == event.name) return
        mutableEvents.value = (mutableEvents.value + line).takeLast(80)
        Log.i("OnesVpn", event.name)
    }
    fun failure(error: Throwable): DiagnosticEvent = when (error) {
        is UnknownHostException -> DiagnosticEvent.DNS_ERROR
        is SocketTimeoutException -> DiagnosticEvent.CONNECTION_TIMEOUT
        is SSLException -> DiagnosticEvent.TLS_ERROR
        else -> DiagnosticEvent.CONNECTION_ERROR
    }
    fun nativeEvent(message: String): DiagnosticEvent {
        val text = message.lowercase()
        return when {
            "dns" in text || "resolve" in text -> DiagnosticEvent.DNS_ERROR
            "tls" in text || "certificate" in text || "reality" in text -> DiagnosticEvent.TLS_ERROR
            "timeout" in text || "deadline" in text -> DiagnosticEvent.CONNECTION_TIMEOUT
            "connect" in text || "network" in text -> DiagnosticEvent.CONNECTION_ERROR
            else -> DiagnosticEvent.NATIVE_ERROR
        }
    }
}
