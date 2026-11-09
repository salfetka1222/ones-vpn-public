package app.onesvpn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import io.nekohasekai.libbox.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface

/** The Java side owns the original TUN; libbox 1.14.4 duplicates the returned fd. */
internal class AndroidPlatform(
    private val service: VpnService,
    private val onPhysicalChange: () -> Unit = {},
    // Reject any late TUN creation after this connection generation was
    // explicitly cancelled (including while builder.establish is in flight).
    private val isSessionCurrent: () -> Boolean
) : PlatformInterface, AutoCloseable {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val callbackThread = HandlerThread("ones-network").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    @Volatile private var underlying: Network? = null
    private var underlyingInterfaceName: String? = null
    private var underlyingInterfaceIndex: Int? = null
    // Changes only when the physical socket-binding network changes.
    // Used to discard health checks that raced with a Wi-Fi/cellular handover.
    private var physicalRevision = 0L
    private var lastPhysicalChangeMs = 0L

    internal data class PhysicalLinkSnapshot(
        val available: Boolean, val revision: Long, val ageMs: Long
    )

    @Synchronized fun physicalLinkSnapshot(): PhysicalLinkSnapshot {
        val age = if (lastPhysicalChangeMs == 0L) Long.MAX_VALUE else
            (SystemClock.elapsedRealtime() - lastPhysicalChangeMs).coerceAtLeast(0L)
        return PhysicalLinkSnapshot(!closed && underlying != null, physicalRevision, age)
    }
    @Volatile private var listener: InterfaceUpdateListener? = null
    @Volatile private var closed = false
    private var registered = false
    // Main-thread recovery checks must know if Android still owns a TUN.
    @Volatile private var tun: ParcelFileDescriptor? = null

    fun hasEstablishedTun(): Boolean = !closed && tun != null
    @Volatile var tunName: String? = null
        private set

    // Callbacks arrive on callbackHandler's thread. Android delivers capabilities
    // and link properties *after* onAvailable; never synchronously re-query them
    // from a callback (they can describe a different network generation).
    private var candidateNetwork: Network? = null
    private var candidateCapabilities: NetworkCapabilities? = null
    private var candidateProperties: LinkProperties? = null
    private var candidateBlocked = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Keep the previous route until Android supplies both metadata objects.
            // A premature bind to a network without an interface can break handover.
            candidateNetwork = network
            candidateCapabilities = null
            candidateProperties = null
            candidateBlocked = false
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != candidateNetwork) return
            candidateCapabilities = capabilities
            publishCandidate()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (network != candidateNetwork) return
            candidateProperties = properties
            publishCandidate()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (network != candidateNetwork) return
            candidateBlocked = blocked
            publishCandidate()
        }

        override fun onLost(network: Network) {
            if (network == candidateNetwork) {
                candidateNetwork = null
                candidateCapabilities = null
                candidateProperties = null
                candidateBlocked = false
            }
            // Do not synchronously scan ConnectivityManager inside a callback.
            // A replacement network will be advertised via onAvailable.
            if (network == underlying) applyInterface(null, null, null, false)
        }
    }

    /** Only called from the callback thread; uses ordered callback arguments. */
    private fun publishCandidate() {
        if (closed || listener == null) return
        val network = candidateNetwork ?: return
        val caps = candidateCapabilities
        val properties = candidateProperties
        val name = properties?.interfaceName
        val index = if (caps != null && properties != null && !candidateBlocked && name != null) {
            runCatching { JavaNetworkInterface.getByName(name)?.index }.getOrNull()
        } else null
        when (NetworkHandoverPolicy.decide(
            capabilitiesKnown = caps != null,
            linkPropertiesKnown = properties != null,
            internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            physical = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true,
            interfaceIndex = index,
            blocked = candidateBlocked
        )) {
            NetworkHandoverPolicy.Action.WAIT -> Unit
            NetworkHandoverPolicy.Action.USE -> applyInterface(network, name, index,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
            NetworkHandoverPolicy.Action.CLEAR -> applyInterface(null, null, null, false)
        }
    }

    /** Outside callbacks only: initial snapshot before registering for updates. */
    private fun findUnderlying(): Network? {
        val choices = connectivity.allNetworks.mapNotNull { candidate ->
            val caps = connectivity.getNetworkCapabilities(candidate) ?: return@mapNotNull null
            val link = connectivity.getLinkProperties(candidate)
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                link?.interfaceName == null) return@mapNotNull null
            candidate to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        // Android's validated network is more likely to have working internet,
        // but do not demand validation (some networks can still reach the proxy).
        return choices.firstOrNull { it.second }?.first ?: choices.firstOrNull()?.first
    }

    /**
     * Update both native routing and socket binding as one serialized transition.
     * Null means the physical network is gone or its metadata is unusable.
     */
    @Synchronized private fun applyInterface(
        network: Network?,
        name: String?,
        index: Int?,
        metered: Boolean
    ) {
        if (closed) return
        val target = listener ?: return
        val previous = underlying
        val oldInterfaceName = underlyingInterfaceName
        val oldInterfaceIndex = underlyingInterfaceIndex
        try {
            if (network != null && name != null && index != null && index > 0) {
                service.setUnderlyingNetworks(arrayOf(network))
                underlying = network
                underlyingInterfaceName = name
                underlyingInterfaceIndex = index
                target.updateDefaultInterface(name, index, metered, false)
                Diagnostics.record(DiagnosticEvent.UNDERLYING_NETWORK_READY)
            } else {
                // Never leave the native engine pinned to a vanished network.
                underlying = null
                underlyingInterfaceName = null
                underlyingInterfaceIndex = null
                service.setUnderlyingNetworks(emptyArray())
                target.updateDefaultInterface("", -1, false, false)
                Diagnostics.record(DiagnosticEvent.UNDERLYING_NETWORK_LOST)
            }
        } catch (_: Exception) {
            underlying = null
            underlyingInterfaceName = null
            underlyingInterfaceIndex = null
            runCatching { service.setUnderlyingNetworks(emptyArray()) }
            runCatching { target.updateDefaultInterface("", -1, false, false) }
            Diagnostics.record(DiagnosticEvent.NETWORK_MONITOR_ERROR)
        } finally {
            if (previous != underlying ||
                oldInterfaceName != underlyingInterfaceName ||
                oldInterfaceIndex != underlyingInterfaceIndex) {
                physicalRevision++
                lastPhysicalChangeMs = SystemClock.elapsedRealtime()
                // Notification only: caller posts to main thread and never runs
                // native engine mutations from the Android callback thread.
                runCatching { onPhysicalChange() }
            }
        }
    }

    @Synchronized override fun openTun(options: TunOptions): Int {
        check(!closed && tun == null) { "TUN_ALREADY_OPEN_OR_CLOSED" }
        check(isSessionCurrent()) { "VPN_SESSION_CANCELLED" }
        check(VpnService.prepare(service) == null) { "VPN_PERMISSION_REVOKED" }
        val builder = service.Builder().setSession("ONES VPN").setMtu(options.mtu).setMetered(false)
        fun addresses(iterator: RoutePrefixIterator): Boolean {
            var found = false
            while (iterator.hasNext()) {
                val route = iterator.next()
                builder.addAddress(route.address(), route.prefix())
                found = true
            }
            return found
        }
        val ipv4 = addresses(options.inet4Address)
        val ipv6 = addresses(options.inet6Address)
        check(ipv4 || ipv6) { "TUN_ADDRESS_MISSING" }
        check(options.autoRoute) { "TUN_AUTO_ROUTE_REQUIRED" }
        // Route ranges include exclusions on every supported Android API level.
        fun routes(iterator: RoutePrefixIterator, fallback: String, enabled: Boolean) {
            if (!iterator.hasNext() && enabled) builder.addRoute(fallback, 0)
            while (iterator.hasNext()) {
                val route = iterator.next()
                builder.addRoute(route.address(), route.prefix())
            }
        }
        routes(options.inet4RouteRange, "0.0.0.0", ipv4)
        routes(options.inet6RouteRange, "::", ipv6)
        val dns = options.dnsServerAddress
        while (dns.hasNext()) builder.addDnsServer(dns.next())
        // Explicit user choices only. Disallowed applications use the device's
        // ordinary network instead of ONES VPN, and may reveal their IP/DNS.
        // Changes take effect on the next tunnel creation; never change a live TUN.
        for (packageName in SplitTunnelStore(service).load().sorted()) {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                // Uninstalled packages have no traffic to exclude. Other failures
                // must stop tunnel creation instead of silently altering routing.
            }
        }
        builder.setUnderlyingNetworks(underlying?.let { arrayOf(it) } ?: emptyArray())
        check(isSessionCurrent()) { "VPN_SESSION_CANCELLED" }
        val descriptor = builder.establish() ?: error("TUN_ESTABLISH_FAILED")
        if (!isSessionCurrent()) {
            // A manual disconnect may race with establish(). This FD must
            // not outlive a deliberately cancelled generation.
            descriptor.close()
            error("VPN_SESSION_CANCELLED")
        }
        tun = descriptor
        Diagnostics.record(DiagnosticEvent.TUN_CREATED)
        // Do not detach: the original must survive until close(), including failed native startup.
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) {
        if (!service.protect(fd)) {
            Diagnostics.record(DiagnosticEvent.SOCKET_PROTECT_FAILED)
            error("SOCKET_PROTECT_FAILED")
        }
        val network = underlying ?: error("UNDERLYING_NETWORK_MISSING")
        // fromFd duplicates the descriptor; bind the socket and close only our temporary duplicate.
        ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
    }

    @Synchronized override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        check(!closed)
        this.listener = listener
        if (registered) return
        // Seed once, outside the callback thread. Register after this snapshot,
        // so ordered callback events cannot be overwritten by an older snapshot.
        val initial = runCatching {
            connectivity.activeNetwork?.takeIf { candidate ->
                val caps = connectivity.getNetworkCapabilities(candidate)
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    connectivity.getLinkProperties(candidate)?.interfaceName != null
            } ?: findUnderlying()
        }.getOrNull()
        val caps = runCatching { initial?.let(connectivity::getNetworkCapabilities) }.getOrNull()
        val properties = runCatching { initial?.let(connectivity::getLinkProperties) }.getOrNull()
        val name = properties?.interfaceName
        val index = name?.let {
            runCatching { JavaNetworkInterface.getByName(it)?.index }.getOrNull()
        }
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            index != null && index > 0) {
            applyInterface(initial, name, index,
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        } else {
            applyInterface(null, null, null, false)
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        if (Build.VERSION.SDK_INT >= 31) {
            connectivity.registerBestMatchingNetworkCallback(request, callback, callbackHandler)
        } else {
            connectivity.requestNetwork(request, callback, callbackHandler)
        }
        registered = true
    }

    @Synchronized override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        this.listener = null
        if (registered) {
            registered = false
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val entries = connectivity.allNetworks.mapNotNull { network ->
            val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            val name = properties.interfaceName ?: return@mapNotNull null
            val system = JavaNetworkInterface.getByName(name) ?: return@mapNotNull null
            NetworkInterface().apply {
                this.name = name
                index = system.index
                mtu = runCatching { system.mtu }.getOrDefault(1500)
                addresses = Strings(properties.linkAddresses.map {
                    "${InetAddress.getByAddress(it.address.address).hostAddress}/${it.prefixLength}"
                })
                dnsServer = Strings(properties.dnsServers.mapNotNull { it.hostAddress })
                gateway = Strings(properties.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress })
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (system.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (system.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                if (system.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext() = entries.hasNext()
            override fun next() = entries.next()
        }
    }

    override fun registerMyInterface(name: String) { tunName = name }

    /** Reads only Android route/DNS metadata for the TUN owned by ONES VPN. */
    fun auditCurrentVpnRoutes(): RouteEvidence? {
        if (closed) return null
        val ownNetwork = vpnNetwork() ?: return null
        val properties = connectivity.getLinkProperties(ownNetwork) ?: return null
        val vpnInterface = properties.interfaceName
        val routes = properties.routes.map { route ->
            RouteAuditPolicy.RouteEntry(
                prefix = RouteAuditPolicy.Prefix(
                    route.destination.address.address,
                    route.destination.prefixLength
                ),
                // RouteInfo.type is public only since Android 13. Fail closed
                // for older OS versions rather than count potentially
                // unreachable/throw routes as VPN forwarding evidence.
                unicast = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    route.type == RouteInfo.RTN_UNICAST,
                onVpnInterface = route.getInterface() == null ||
                    route.getInterface() == vpnInterface
            )
        }
        val excludedApps = SplitTunnelStore(service).load().size
        // Only Android 13+ exposes route type to distinguish real forwarding
        // from unreachable/throw routes. Older devices get no DNS-route verdict.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RouteAuditPolicy.evaluateForwarding(
                routes, properties.dnsServers.map { it.address }, excludedApps
            )
        } else {
            RouteAuditPolicy.evaluateForwarding(routes, properties.dnsServers.size, excludedApps)
        }
    }

    fun vpnNetwork(): Network? = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            tunName != null && connectivity.getLinkProperties(it)?.interfaceName == tunName
    }

    @Synchronized override fun close() {
        closed = true
        listener = null
        if (registered) {
            registered = false
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        callbackThread.quitSafely()
        val descriptor = tun
        tun = null
        try { descriptor?.close() } finally { tunName = null }
        Diagnostics.record(DiagnosticEvent.TUN_CLOSED)
    }

    override fun findConnectionOwner(protocol: Int, source: String, sourcePort: Int, destination: String, destinationPort: Int): ConnectionOwner {
        // Owner lookup is advisory; never return null to the Go wrapper, even on OEM/API failures.
        val uid = runCatching {
            connectivity.getConnectionOwnerUid(protocol, InetSocketAddress(source, sourcePort), InetSocketAddress(destination, destinationPort))
        }.getOrDefault(-1)
        return ConnectionOwner().apply {
            userId = uid
            userName = ""
            processPath = ""
            setAndroidPackageNames(Strings(emptyList()))
        }
    }
    override fun includeAllNetworks() = false
    override fun underNetworkExtension() = false
    override fun localDNSTransport(): LocalDNSTransport? = null // Config uses explicit DoH resolvers.
    override fun clearDNSCache() {}
    override fun readWIFIState(): WIFIState? = null
    override fun startNeighborMonitor(listener: NeighborUpdateListener) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {}
    override fun sendNotification(notification: Notification) {}
    override fun cancelNotification(identifier: String, type: Int) {}
    override fun usePlatformBridge() = false
    override fun createBridge(options: BridgeOptions): BridgeSession = throw UnsupportedOperationException()
    override fun usePlatformShell() = false
    override fun checkPlatformShell() = throw UnsupportedOperationException()
    override fun lookupUser(name: String): PlatformUser = throw UnsupportedOperationException()
    override fun openShellSession(user: PlatformUser, command: String, environment: StringIterator, terminal: String, rows: Int, cols: Int): ShellSession = throw UnsupportedOperationException()
    override fun lookupSFTPServer() = ""
    override fun readSystemSSHHostKey() = ""
    override fun tailscaleHostname() = ""

    private class Strings(values: List<String>) : StringIterator {
        private val iterator = values.iterator()
        private val size = values.size
        override fun len() = size
        override fun hasNext() = iterator.hasNext()
        override fun next() = iterator.next()
    }
}
