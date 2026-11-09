package app.onesvpn

/**
 * A strictly offline, descriptive review of Android VPN metadata.
 * This NEVER asserts packet-level leak protection or DNS/WebRTC safety.
 */
internal enum class LeakFindingCode {
    ROUTE_TYPE_UNAVAILABLE,
    IPV4_ROUTE_GAP,
    IPV6_ROUTE_GAP,
    DNS_SERVER_UNLISTED,
    DNS_VPN_ROUTE_GAP,
    APP_EXCLUSIONS,
    LOCKDOWN_UNCONFIRMED,
    LOCKDOWN_NOT_ENABLED,
    LOCKDOWN_ACTIVE_UNTESTED,
    EXTERNAL_TESTS_REQUIRED
}

internal data class LeakFinding(
    val code: LeakFindingCode,
    val message: String
)

internal object LocalLeakReviewPolicy {
    fun assess(
        evidence: RouteEvidence,
        protection: SystemProtectionLevel,
        apiLevel: Int
    ): List<LeakFinding> = buildList {
        // Android 10-12 does not expose RouteInfo.type. The collector therefore
        // intentionally declines to claim full coverage on those API levels.
        if (apiLevel < 33) {
            add(LeakFinding(
                LeakFindingCode.ROUTE_TYPE_UNAVAILABLE,
                "Android ниже 13: тип маршрута не доступен, покрытие IP не подтверждено."
            ))
        } else {
            if (!evidence.fullIpv4Coverage) add(LeakFinding(
                LeakFindingCode.IPV4_ROUTE_GAP,
                "IPv4: полное покрытие маршрутов через VPN не подтверждено."
            ))
            if (!evidence.fullIpv6Coverage) add(LeakFinding(
                LeakFindingCode.IPV6_ROUTE_GAP,
                "IPv6: полное покрытие маршрутов через VPN не подтверждено. " +
                    "Это не доказывает утечку: доступность IPv6 может отсутствовать."
            ))
        }

        if (evidence.vpnDnsServers == 0) add(LeakFinding(
            LeakFindingCode.DNS_SERVER_UNLISTED,
            "Android не показывает DNS-серверы для VPN. " +
                "Нужна отдельная проверка DNS; отсутствие записи ещё не доказывает утечку."
        ))
        if (apiLevel >= 33 && (evidence.dnsServersWithoutVpnRoute ?: 0) > 0) add(LeakFinding(
            LeakFindingCode.DNS_VPN_ROUTE_GAP,
            "Для ${evidence.dnsServersWithoutVpnRoute} DNS-адресов Android не показывает " +
                "маршрут передачи через VPN. Возможен обход туннеля; нужна проверка DNS-трафика."
        ))

        if (evidence.appExclusions > 0) add(LeakFinding(
            LeakFindingCode.APP_EXCLUSIONS,
            "Исключено приложений: " + evidence.appExclusions + ". " +
                "Их трафик может обходить VPN; проверьте браузер, где проводите тест."
        ))

        when (protection) {
            SystemProtectionLevel.UNKNOWN -> add(LeakFinding(
                LeakFindingCode.LOCKDOWN_UNCONFIRMED,
                "Системный Kill Switch не подтверждён: состояние Android неизвестно."
            ))
            SystemProtectionLevel.VPN_ONLY,
            SystemProtectionLevel.ALWAYS_ON -> add(LeakFinding(
                LeakFindingCode.LOCKDOWN_NOT_ENABLED,
                "Системная блокировка соединений вне VPN выключена. " +
                    "Always-on сам по себе не блокирует трафик при отключении VPN."
            ))
            SystemProtectionLevel.LOCKDOWN -> add(LeakFinding(
                LeakFindingCode.LOCKDOWN_ACTIVE_UNTESTED,
                "Android сообщает о включённой блокировке вне VPN. " +
                    "Реальное поведение при обрыве ещё не проверено."
            ))
        }
        add(LeakFinding(
            LeakFindingCode.EXTERNAL_TESTS_REQUIRED,
            "Маршруты Android не доказывают отсутствие DNS, IPv6 или WebRTC-утечек. " +
                "Нужны отдельные проверки трафика через VPN."
        ))
    }
}
