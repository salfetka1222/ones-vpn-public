package app.onesvpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnStatus(val label: String) {
    DISCONNECTED("Отключено"), CONNECTING("Подключение"), CONNECTED("Подключено"),
    DISCONNECTING("Отключение"), ERROR("Ошибка")
}
data class VpnState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val detail: String = "",
    val connectedAtNanos: Long? = null,
    val lastVerifiedElapsedMs: Long? = null
)

/** Session generations reject completions arriving after cancellation. */
class VpnLifecycle(
    // Android service supplies elapsedRealtime(), which includes deep sleep.
    // JVM unit tests use a monotonic fallback without Android framework calls.
    private val elapsedClockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    companion object {
        const val TUN_HELD_UNVERIFIED = "Связь не подтверждена: VPN-туннель сохранён"
        const val NATIVE_STOPPED_TUN_HELD = "VPN-движок остановлен: туннель сохранён"
        const val STARTUP_DELAYED = "Запуск VPN задерживается: связь не проверена"
    }

    private val mutableState = MutableStateFlow(VpnState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    @Synchronized fun start(): Long? {
        if (mutableState.value.status !in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR)) return null
        generation++
        mutableState.value = VpnState(VpnStatus.CONNECTING)
        return generation
    }
    @Synchronized fun isCurrent(generation: Long): Boolean =
        this.generation == generation && mutableState.value.status in setOf(VpnStatus.CONNECTING, VpnStatus.CONNECTED)
    @Synchronized fun connected(generation: Long): Boolean {
        if (!isCurrent(generation)) return false
        // Record every successful VPN-bound probe using a clock that includes
        // deep sleep. Periodic checks never reset the connection stopwatch.
        val verifiedAt = elapsedClockMs()
        if (mutableState.value.status != VpnStatus.CONNECTED) {
            mutableState.value = VpnState(
                VpnStatus.CONNECTED,
                connectedAtNanos = System.nanoTime(),
                lastVerifiedElapsedMs = verifiedAt
            )
        } else {
            mutableState.value = mutableState.value.copy(
                detail = "",
                lastVerifiedElapsedMs = verifiedAt
            )
        }
        return true
    }

    /** Transient failures are visible, but do not falsely change the session to ERROR. */
    @Synchronized fun checkingConnection(expectedGeneration: Long): Boolean {
        if (!isCurrent(expectedGeneration) || mutableState.value.status != VpnStatus.CONNECTED) return false
        mutableState.value = mutableState.value.copy(detail = "Проверяем соединение…")
        return true
    }

    /**
     * A native TUN exists, but HTTPS endpoints have repeatedly failed.
     * Do not mark the session verified or drop the TUN just to retry.
     */
    @Synchronized fun tunnelUnverified(
        expectedGeneration: Long,
        nativeStopped: Boolean = false
    ): Boolean {
        if (!isCurrent(expectedGeneration)) return false
        val detail = if (nativeStopped) NATIVE_STOPPED_TUN_HELD else TUN_HELD_UNVERIFIED
        mutableState.value = mutableState.value.copy(detail = detail)
        return true
    }

    /** A slow in-flight native start is not permission to close Android routes. */
    @Synchronized fun startupDelayed(expectedGeneration: Long): Boolean {
        if (!isCurrent(expectedGeneration) ||
            mutableState.value.status != VpnStatus.CONNECTING) return false
        mutableState.value = mutableState.value.copy(detail = STARTUP_DELAYED)
        return true
    }

    /** Keep TUN alive, but show that physical connectivity is not yet usable. */
    @Synchronized fun waitingForNetwork(expectedGeneration: Long, detail: String): Boolean {
        if (!isCurrent(expectedGeneration)) return false
        mutableState.value = mutableState.value.copy(detail = detail)
        return true
    }

    @Synchronized fun connectedGeneration(): Long? =
        generation.takeIf { mutableState.value.status == VpnStatus.CONNECTED }

    /** Active native startup or established session; never a stopping generation. */
    @Synchronized fun activeGeneration(): Long? =
        generation.takeIf {
            mutableState.value.status in setOf(VpnStatus.CONNECTING, VpnStatus.CONNECTED)
        }

    /** Diagnostic checks only apply to a previously verified, non-recovering session. */
    @Synchronized fun verifiedGeneration(): Long? =
        generation.takeIf {
            mutableState.value.status == VpnStatus.CONNECTED &&
                mutableState.value.detail.isEmpty() &&
                mutableState.value.lastVerifiedElapsedMs != null
        }

    @Synchronized fun stop(): Boolean {
        if (mutableState.value.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.DISCONNECTING)) return false
        generation++
        mutableState.value = VpnState(VpnStatus.DISCONNECTING)
        return true
    }
    /** Only the cleanup that owns the current stop generation may finish it. */
    @Synchronized fun stopGeneration(): Long? =
        generation.takeIf { mutableState.value.status == VpnStatus.DISCONNECTING }

    @Synchronized fun stopped(expectedGeneration: Long, error: String? = null): Boolean {
        if (generation != expectedGeneration || mutableState.value.status != VpnStatus.DISCONNECTING) return false
        mutableState.value = if (error == null) VpnState() else VpnState(VpnStatus.ERROR, error)
        return true
    }
}
