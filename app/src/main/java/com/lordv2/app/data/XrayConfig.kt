package com.lordv2.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Builds an Xray-core JSON configuration from a [Profile] and the user's settings. */
object XrayConfig {
    const val SOCKS_PORT = 10808
    const val HTTP_PORT = 10809

    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
        "169.254.0.0/16", "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128"
    )

    fun dnsServers(s: AppSettings): List<String> = when (s.dnsMode) {
        "custom" -> s.customDns.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("1.1.1.1") }
        "system" -> emptyList()
        else -> listOf("1.1.1.1", "8.8.8.8")
    }

    fun build(p: Profile, s: AppSettings, fullTunnel: Boolean): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", if (s.debugMode) "debug" else "warning"))

        val dns = dnsServers(s)
        if (dns.isNotEmpty()) {
            root.put("dns", JSONObject().put("servers", JSONArray(dns)).put("queryStrategy", if (s.ipv6) "UseIP" else "UseIPv4"))
        }

        val sniff = JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls", "quic")))
        val inbounds = JSONArray()
        inbounds.put(
            JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", SOCKS_PORT).put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true)).put("sniffing", sniff)
        )
        inbounds.put(
            JSONObject().put("tag", "http").put("listen", "127.0.0.1").put("port", HTTP_PORT).put("protocol", "http")
                .put("sniffing", sniff)
        )
        if (fullTunnel) {
            // Experimental: requires an Xray build with the TUN inbound (fd is passed by the core bridge).
            inbounds.put(
                JSONObject().put("tag", "tun").put("protocol", "tun")
                    .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500)).put("sniffing", sniff)
            )
        }
        root.put("inbounds", inbounds)

        val direct = JSONObject().put("tag", "direct").put("protocol", "freedom")
            .put("settings", JSONObject().put("domainStrategy", if (s.ipv6) "UseIP" else "UseIPv4"))
        val block = JSONObject().put("tag", "block").put("protocol", "blackhole")
        root.put("outbounds", JSONArray().put(outbound(p)).put(direct).put(block))

        val rules = JSONArray()
        rules.put(JSONObject().put("type", "field").put("ip", JSONArray(PRIVATE_CIDRS)).put("outboundTag", "direct"))
        when (s.routing) {
            "rule" -> rules.put(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray(listOf("domain:ir", "domain:local", "domain:lan")))
                    .put("outboundTag", "direct")
            )
            "direct" -> rules.put(JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", "direct"))
            else -> Unit
        }
        root.put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", rules))
        return root.toString(2)
    }

    fun outbound(p: Profile): JSONObject {
        val protocol = when (p.protocol) {
            Protocol.VMESS -> "vmess"
            Protocol.VLESS -> "vless"
            Protocol.TROJAN -> "trojan"
            Protocol.SHADOWSOCKS -> "shadowsocks"
            Protocol.SOCKS -> "socks"
        }
        val o = JSONObject().put("tag", "proxy").put("protocol", protocol)
        val settings: JSONObject = when (p.protocol) {
            Protocol.VMESS -> {
                val user = JSONObject().put("id", p.p("id")).put("alterId", p.p("aid", "0").toIntOrNull() ?: 0)
                    .put("security", p.p("scy", "auto"))
                JSONObject().put("vnext", JSONArray().put(server(p).put("users", JSONArray().put(user))))
            }
            Protocol.VLESS -> {
                val user = JSONObject().put("id", p.p("id")).put("encryption", p.p("encryption", "none"))
                if (p.p("flow").isNotBlank()) user.put("flow", p.p("flow"))
                JSONObject().put("vnext", JSONArray().put(server(p).put("users", JSONArray().put(user))))
            }
            Protocol.TROJAN -> JSONObject().put("servers", JSONArray().put(server(p).put("password", p.p("password"))))
            Protocol.SHADOWSOCKS -> JSONObject().put(
                "servers",
                JSONArray().put(server(p).put("method", p.p("method", "aes-256-gcm")).put("password", p.p("password")))
            )
            Protocol.SOCKS -> {
                val srv = server(p)
                if (p.p("user").isNotBlank()) {
                    srv.put("users", JSONArray().put(JSONObject().put("user", p.p("user")).put("pass", p.p("password"))))
                }
                JSONObject().put("servers", JSONArray().put(srv))
            }
        }
        o.put("settings", settings)
        if (p.protocol == Protocol.VMESS || p.protocol == Protocol.VLESS || p.protocol == Protocol.TROJAN) {
            o.put("streamSettings", stream(p))
        }
        return o
    }

    private fun server(p: Profile): JSONObject = JSONObject().put("address", p.address).put("port", p.port)

    private fun stream(p: Profile): JSONObject {
        val net = p.network.let { if (it == "raw") "tcp" else it }
        val ss = JSONObject().put("network", net)
        val sni = p.p("sni", p.p("host", p.address))
        val alpn = p.p("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        when (p.security) {
            "tls" -> {
                val t = JSONObject().put("serverName", sni)
                    .put("allowInsecure", p.p("allowInsecure") == "1" || p.p("allowInsecure") == "true")
                if (alpn.isNotEmpty()) t.put("alpn", JSONArray(alpn))
                if (p.p("fp").isNotBlank()) t.put("fingerprint", p.p("fp"))
                ss.put("security", "tls").put("tlsSettings", t)
            }
            "reality" -> {
                val r = JSONObject().put("serverName", sni).put("fingerprint", p.p("fp", "chrome"))
                    .put("publicKey", p.p("pbk")).put("shortId", p.p("sid")).put("spiderX", p.p("spx"))
                ss.put("security", "reality").put("realitySettings", r)
            }
            else -> ss.put("security", "none")
        }
        when (net) {
            "ws" -> {
                val w = JSONObject().put("path", p.p("path", "/"))
                if (p.p("host").isNotBlank()) w.put("headers", JSONObject().put("Host", p.p("host")))
                ss.put("wsSettings", w)
            }
            "grpc" -> ss.put("grpcSettings", JSONObject().put("serviceName", p.p("path")).put("multiMode", p.p("mode") == "multi"))
            "h2", "http" -> {
                val h = JSONObject().put("path", p.p("path", "/"))
                if (p.p("host").isNotBlank()) h.put("host", JSONArray(p.p("host").split(',').map { it.trim() }))
                ss.put("network", "http").put("httpSettings", h)
            }
            "httpupgrade" -> ss.put("httpupgradeSettings", JSONObject().put("path", p.p("path", "/")).put("host", p.p("host")))
            "xhttp", "splithttp" -> ss.put("network", "xhttp").put(
                "xhttpSettings",
                JSONObject().put("path", p.p("path", "/")).put("host", p.p("host")).put("mode", p.p("mode", "auto"))
            )
            "tcp" -> if (p.p("type") == "http") {
                val req = JSONObject().put("path", JSONArray(listOf(p.p("path", "/"))))
                    .put("headers", JSONObject().put("Host", JSONArray(listOf(p.p("host", p.address)))))
                ss.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", req)))
            }
            else -> Unit
        }
        return ss
    }
}
