package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class SingBoxConfigTest {
    private fun profile(query: String = "security=tls", host: String = "vpn.example.org") =
        parseProfile("vless://00000000-0000-4000-8000-000000000001@$host:443?$query#Test")

    @Test fun unencryptedVlessCannotBeStarted() {
        listOf("", "security=none").forEach { query ->
            assertThrows(IllegalArgumentException::class.java) { SingBoxConfig.outbound(profile(query)) }
        }
    }

    @Test fun hardcodedDnsAlsoUsesInternalResolver() {
        val route = SingBoxConfig.buildTunConfig(profile()).getJSONObject("route")
        assertTrue("Port 53 must be intercepted for apps using their own DNS", route.has("rules"))
        val rule = route.getJSONArray("rules").getJSONObject(0)
        assertEquals(53, rule.getInt("port"))
        assertEquals("hijack-dns", rule.getString("action"))
    }

    @Test fun dnsUsesProxyAndSeparateBootstrapResolver() {
        val config = SingBoxConfig.buildTunConfig(profile())
        assertTrue("DNS must be explicit", config.has("dns"))
        val dns = config.getJSONObject("dns")
        assertEquals("remote", dns.getString("final"))
        val servers = dns.getJSONArray("servers")
        assertEquals("proxy", servers.getJSONObject(1).getString("detour"))
        assertEquals("bootstrap", config.getJSONArray("outbounds").getJSONObject(0).getString("domain_resolver"))
    }

    @Test fun tunnelRoutesBothIpFamiliesAndRequiresStrictRouting() {
        val config = SingBoxConfig.buildTunConfig(profile())
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertTrue(tun.getBoolean("auto_route"))
        assertTrue(tun.getBoolean("strict_route"))
        assertEquals("hijack", tun.getString("dns_mode"))
        val addresses = tun.getJSONArray("address")
        val list = (0 until addresses.length()).map(addresses::getString)
        assertTrue("IPv4 tunnel address missing", list.any { it.contains(".") })
        assertTrue("IPv6 tunnel address missing", list.any { it.contains(":") })
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
    }

    @Test fun remoteDnsUsesProxyAndTlsCertificateVerificationRemainsEnabled() {
        val config = SingBoxConfig.buildTunConfig(profile())
        val dns = config.getJSONObject("dns").getJSONArray("servers")
        val remote = dns.getJSONObject(1)
        assertEquals("https", remote.getString("type"))
        assertEquals("proxy", remote.getString("detour"))
        assertTrue(remote.getJSONObject("tls").getBoolean("enabled"))
        val outboundTls = config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")
        assertTrue(outboundTls.getBoolean("enabled"))
        assertFalse(outboundTls.optBoolean("insecure"))
    }

    @Test fun realityEnablesUtlsWithoutExplicitFingerprint() {
        val tls = SingBoxConfig.outbound(profile("security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab12&sni=example.org")).getJSONObject("tls")
        assertTrue(tls.has("utls"))
        assertTrue(tls.getJSONObject("utls").getBoolean("enabled"))
    }

    @Test fun alpnIsPreserved() {
        val tls = SingBoxConfig.outbound(profile("security=tls&alpn=h2%2Chttp%2F1.1")).getJSONObject("tls")
        assertEquals(listOf("h2", "http/1.1"), tls.getJSONArray("alpn").let { a -> (0 until a.length()).map(a::getString) })
    }

    @Test fun ipv6ServerHasNoUriBrackets() {
        assertEquals("2001:db8::1", SingBoxConfig.outbound(profile(host = "[2001:db8::1]")).getString("server"))
    }

    @Test fun websocketPathAndPlusAreNotLost() {
        val ws = SingBoxConfig.outbound(profile("security=tls&type=ws&path=%2Fws%3Fa%3D1%2B2&host=cdn.example.org")).getJSONObject("transport")
        assertEquals("/ws?a=1+2", ws.getString("path"))
        assertEquals("cdn.example.org", ws.getJSONObject("headers").getString("Host"))
    }

    @Test fun grpcServiceNameIsDecoded() {
        assertEquals("service/name", SingBoxConfig.outbound(profile("security=tls&type=grpc&serviceName=service%2Fname")).getJSONObject("transport").getString("service_name"))
    }

    @Test fun visionOverWebsocketIsRejectedBeforeTun() {
        assertThrows(IllegalArgumentException::class.java) { SingBoxConfig.outbound(profile("security=tls&type=ws&flow=xtls-rprx-vision")) }
    }

    @Test fun ambiguousAndUnsupportedSecurityParametersAreRejected() {
        listOf("security=tls&security=none", "security=tls&allowInsecure=1", "security=none&encryption=mlkem768x25519plus", "security=tls&type=grpc&mode=multi").forEach {
            assertThrows(IllegalArgumentException::class.java) { SingBoxConfig.outbound(profile(it)) }
        }
    }
}
