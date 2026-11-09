package app.onesvpn

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Build a sing-box outbound from an imported VLESS URI.
 * This does not start a VPN. Only explicitly supported transports are accepted.
 */
object SingBoxConfig {
    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    fun outbound(profile: VpnProfile): JSONObject {
        val uri = URI(profile.uri)
        require(uri.scheme.equals("vless", true)) { "Поддерживается только VLESS" }
        val query = mutableMapOf<String, String>()
        uri.rawQuery?.split("&")?.filter { it.isNotBlank() }?.forEach { pair ->
            val parts = pair.split("=", limit = 2)
            val key = decode(parts[0])
            require(!query.containsKey(key)) { "Повторяющийся параметр VLESS" }
            query[key] = decode(parts.getOrElse(1) { "" })
        }
        val transport = (query["type"]?.lowercase() ?: "tcp").let { if (it == "raw") "tcp" else it }
        require(transport in setOf("tcp", "ws", "grpc")) { "Транспорт VLESS пока не поддерживается" }
        val security = query["security"]?.lowercase() ?: "none"
        require(security in setOf("tls", "reality")) { "Для безопасного VLESS требуется TLS или Reality" }
        require(query["encryption"].isNullOrBlank() || query["encryption"] == "none") { "Шифрование VLESS не поддерживается" }
        require(listOf("allowInsecure", "insecure", "skip-cert-verify").none { query[it] in setOf("1", "true") }) { "Отключение проверки TLS запрещено" }
        require(query["headerType"].isNullOrBlank() || query["headerType"] == "none") { "Маскировка TCP не поддерживается" }
        require(query["mode"].isNullOrBlank() || query["mode"] == "gun") { "Режим gRPC не поддерживается" }
        val flow = query["flow"].orEmpty()
        require(flow.isEmpty() || flow == "xtls-rprx-vision") { "Неподдерживаемый flow" }
        require(!(flow.isNotEmpty() && security == "none")) { "Vision требует TLS/Reality" }
        require(flow.isEmpty() || transport == "tcp") { "Vision требует транспорта TCP" }
        require(security != "reality" || transport == "tcp") { "Reality поддерживается только с TCP" }
        val parsed = parseProfile(profile.uri)

        val result = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", parsed.host.removeSurrounding("[", "]"))
            .put("server_port", parsed.port)
            .put("uuid", uri.userInfo)
            .put("domain_resolver", "bootstrap")
            .put("connect_timeout", "10s")
        if (flow.isNotEmpty()) result.put("flow", flow)

        if (security != "none") {
            val tls = JSONObject().put("enabled", true)
            (query["sni"] ?: query["serverName"])?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
            query["alpn"]?.takeIf { it.isNotBlank() }?.let { value ->
                tls.put("alpn", JSONArray(value.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            }
            (query["fp"]?.takeIf { it.isNotBlank() } ?: if (security == "reality") "chrome" else null)?.let {
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
            }
            if (security == "reality") {
                val pbk = query["pbk"] ?: ""
                require(pbk.isNotBlank()) { "Для Reality требуется публичный ключ" }
                require(Regex("[A-Za-z0-9_-]{43}").matches(pbk)) { "Неверный публичный ключ Reality" }
                val sid = query["sid"].orEmpty()
                require(sid.length <= 16 && sid.length % 2 == 0 && sid.all { it in "0123456789abcdefABCDEF" }) { "Неверный short ID Reality" }
                tls.put("reality", JSONObject().put("enabled", true).put("public_key", pbk)
                    .put("short_id", query["sid"] ?: ""))
            }
            result.put("tls", tls)
        }
        when (transport) {
            "ws" -> {
                val ws = JSONObject().put("type", "ws").put("path", query["path"] ?: "/")
                query["host"]?.takeIf { it.isNotBlank() }?.let { host ->
                    ws.put("headers", JSONObject().put("Host", host))
                }
                result.put("transport", ws)
            }
            "grpc" -> result.put("transport", JSONObject().put("type", "grpc")
                .put("service_name", query["serviceName"] ?: ""))
        }
        return result
    }

    /**
     * Configuration for the pinned sing-box 1.14.4 engine.
     * Never log or share this JSON: it contains the VLESS credential.
     */
    fun buildTunConfig(profile: VpnProfile): JSONObject {
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "ones-tun")
            .put("address", JSONArray().put("172.19.0.1/30").put("fd00:19::1/126"))
            .put("auto_route", true)
            .put("strict_route", true)
            .put("dns_mode", "hijack")
            .put("mtu", 1400)
            .put("stack", "mixed")
        val outbounds = JSONArray()
            .put(outbound(profile))
        // Bootstrap resolves only the proxy endpoint outside the tunnel; application DNS uses proxy.
        fun doh(tag: String) = JSONObject().put("type", "https").put("tag", tag)
            .put("server", "1.1.1.1").put("server_port", 443).put("path", "/dns-query")
            .put("tls", JSONObject().put("enabled", true).put("server_name", "cloudflare-dns.com"))
        return JSONObject()
            .put("log", JSONObject().put("level", "error"))
            .put("dns", JSONObject().put("servers", JSONArray().put(doh("bootstrap"))
                .put(doh("remote").put("detour", "proxy")))
                .put("final", "remote").put("strategy", "prefer_ipv4"))
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("final", "proxy")
                .put("rules", JSONArray().put(JSONObject().put("port", 53).put("action", "hijack-dns")))
                .put("default_domain_resolver", "bootstrap")
                .put("auto_detect_interface", true))
    }

    fun preview(profile: VpnProfile): String = buildTunConfig(profile).toString(2)
}
