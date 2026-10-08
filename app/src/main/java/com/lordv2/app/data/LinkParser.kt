package com.lordv2.app.data

import android.net.Uri
import org.json.JSONObject

/**
 * Parses and exports share links. Each protocol has its own small parser so new
 * protocols can be added without touching the rest of the app.
 */
object LinkParser {

    class ParseResult(val profiles: List<Profile>, val failed: Int)

    fun parseMany(input: String, subscriptionId: String? = null): ParseResult {
        var text = input.trim().removePrefix("\uFEFF")
        if (!text.contains("://")) {
            val decoded = Codec.b64decode(text)
            if (decoded != null && decoded.contains("://")) text = decoded
        }
        val out = ArrayList<Profile>()
        var failed = 0
        text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val p = parse(line, subscriptionId)
            if (p != null) out += p else if (line.contains("://")) failed++
        }
        return ParseResult(out, failed)
    }

    fun parse(link: String, subscriptionId: String? = null): Profile? = try {
        val scheme = link.substringBefore("://").lowercase()
        val parsed: Profile? = when (scheme) {
            "vmess" -> parseVmess(link)
            "vless" -> parseStd(link, Protocol.VLESS)
            "trojan" -> parseStd(link, Protocol.TROJAN)
            "ss" -> parseSs(link)
            "socks", "socks5" -> parseSocks(link)
            else -> null
        }
        parsed
            ?.let { p -> p.copy(subscriptionId = subscriptionId, countryCode = Countries.detect(p.name, p.address)) }
            ?.takeIf { it.address.isNotBlank() && it.port in 1..65535 }
    } catch (e: Exception) { null }

    // ---------------------------------------------------------------- parsers

    private fun parseVmess(link: String): Profile? {
        val json = Codec.b64decode(link.substringAfter("://").substringBefore('#')) ?: return null
        val o = JSONObject(json)
        val params = HashMap<String, String>()
        params["id"] = o.optString("id")
        params["aid"] = o.optString("aid", "0")
        params["scy"] = o.optString("scy", "auto")
        params["net"] = o.optString("net", "tcp")
        params["type"] = o.optString("type", "none")
        params["host"] = o.optString("host")
        params["path"] = o.optString("path")
        val tls = o.optString("tls")
        params["security"] = if (tls.isBlank() || tls == "none") "none" else tls
        params["sni"] = o.optString("sni")
        params["alpn"] = o.optString("alpn")
        params["fp"] = o.optString("fp")
        val address = o.optString("add")
        return Profile(
            name = o.optString("ps").ifBlank { address },
            protocol = Protocol.VMESS,
            address = address,
            port = o.optString("port").trim().toIntOrNull() ?: 0,
            params = params.filterValues { it.isNotBlank() },
        )
    }

    private fun parseStd(link: String, proto: Protocol): Profile? {
        val u = splitUrl(link)
        val q = u.query
        val params = HashMap<String, String>()
        if (proto == Protocol.VLESS) params["id"] = u.user else params["password"] = u.user
        params["net"] = q["type"] ?: "tcp"
        params["security"] = q["security"] ?: if (proto == Protocol.TROJAN) "tls" else "none"
        q["sni"]?.let { params["sni"] = it }
        q["host"]?.let { params["host"] = it }
        q["path"]?.let { params["path"] = it }
        q["serviceName"]?.let { params["path"] = it }
        q["mode"]?.let { params["mode"] = it }
        q["flow"]?.let { params["flow"] = it }
        q["fp"]?.let { params["fp"] = it }
        q["pbk"]?.let { params["pbk"] = it }
        q["sid"]?.let { params["sid"] = it }
        q["spx"]?.let { params["spx"] = it }
        q["alpn"]?.let { params["alpn"] = it }
        q["headerType"]?.let { params["type"] = it }
        q["encryption"]?.let { params["encryption"] = it }
        (q["allowInsecure"] ?: q["insecure"])?.let { params["allowInsecure"] = it }
        return Profile(
            name = u.fragment.ifBlank { "${u.host}:${u.port}" },
            protocol = proto,
            address = u.host,
            port = u.port,
            params = params.filterValues { it.isNotBlank() },
        )
    }

    private fun parseSs(link: String): Profile? {
        var body = link.substringAfter("://")
        val name = if (body.contains('#')) Uri.decode(body.substringAfter('#')) else ""
        body = body.substringBefore('#').substringBefore('?').trimEnd('/')
        val method: String
        val password: String
        val hostPort: String
        if (body.contains('@')) {
            val userInfo = body.substringBeforeLast('@')
            hostPort = body.substringAfterLast('@')
            val decoded = if (userInfo.contains(':')) Uri.decode(userInfo)
            else (Codec.b64decode(Uri.decode(userInfo)) ?: return null)
            method = decoded.substringBefore(':')
            password = decoded.substringAfter(':')
        } else {
            val decoded = Codec.b64decode(body) ?: return null
            method = decoded.substringBefore(':')
            val rest = decoded.substringAfter(':')
            password = rest.substringBeforeLast('@')
            hostPort = rest.substringAfterLast('@')
        }
        val host = hostPort.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: 0
        return Profile(
            name = name.ifBlank { "$host:$port" },
            protocol = Protocol.SHADOWSOCKS,
            address = host,
            port = port,
            params = mapOf("method" to method, "password" to password),
        )
    }

    private fun parseSocks(link: String): Profile? {
        val u = splitUrl(link)
        val params = HashMap<String, String>()
        if (u.user.isNotBlank()) {
            val creds = if (u.user.contains(':')) u.user else (Codec.b64decode(u.user) ?: u.user)
            params["user"] = creds.substringBefore(':')
            params["password"] = creds.substringAfter(':', "")
        }
        return Profile(name = u.fragment.ifBlank { "${u.host}:${u.port}" }, protocol = Protocol.SOCKS, address = u.host, port = u.port, params = params)
    }

    private class Url(val user: String, val host: String, val port: Int, val query: Map<String, String>, val fragment: String)

    private fun splitUrl(link: String): Url {
        var rest = link.substringAfter("://")
        val fragment = if (rest.contains('#')) Uri.decode(rest.substringAfter('#')) else ""
        rest = rest.substringBefore('#')
        val query = if (rest.contains('?')) rest.substringAfter('?') else ""
        rest = rest.substringBefore('?').trimEnd('/')
        val user = if (rest.contains('@')) Uri.decode(rest.substringBeforeLast('@')) else ""
        val hp = rest.substringAfterLast('@')
        val host: String
        val port: Int
        if (hp.startsWith("[")) {
            host = hp.substringAfter('[').substringBefore(']')
            port = hp.substringAfter("]:", "").toIntOrNull() ?: 0
        } else {
            host = hp.substringBeforeLast(':')
            port = hp.substringAfterLast(':', "").toIntOrNull() ?: 0
        }
        val qm = HashMap<String, String>()
        query.split('&').filter { it.contains('=') }.forEach {
            qm[Uri.decode(it.substringBefore('='))] = Uri.decode(it.substringAfter('='))
        }
        return Url(user, host, port, qm, fragment)
    }

    // ---------------------------------------------------------------- export

    fun toLink(p: Profile): String {
        val host = if (p.address.contains(':')) "[${p.address}]" else p.address
        return when (p.protocol) {
            Protocol.VMESS -> {
                val o = JSONObject()
                o.put("v", "2"); o.put("ps", p.name); o.put("add", p.address); o.put("port", p.port.toString())
                o.put("id", p.p("id")); o.put("aid", p.p("aid", "0")); o.put("scy", p.p("scy", "auto"))
                o.put("net", p.network); o.put("type", p.p("type", "none")); o.put("host", p.p("host"))
                o.put("path", p.p("path")); o.put("tls", if (p.security == "none") "" else p.security)
                o.put("sni", p.p("sni")); o.put("alpn", p.p("alpn")); o.put("fp", p.p("fp"))
                "vmess://" + Codec.b64encode(o.toString())
            }
            Protocol.VLESS, Protocol.TROJAN -> {
                val q = LinkedHashMap<String, String>()
                if (p.protocol == Protocol.VLESS) q["encryption"] = p.p("encryption", "none")
                q["type"] = p.network
                q["security"] = p.security
                listOf("sni", "host", "flow", "fp", "pbk", "sid", "spx", "alpn", "mode").forEach { k ->
                    p.params[k]?.takeIf { it.isNotBlank() }?.let { q[k] = it }
                }
                p.params["path"]?.takeIf { it.isNotBlank() }?.let { if (p.network == "grpc") q["serviceName"] = it else q["path"] = it }
                p.params["type"]?.takeIf { it.isNotBlank() && it != "none" }?.let { q["headerType"] = it }
                val qs = q.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
                "${p.protocol.scheme}://${Uri.encode(p.credential)}@$host:${p.port}?$qs#${Uri.encode(p.name)}"
            }
            Protocol.SHADOWSOCKS ->
                "ss://" + Codec.b64encode("${p.p("method")}:${p.p("password")}", urlSafe = true) + "@$host:${p.port}#${Uri.encode(p.name)}"
            Protocol.SOCKS -> {
                val auth = if (p.p("user").isNotBlank()) Codec.b64encode("${p.p("user")}:${p.p("password")}", urlSafe = true) + "@" else ""
                "socks://$auth$host:${p.port}#${Uri.encode(p.name)}"
            }
        }
    }
}
