package app.onesvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class OnesVpnService : VpnService(), CommandServerHandler {
    companion object {
        const val ACTION_CONNECT = "app.onesvpn.CONNECT"
        const val ACTION_DISCONNECT = "app.onesvpn.DISCONNECT"
        const val ACTION_AUDIT_ROUTES = "app.onesvpn.AUDIT_ROUTES"
        const val ACTION_EXTERNAL_IP_CHECK = "app.onesvpn.EXTERNAL_IP_CHECK"
        const val ACTION_CHECK_SYSTEM_PROTECTION = "app.onesvpn.CHECK_SYSTEM_PROTECTION"
        const val ACTION_VERIFY_ON_RESUME = "app.onesvpn.VERIFY_ON_RESUME"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val CHANNEL = "ones_vpn_connection"
        private val lifecycle = VpnLifecycle { SystemClock.elapsedRealtime() }
        val state = lifecycle.state
    }
    private val main = Handler(Looper.getMainLooper())
    // All native mutations and resource teardown are serialized on this worker.
    private val engineWorker = Executors.newSingleThreadExecutor()
    private val probeWorker = Executors.newSingleThreadExecutor()
    private val externalIpWorker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val trafficMonitor = VpnTrafficMonitor()
    private var engine: CommandServer? = null // engineWorker only
    @Volatile private var platform: AndroidPlatform? = null // writes: engineWorker; read-only recovery guard: main
    private var probe: VpnConnectivityProbe? = null // main only
    private var deadline: ScheduledFuture<*>? = null
    private var nextProbe: ScheduledFuture<*>? = null
    private var latestStartId = 0
    // Main-thread only. Intent and profile live only for this service instance.
    private var desiredProfileId: String? = null
    private var retryAttempt = 0
    private var failedHealthChecks = 0
    private var probeInFlightFor: Long? = null // Main thread only
    private var externalIpRequestId = 0L
    private var routeAuditRequestId = 0L
    private var retryTask: ScheduledFuture<*>? = null
    private var destroyed = false
    private var lastForegroundCheckElapsedMs: Long? = null
    private var lastNotificationLabel: String? = null
    // If libbox reports spontaneous service stop while Android still owns TUN,
    // preserve that TUN but never claim healthy traffic or re-arm native probes.
    private var nativeServiceStopped = false
    // Written by engineWorker; observed on main by spontaneous native callback.
    // An in-flight establish() must never be cancelled as if no TUN can exist.
    @Volatile private var nativeStartInFlight = false

    override fun onCreate() {
        super.onCreate()
        refreshSystemProtection()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "VPN-соединение", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action != ACTION_CHECK_SYSTEM_PROTECTION) refreshSystemProtection()
        // Android may start the VPN service itself (including after reboot).
        // Never infer this from any app diagnostic command or unknown action.
        val systemInitiated = AlwaysOnRestartPolicy.isSystemStart(intent?.action)
        val requestedBySystem = if (systemInitiated) AlwaysOnRestartPolicy.profileToRestore(
            systemInitiated = true,
            alwaysOn = isAlwaysOn,
            vpnConsentGranted = prepare(this) == null,
            rememberedId = ConnectionIntentStore(this).rememberedId()
        ) else null
        when (intent?.action) {
            ACTION_CHECK_SYSTEM_PROTECTION -> {
                // Read-only OS report. Emit a fresh result even if the OS-level
                // protection value is unchanged since the previous query.
                refreshSystemProtection(manualCheck = true)
            }
            ACTION_VERIFY_ON_RESUME -> {
                // Only a running, already connected session can be checked.
                // Resuming the Activity does not start or reconnect a VPN.
                val generation = lifecycle.connectedGeneration()
                if (generation != null && !destroyed && probe != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (ForegroundHealthPolicy.shouldCheck(
                            now, state.value.lastVerifiedElapsedMs,
                            lastForegroundCheckElapsedMs
                        )) {
                        lastForegroundCheckElapsedMs = now
                        nextProbe?.cancel(false)
                        beginProbe(generation)
                    }
                }
            }
            ACTION_EXTERNAL_IP_CHECK -> {
                val session = lifecycle.verifiedGeneration()
                if (session == null || destroyed) {
                    externalIpRequestId++
                    ExternalIpState.clear()
                } else if (!ExternalIpState.state.value.inProgress) {
                    val requestId = ++externalIpRequestId
                    ExternalIpState.running()
                    // Use only the VPN network; never fall back to physical Wi-Fi.
                    // Reject results captured before a physical network handover.
                    engineWorker.execute {
                        val adapter = platform
                        val before = adapter?.physicalLinkSnapshot()
                        val network = if (before?.available == true)
                            runCatching { adapter?.vpnNetwork() }.getOrNull() else null
                        if (network == null) {
                            main.post {
                                if (destroyed || requestId != externalIpRequestId) return@post
                                if (isEvidenceCurrent(session, adapter, before))
                                    ExternalIpState.noVpn()
                                else ExternalIpState.clear()
                            }
                        } else {
                            externalIpWorker.execute {
                                val answer = ExternalIpProbe.run(network)
                                main.post {
                                    if (destroyed || requestId != externalIpRequestId) return@post
                                    if (isEvidenceCurrent(session, adapter, before))
                                        ExternalIpState.finished(answer.first, answer.second)
                                    else ExternalIpState.clear()
                                }
                            }
                        }
                    }
                }
            }
            ACTION_AUDIT_ROUTES -> {
                val session = lifecycle.verifiedGeneration()
                if (session == null || destroyed) {
                    routeAuditRequestId++
                    VpnRouteAudit.clear()
                } else {
                    val requestId = ++routeAuditRequestId
                    VpnRouteAudit.running()
                    engineWorker.execute {
                        val adapter = platform
                        val before = adapter?.physicalLinkSnapshot()
                        val result = runCatching {
                            if (before?.available == true) adapter?.auditCurrentVpnRoutes()
                            else null
                        }
                        main.post {
                            if (destroyed || requestId != routeAuditRequestId) return@post
                            if (!isEvidenceCurrent(session, adapter, before)) {
                                VpnRouteAudit.clear()
                            } else {
                                result.onSuccess { VpnRouteAudit.finish(it) }
                                    .onFailure { VpnRouteAudit.fail() }
                            }
                        }
                    }
                }
            }
            ACTION_DISCONNECT -> {
                // Explicit disconnect must survive a process death or a later
                // Android Always-on restart. Do not clear on ordinary onDestroy().
                if (!ConnectionIntentStore(this).clear())
                    Diagnostics.record(DiagnosticEvent.CONNECT_INTENT_SAVE_FAILED)
                cancelReconnect()
                if (!requestStop()) stopSelfResult(startId)
            }
            ACTION_CONNECT, VpnService.SERVICE_INTERFACE, null -> {
                if (systemInitiated && requestedBySystem == null) {
                    // No saved user request or Android is not in Always-on mode.
                    // Especially with lockdown enabled, never silently choose a
                    // different profile or bypass Android's system protection.
                    if (state.value.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR))
                        stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                val id = if (systemInitiated) requestedBySystem
                    else intent?.getStringExtra(EXTRA_PROFILE_ID)
                // Reject duplicate starts before changing desiredProfileId or cancelling retries.
                // Otherwise a second start while connected could silently change the target
                // profile without changing the running native tunnel.
                if (state.value.status in setOf(VpnStatus.CONNECTING, VpnStatus.CONNECTED, VpnStatus.DISCONNECTING)) {
                    return START_NOT_STICKY
                }
                // A fresh explicit request replaces any pending automatic retry.
                cancelPendingRetry()
                if (id != desiredProfileId) retryAttempt = 0
                desiredProfileId = id
                // Even duplicate startForegroundService calls must satisfy Android's foreground deadline.
                showNotification(state.value.status.label)
                val generation = lifecycle.start() ?: return START_NOT_STICKY
                VpnTraffic.reset()
                routeAuditRequestId++
                externalIpRequestId++
                VpnRouteAudit.clear()
                ExternalIpState.clear()
                failedHealthChecks = 0
                lastForegroundCheckElapsedMs = null
                nativeServiceStopped = false
                Diagnostics.record(DiagnosticEvent.CONNECT_REQUESTED)
                if (id == null || prepare(this) != null) {
                    cancelReconnect()
                    requestStop("VPN_PERMISSION_OR_PROFILE_MISSING")
                    return START_NOT_STICKY
                }
                if (!systemInitiated) {
                    // Store only after Android has granted VPN consent. No
                    // credentials or subscription URLs are saved here.
                    runCatching { ConnectionIntentStore(this).remember(id) }
                        .onFailure { Diagnostics.record(DiagnosticEvent.CONNECT_INTENT_SAVE_FAILED) }
                }
                showNotification(VpnStatus.CONNECTING.label)
                armStartupWarning(generation)
                engineWorker.execute {
                    try {
                        if (!lifecycle.isCurrent(generation)) return@execute
                        val profile = ProfileStore(this).load().firstOrNull { it.id == id }
                            ?: error("PROFILE_NOT_FOUND")
                        val validation = NativeEngine.validate(this, profile)
                        Diagnostics.record(if (validation.isSuccess) DiagnosticEvent.CONFIG_VALID else DiagnosticEvent.CONFIG_INVALID)
                        validation.getOrThrow()
                        if (!lifecycle.isCurrent(generation)) return@execute
                        val adapter = AndroidPlatform(
                            this,
                            onPhysicalChange = { onPhysicalNetworkChanged(generation) },
                            isSessionCurrent = { lifecycle.isCurrent(generation) }
                        )
                        platform = adapter
                        val server = Libbox.newCommandServer(this, adapter)
                        engine = server
                        Diagnostics.record(DiagnosticEvent.ENGINE_STARTING)
                        nativeStartInFlight = true
                        try {
                            server.start()
                            check(lifecycle.isCurrent(generation)) { "VPN_SESSION_CANCELLED" }
                            server.startOrReloadService(
                                SingBoxConfig.buildTunConfig(profile).toString(), OverrideOptions()
                            )
                        } finally {
                            nativeStartInFlight = false
                        }
                        Diagnostics.record(DiagnosticEvent.ENGINE_STARTED)
                        main.post {
                            if (lifecycle.isCurrent(generation) && !destroyed) {
                                // A spontaneous native stop may have arrived DURING
                                // builder.establish(). Let startup settle before
                                // deciding whether there is a TUN to preserve.
                                deadline?.cancel(false)
                                deadline = null
                                if (nativeServiceStopped) {
                                    if (platform?.hasEstablishedTun() == true)
                                        holdUnverifiedTunnel(generation, nativeStopped = true)
                                    else requestStop("ENGINE_STOPPED_DURING_STARTUP")
                                } else {
                                    probe = VpnConnectivityProbe(adapter)
                                    trafficMonitor.start()
                                    beginProbe(generation)
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        // Go panic cannot be caught here. JNI linkage errors can, and still need cleanup.
                        Diagnostics.record(Diagnostics.failure(failure))
                        main.post {
                            if (!lifecycle.isCurrent(generation)) return@post
                            if (platform?.hasEstablishedTun() == true) {
                                // Native startup can fail AFTER Android has established
                                // the VPN routing. Preserve those routes rather than
                                // silently expose ordinary traffic without Lockdown.
                                nativeServiceStopped = true
                                deadline?.cancel(false)
                                holdUnverifiedTunnel(generation, nativeStopped = true)
                            } else {
                                requestStop("ENGINE_START_FAILED")
                            }
                        }
                    }
                }
            }
            else -> if (state.value.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR)) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    /** Session identity AND physical revision must still match after async checks. */
    private fun isEvidenceCurrent(
        session: Long,
        adapter: AndroidPlatform?,
        before: AndroidPlatform.PhysicalLinkSnapshot?
    ): Boolean {
        val after = adapter?.physicalLinkSnapshot()
        return !destroyed && NetworkEvidencePolicy.isCurrent(
            beforeAvailable = before?.available == true,
            afterAvailable = after?.available == true,
            beforeRevision = before?.revision ?: -1L,
            afterRevision = after?.revision ?: -2L,
            verifiedSession = lifecycle.verifiedGeneration() == session
        )
    }

    private fun onPhysicalNetworkChanged(generation: Long) {
        main.post {
            if (destroyed || !lifecycle.isCurrent(generation) || probe == null) return@post
            val snapshot = platform?.physicalLinkSnapshot() ?: return@post
            // A 30-second periodic health check is too slow to report loss of
            // a physical network. Immediately invalidate previously displayed
            // verification; the existing recovery policy schedules rechecks.
            routeAuditRequestId++
            externalIpRequestId++
            VpnRouteAudit.clear()
            ExternalIpState.clear()
            waitForPhysicalNetwork(generation,
                if (snapshot.available) NetworkRecoveryPolicy.Decision.WAIT_FOR_HANDOVER
                else NetworkRecoveryPolicy.Decision.WAIT_FOR_NETWORK)
        }
    }

    private fun refreshSystemProtection(manualCheck: Boolean = false) {
        runCatching {
            SystemVpnProtection.update(isAlwaysOn, isLockdownEnabled, manualCheck)
        }.onFailure {
            // Never report a false level when Android refuses the query.
            SystemVpnProtection.clear(manualCheck)
        }
    }

    /** A physical outage is not a VPN server failure; keep TUN open and retry. */
    private fun waitForPhysicalNetwork(
        generation: Long,
        reason: NetworkRecoveryPolicy.Decision
    ) {
        if (state.value.status == VpnStatus.CONNECTING) {
            // There is nothing to probe while all physical networks are missing.
            // Rearm the startup probe timeout only after a usable network returns.
            deadline?.cancel(false)
            deadline = null
        }
        failedHealthChecks = 0
        val message = if (reason == NetworkRecoveryPolicy.Decision.WAIT_FOR_NETWORK)
            "Ожидаем сеть" else "Восстанавливаем сеть"
        // Record transitions, not every 5-second retry.
        val changed = state.value.detail != message
        if (lifecycle.waitingForNetwork(generation, message)) {
            if (changed) Diagnostics.record(if (reason ==
                NetworkRecoveryPolicy.Decision.WAIT_FOR_NETWORK)
                DiagnosticEvent.PHYSICAL_NETWORK_WAIT else DiagnosticEvent.NETWORK_HANDOVER_WAIT)
            if (changed) showNotification(message)
            scheduleHealthProbe(generation, NetworkRecoveryPolicy.RETRY_SECONDS)
        }
    }

    private fun beginProbe(generation: Long) {
        if (!lifecycle.isCurrent(generation) || destroyed || nativeServiceStopped) return
        val currentProbe = probe ?: return
        val adapter = platform ?: return
        // Do not queue overlapping network checks across fast handovers.
        if (probeInFlightFor != null) {
            scheduleHealthProbe(generation, 2L)
            return
        }
        val wasConnected = state.value.status == VpnStatus.CONNECTED
        val before = adapter.physicalLinkSnapshot()
        val networkDecision = NetworkRecoveryPolicy.assess(
            physicalAvailable = before.available,
            physicalAgeMs = before.ageMs,
            previouslyConnected = wasConnected
        )
        if (networkDecision != NetworkRecoveryPolicy.Decision.PROBE) {
            waitForPhysicalNetwork(generation, networkDecision)
            return
        }
        probeInFlightFor = generation
        Diagnostics.record(DiagnosticEvent.PROBE_STARTED)
        // The TUN is established before the first health probe. Probe failure
        // cannot justify dropping its routes even if no proof has yet succeeded.
        probeWorker.execute {
            var success = false
            try {
                val attempts = if (wasConnected) 1 else 3
                repeat(attempts) {
                    if (!success && lifecycle.isCurrent(generation)) {
                        success = currentProbe.check()
                        if (!success && it < attempts - 1) Thread.sleep(500)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                Diagnostics.record(Diagnostics.failure(failure))
            } finally {
                main.post {
                    if (probeInFlightFor == generation) probeInFlightFor = null
                    if (!lifecycle.isCurrent(generation) || destroyed ||
                        nativeServiceStopped) return@post
                    // A probe can finish after Wi-Fi has been replaced by
                    // cellular. Its result applies to the old physical route,
                    // even if it returned HTTP 204 successfully.
                    val after = adapter.physicalLinkSnapshot()
                    val networkDecisionAfter = NetworkRecoveryPolicy.assess(
                        physicalAvailable = after.available,
                        physicalAgeMs = after.ageMs,
                        previouslyConnected = wasConnected,
                        changedDuringProbe = before.revision != after.revision
                    )
                    if (networkDecisionAfter != NetworkRecoveryPolicy.Decision.PROBE) {
                        if (before.revision != after.revision) {
                            Diagnostics.record(DiagnosticEvent.STALE_NETWORK_PROBE)
                        }
                        waitForPhysicalNetwork(generation, networkDecisionAfter)
                        return@post
                    }
                    val wasRecovering = state.value.detail.isNotEmpty() || failedHealthChecks > 0
                    val decision = HealthProbePolicy.evaluate(
                        probeSucceeded = success,
                        consecutiveFailures = failedHealthChecks
                    )
                    failedHealthChecks = decision.consecutiveFailures
                    when (decision.action) {
                        HealthProbePolicy.Action.HEALTHY -> {
                            if (lifecycle.connected(generation)) {
                                Diagnostics.record(DiagnosticEvent.PROBE_OK)
                                if (wasRecovering) Diagnostics.record(DiagnosticEvent.VPN_RECOVERED)
                                retryAttempt = 0
                                showNotification(VpnStatus.CONNECTED.label)
                                scheduleHealthProbe(generation,
                                    HealthProbePolicy.HEALTHY_INTERVAL_SECONDS)
                            }
                        }
                        HealthProbePolicy.Action.RECHECK -> {
                            Diagnostics.record(DiagnosticEvent.HEALTH_PROBE_RECHECK)
                            lifecycle.checkingConnection(generation)
                            showNotification("Проверяем соединение")
                            scheduleHealthProbe(generation,
                                HealthProbePolicy.RETRY_INTERVAL_SECONDS)
                        }
                        HealthProbePolicy.Action.HOLD_TUN -> {
                            // Security gate: never close an already-established
                            // TUN because a public HTTPS probe is unreachable.
                            // Without Android Lockdown, stop/retry would create a
                            // direct physical-network bypass window.
                            holdUnverifiedTunnel(generation)
                            scheduleHealthProbe(generation,
                                HealthProbePolicy.HOLD_INTERVAL_SECONDS)
                        }
                    }
                }
            }
        }
    }

    /** Only for an existing TUN: never manufacture system lockdown. */
    private fun holdUnverifiedTunnel(generation: Long, nativeStopped: Boolean = false) {
        if (destroyed || !lifecycle.isCurrent(generation)) return
        val detail = if (nativeStopped) VpnLifecycle.NATIVE_STOPPED_TUN_HELD
            else VpnLifecycle.TUN_HELD_UNVERIFIED
        val changed = state.value.detail != detail
        if (!lifecycle.tunnelUnverified(generation, nativeStopped)) return
        if (changed) {
            Diagnostics.record(
                if (nativeStopped) DiagnosticEvent.NATIVE_ERROR
                else DiagnosticEvent.HEALTH_PROBE_TUN_HELD
            )
            showNotification(if (nativeStopped)
                "VPN-движок остановлен · туннель сохранён"
                else "Связь не подтверждена · VPN-туннель сохранён")
        }
    }

    private fun scheduleHealthProbe(generation: Long, delaySeconds: Long) {
        nextProbe?.cancel(false)
        nextProbe = timer.schedule({
            main.post { if (lifecycle.isCurrent(generation) && !destroyed) beginProbe(generation) }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    /**
     * Startup timeout is informational ONLY. A native start or Android's
     * builder.establish() can still be in progress; invalidating the session
     * here could establish and subsequently remove a VPN route without the
     * user's permission, exposing direct traffic when Lockdown is disabled.
     */
    private fun armStartupWarning(generation: Long, seconds: Long = 25L) {
        deadline?.cancel(false)
        deadline = timer.schedule({ main.post {
            if (lifecycle.isCurrent(generation) &&
                state.value.status == VpnStatus.CONNECTING && probe == null &&
                lifecycle.startupDelayed(generation)) {
                Diagnostics.record(DiagnosticEvent.CONNECTION_TIMEOUT)
                showNotification("Запуск VPN задерживается · связь не проверена")
            }
        } }, seconds, TimeUnit.SECONDS)
    }

    private fun cancelPendingRetry() {
        retryTask?.cancel(false)
        retryTask = null
    }

    private fun cancelReconnect() {
        cancelPendingRetry()
        desiredProfileId = null
        retryAttempt = 0
    }

    private fun retryDelay(error: String?): Long? {
        // Do not loop on invalid credentials, configuration, or revoked VPN consent.
        // Failed HTTPS checks and an in-flight native startup cannot
        // authorize teardown/retry while Android might be creating TUN.
        if (error != "STARTUP_TIMEOUT") return null
        return ReconnectPolicy.delayMs(retryAttempt, desiredProfileId != null,
            !destroyed && prepare(this) == null, alwaysOn = isAlwaysOn)
    }

    /** Main-thread entry point; invalidate the generation before any queued/native work completes. */
    private fun requestStop(error: String? = null): Boolean {
        if (!lifecycle.stop()) return false
        val stopGeneration = lifecycle.stopGeneration() ?: return false
        failedHealthChecks = 0
        Diagnostics.record(DiagnosticEvent.STOP_REQUESTED)
        trafficMonitor.stop()
        SystemVpnProtection.clear()
        routeAuditRequestId++
        externalIpRequestId++
        VpnRouteAudit.clear()
        ExternalIpState.clear()
        deadline?.cancel(false)
        nextProbe?.cancel(false)
        probe?.close()
        probe = null
        if (!destroyed) showNotification(VpnStatus.DISCONNECTING.label)
        engineWorker.execute {
            var closeFailed = false
            try { engine?.closeService() } catch (_: Throwable) { closeFailed = true }
            try { engine?.close() } catch (_: Throwable) { closeFailed = true }
            engine = null
            // libbox closes its duplicate first; Java always releases the original, even on failure.
            try { platform?.close() } catch (_: Exception) { closeFailed = true }
            platform = null
            if (closeFailed) Diagnostics.record(DiagnosticEvent.ENGINE_CLOSE_FAILED)
            main.post {
                val finalError = if (closeFailed) "ENGINE_CLOSE_FAILED" else error
                if (!lifecycle.stopped(stopGeneration, finalError)) return@post
                OnesQuickTileService.requestRefresh(this)
                Diagnostics.record(DiagnosticEvent.SERVICE_STOPPED)
                val delay = if (!destroyed) retryDelay(finalError) else null
                val profileId = desiredProfileId
                if (delay != null && profileId != null) {
                    retryAttempt++
                    showNotification("Повторное подключение")
                    retryTask = timer.schedule({ main.post {
                        retryTask = null
                        if (!destroyed && desiredProfileId == profileId && prepare(this) == null) {
                            // Dispatch through Android's service manager, never call a lifecycle
                            // callback directly with a recycled startId.
                            try {
                                startService(Intent(this, OnesVpnService::class.java)
                                    .setAction(ACTION_CONNECT).putExtra(EXTRA_PROFILE_ID, profileId))
                            } catch (_: IllegalStateException) {
                                cancelReconnect()
                                removeConnectionNotification()
                                stopSelfResult(latestStartId)
                            }
                        } else {
                            cancelReconnect()
                            removeConnectionNotification()
                            stopSelfResult(latestStartId)
                        }
                    } }, delay, TimeUnit.MILLISECONDS)
                } else {
                    removeConnectionNotification()
                    if (!destroyed) stopSelfResult(latestStartId)
                }
            }
        }
        return true
    }

    private fun showNotification(label: String) {
        // Periodic health probes run every 30 seconds. Avoid waking
        // Android's notification pipeline and Quick Settings unnecessarily.
        if (!ForegroundNotificationPolicy.needsUpdate(lastNotificationLabel, label)) return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, OnesVpnService::class.java)
            .setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ONES VPN").setContentText(label).setContentIntent(open)
            .addAction(0, "Отключиться", disconnect).setOngoing(true).build())
        lastNotificationLabel = label
        OnesQuickTileService.requestRefresh(this)
    }

    private fun removeConnectionNotification() {
        // The next service connection must post its foreground notification,
        // even if its text happens to match the prior connection.
        lastNotificationLabel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        // VPN consent was revoked: discard persisted automatic restore intent.
        // Do not call VpnService.onRevoke(): it stops the service before asynchronous cleanup finishes.
        main.post {
            if (!ConnectionIntentStore(this).clear())
                Diagnostics.record(DiagnosticEvent.CONNECT_INTENT_SAVE_FAILED)
            cancelReconnect()
            if (!destroyed && !requestStop()) stopSelf()
        }
    }
    override fun onDestroy() {
        destroyed = true
        cancelReconnect()
        if (state.value.status !in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR)) requestStop()
        deadline?.cancel(false)
        nextProbe?.cancel(false)
        timer.shutdownNow()
        probeWorker.shutdownNow()
        externalIpWorker.shutdownNow()
        trafficMonitor.close()
        SystemVpnProtection.clear()
        VpnRouteAudit.clear()
        ExternalIpState.clear()
        // shutdown() drains already queued native cleanup, unlike shutdownNow().
        engineWorker.shutdown()
        super.onDestroy()
    }
    override fun serviceStop() {
        main.post {
            if (destroyed || state.value.status == VpnStatus.DISCONNECTING) return@post
            // Unexpected libbox serviceStop is NOT the user's disconnect.
            // A callback can race with an in-flight Android establish(); a
            // false hasEstablishedTun() snapshot is not permission to tear
            // down a TUN that is about to be installed.
            val activeGeneration = lifecycle.activeGeneration()
            if (activeGeneration == null) {
                cancelReconnect()
                requestStop()
                return@post
            }
            when (NativeStopPolicy.decide(
                tunEstablished = platform?.hasEstablishedTun() == true,
                nativeStartInFlight = nativeStartInFlight
            )) {
                NativeStopPolicy.Action.HOLD_TUN -> {
                    nativeServiceStopped = true
                    nextProbe?.cancel(false)
                    deadline?.cancel(false)
                    probe?.close()
                    probe = null
                    routeAuditRequestId++
                    externalIpRequestId++
                    VpnRouteAudit.clear()
                    ExternalIpState.clear()
                    holdUnverifiedTunnel(activeGeneration, nativeStopped = true)
                }
                NativeStopPolicy.Action.DEFER_UNTIL_STARTUP_FINISHES -> {
                    nativeServiceStopped = true
                    // Do not claim the tunnel exists yet. The engine worker
                    // will resolve the signal after establish() finishes or
                    // throws, and preserve any TUN actually created.
                    lifecycle.waitingForNetwork(
                        activeGeneration, "VPN-движок остановлен во время запуска"
                    )
                    showNotification("VPN-движок остановлен · проверяем запуск")
                }
                NativeStopPolicy.Action.CLEANUP_NO_TUN -> {
                    cancelReconnect()
                    requestStop()
                }
            }
        }
    }
    override fun serviceReload() {} // Profiles are immutable for the lifetime of a session.
    override fun connectSSHAgent() = -1
    override fun getSystemProxyStatus() = SystemProxyStatus()
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String) { Diagnostics.record(Diagnostics.nativeEvent(message)) }
}
