package com.lord.vpn

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Converts a share link (vless/vmess/trojan/ss) + app settings into an Xray JSON config. */
object ConfigBuilder {

    fun build(link: String, s: JSONObject, hasGeo: Boolean, forPing: Boolean = false): String {
        val outbound = outbound(link, s)
        val rules = JSONArray()
        val global = s.optString("mode") == "global"
        if (hasGeo && !forPing && !global) {
        if (s.optBoolean("blockAds")) rules.put(rule("domain", JSONArray().put("geosite:category-ads-all"), "block"))
        if (s.optBoolean("bypassLan")) rules.put(rule("ip", JSONArray().put("geoip:private"), "direct"))
        if (s.optBoolean("bypassIr")) {
            rules.put(rule("ip", JSONArray().put("geoip:ir"), "direct"))
            rules.put(rule("domain", JSONArray().put("domain:ir"), "direct"))
        }
        }
        val socks = s.optString("socks", "10808").toIntOrNull() ?: 10808
        val http = s.optString("http", "10809").toIntOrNull() ?: 10809
        val sniff = JSONObject().put("enabled", s.optBoolean("sniff", true))
            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))

        val outbounds = JSONArray().put(outbound)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        if (s.optBoolean("fragment")) outbounds.put(JSONObject().put("tag", "fragment").put("protocol", "freedom")
            .put("settings", JSONObject().put("fragment", JSONObject().put("packets", "tlshello")
                .put("length", s.optString("fragLen", "10-20")).put("interval", "10-20")))
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("TcpNoDelay", true))))

        val inbounds = JSONArray()
        if (!forPing) inbounds
            .put(JSONObject().put("tag", "socks").put("port", socks).put("listen", "127.0.0.1")
                .put("protocol", "socks").put("settings", JSONObject().put("udp", true).put("auth", "noauth").put("ip", "127.0.0.1"))
                .put("sniffing", sniff))
            .put(JSONObject().put("tag", "http").put("port", http).put("listen", "127.0.0.1")
                .put("protocol", "http"))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("stats", JSONObject())
            .put("policy", JSONObject().put("system", JSONObject()
                .put("statsOutboundUplink", true).put("statsOutboundDownlink", true)))
            .put("dns", JSONObject().put("servers", JSONArray()
                .put(s.optString("remoteDns", "1.1.1.1"))
                .put(JSONObject().put("address", s.optString("localDns", "78.157.42.100"))
                    .put("domains", JSONArray().put("domain:ir")))))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject()
                .put("domainStrategy", s.optString("domainStrategy", "IPIfNonMatch"))
                .put("rules", rules))
            .toString()
    }

    private fun rule(key: String, v: JSONArray, tag: String) =
        JSONObject().put("type", "field").put(key, v).put("outboundTag", tag)

    private fun outbound(link: String, s: JSONObject): JSONObject {
        val proto = link.substringBefore("://").lowercase()
        val o = JSONObject().put("tag", "proxy")
        var net = "tcp"; var sec = "none"; var sni = ""; var path = ""; var host = ""
        var fp = "chrome"; var pbk = ""; var sid = ""; var alpn: String? = null; var spx = ""; var headerType = "none"

        when (proto) {
            "vmess" -> {
                val j = JSONObject(String(Base64.decode(link.substringAfter("://"), Base64.DEFAULT)))
                net = j.optString("net", "tcp"); sec = if (j.optString("tls") == "tls") "tls" else "none"
                sni = j.optString("sni"); path = j.optString("path"); host = j.optString("host")
                headerType = j.optString("type", "none"); fp = j.optString("fp", "chrome").ifEmpty { "chrome" }
                if (j.optString("alpn").isNotEmpty()) alpn = j.optString("alpn")
                o.put("protocol", "vmess").put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", j.getString("add")).put("port", j.optString("port").toInt())
                        .put("users", JSONArray().put(JSONObject().put("id", j.getString("id"))
                            .put("alterId", j.optInt("aid", 0)).put("security", j.optString("scy", "auto")))))))
            }
            "vless", "trojan" -> {
                val u = Uri.parse(link)
                net = u.getQueryParameter("type") ?: "tcp"
                sec = u.getQueryParameter("security") ?: if (proto == "trojan") "tls" else "none"
                sni = u.getQueryParameter("sni") ?: ""; host = u.getQueryParameter("host") ?: ""
                path = u.getQueryParameter("path") ?: u.getQueryParameter("serviceName") ?: ""
                fp = u.getQueryParameter("fp") ?: "chrome"
                pbk = u.getQueryParameter("pbk") ?: ""; sid = u.getQueryParameter("sid") ?: ""
                alpn = u.getQueryParameter("alpn"); spx = u.getQueryParameter("spx") ?: ""
                headerType = u.getQueryParameter("headerType") ?: "none"
                val user = Uri.decode(u.userInfo ?: "")
                if (proto == "vless") {
                    o.put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(
                        JSONObject().put("address", u.host).put("port", u.port)
                            .put("users", JSONArray().put(JSONObject().put("id", user).put("encryption", "none")
                                .put("flow", u.getQueryParameter("flow") ?: "")))))))
                } else {
                    o.put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(
                        JSONObject().put("address", u.host).put("port", u.port).put("password", user))))
                }
            }
            "ss" -> {
                var body = link.substringAfter("://").substringBefore("#").substringBefore("?")
                val (userinfo, hp) = if (body.contains("@")) {
                    val ui = body.substringBefore("@")
                    (if (ui.contains(":")) ui else String(Base64.decode(ui, Base64.URL_SAFE or Base64.NO_WRAP))) to body.substringAfter("@")
                } else {
                    val d = String(Base64.decode(body, Base64.DEFAULT)); d.substringBeforeLast("@") to d.substringAfterLast("@")
                }
                o.put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject().put("address", hp.substringBeforeLast(":")).put("port", hp.substringAfterLast(":").toInt())
                        .put("method", userinfo.substringBefore(":")).put("password", userinfo.substringAfter(":")))))
            }
            else -> throw IllegalArgumentException("Unsupported protocol: $proto")
        }

        val stream = JSONObject().put("network", net).put("security", sec)
        if (sec == "tls") stream.put("tlsSettings", JSONObject().put("serverName", sni)
            .put("allowInsecure", s.optBoolean("insecure")).put("fingerprint", fp)
            .apply { alpn?.let { put("alpn", JSONArray(it.split(","))) } })
        if (sec == "reality") stream.put("realitySettings", JSONObject().put("serverName", sni)
            .put("fingerprint", fp).put("publicKey", pbk).put("shortId", sid).put("spiderX", spx))
        when (net) {
            "tcp" -> if (headerType == "http") stream.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http")
                .put("request", JSONObject().put("path", JSONArray().put(path.ifEmpty { "/" }))
                    .put("headers", JSONObject().put("Host", JSONArray().put(host))))))
            "ws" -> stream.put("wsSettings", JSONObject().put("path", path.ifEmpty { "/" }).put("headers", JSONObject().put("Host", host)))
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", path).put("multiMode", false))
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().put("path", path).put("host", host))
            "xhttp" -> stream.put("xhttpSettings", JSONObject().put("path", path).put("host", host))
        }
        if (s.optBoolean("fragment")) stream.put("sockopt", JSONObject().put("dialerProxy", "fragment"))
        if (sni.isEmpty() && host.isNotEmpty() && (sec == "tls")) stream.optJSONObject("tlsSettings")?.put("serverName", host)
        o.put("streamSettings", stream)
        if (s.optBoolean("mux")) o.put("mux", JSONObject().put("enabled", true).put("concurrency", 8))
        return o
    }
}
