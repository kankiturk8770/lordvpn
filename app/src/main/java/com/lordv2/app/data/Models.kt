package com.lordv2.app.data

import org.json.JSONObject
import java.util.UUID

enum class Protocol(val label: String, val scheme: String) {
    VMESS("VMess", "vmess"),
    VLESS("VLESS", "vless"),
    TROJAN("Trojan", "trojan"),
    SHADOWSOCKS("Shadowsocks", "ss"),
    SOCKS("SOCKS", "socks");

    companion object {
        fun of(s: String?): Protocol =
            Protocol.entries.firstOrNull { it.name.equals(s, true) || it.scheme.equals(s, true) } ?: VLESS
    }
}

/** A single VPN configuration. Protocol specific values live in [params] so new protocols are easy to add. */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    val countryCode: String = "UN",
    val params: Map<String, String> = emptyMap(),
    val subscriptionId: String? = null,
    val ping: Int = PING_UNTESTED,
    val lastUsed: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun p(key: String, def: String = ""): String = params[key]?.takeIf { it.isNotBlank() } ?: def
    val network: String get() = p("net", "tcp")
    val security: String get() = p("security", "none")
    val credential: String
        get() = when (protocol) {
            Protocol.VMESS, Protocol.VLESS -> p("id")
            else -> p("password")
        }

    fun identityKey(): String = "${protocol.name}|$address|$port|$credential|${p("net")}|${p("path")}|${p("sni")}"

    fun toJson(): JSONObject {
        val pj = JSONObject()
        params.forEach { (k, v) -> pj.put(k, v) }
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("protocol", protocol.name)
        o.put("address", address); o.put("port", port); o.put("country", countryCode)
        o.put("params", pj)
        if (subscriptionId != null) o.put("sub", subscriptionId)
        o.put("ping", ping); o.put("lastUsed", lastUsed); o.put("createdAt", createdAt)
        return o
    }

    companion object {
        const val PING_UNTESTED = -1
        const val PING_FAILED = -2

        fun fromJson(o: JSONObject): Profile {
            val pj = o.optJSONObject("params") ?: JSONObject()
            val params = HashMap<String, String>()
            val keys = pj.keys()
            while (keys.hasNext()) { val k = keys.next(); params[k] = pj.optString(k) }
            return Profile(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name"),
                protocol = Protocol.of(o.optString("protocol")),
                address = o.optString("address"),
                port = o.optInt("port"),
                countryCode = o.optString("country", "UN"),
                params = params,
                subscriptionId = if (o.has("sub")) o.optString("sub") else null,
                ping = o.optInt("ping", PING_UNTESTED),
                lastUsed = o.optLong("lastUsed", 0L),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}

data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val autoUpdate: Boolean = true,
    val intervalHours: Int = 12,
    val lastUpdated: Long = 0L,
    val count: Int = 0,
    val lastError: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("url", url); put("enabled", enabled)
        put("autoUpdate", autoUpdate); put("interval", intervalHours); put("lastUpdated", lastUpdated)
        put("count", count); if (lastError != null) put("error", lastError)
    }

    companion object {
        fun fromJson(o: JSONObject) = Subscription(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            url = o.optString("url"),
            enabled = o.optBoolean("enabled", true),
            autoUpdate = o.optBoolean("autoUpdate", true),
            intervalHours = o.optInt("interval", 12),
            lastUpdated = o.optLong("lastUpdated", 0L),
            count = o.optInt("count", 0),
            lastError = if (o.has("error")) o.optString("error") else null,
        )
    }
}

enum class LogLevel { INFO, SUCCESS, WARN, ERROR, DEBUG }

data class LogEntry(val time: Long, val level: LogLevel, val message: String)

data class AppSettings(
    val onboarded: Boolean = false,
    val language: String = "en",
    val theme: String = "dark",          // system | dark | light | amoled
    val autoStart: Boolean = false,      // connect when the app is opened
    val startOnBoot: Boolean = false,
    val notifications: Boolean = true,   // detailed connection notification
    val autoConnect: Boolean = false,    // connect when a network becomes available
    val autoReconnect: Boolean = true,
    val killSwitch: Boolean = false,
    val dnsMode: String = "auto",        // auto | system | custom
    val customDns: String = "1.1.1.1, 8.8.8.8",
    val routing: String = "global",      // global | rule | direct
    val ipv6: Boolean = false,
    val timeoutSec: Int = 10,
    val defaultConfigId: String? = null,
    val autoUpdateSubs: Boolean = true,
    val animations: Boolean = true,
    val mapStyle: String = "dots",       // dots | minimal | off
    val debugMode: Boolean = false,
    val tunnelMode: String = "proxy",    // proxy | full
    val sortMode: String = "name",       // name | ping | recent | country
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("onboarded", onboarded); put("language", language); put("theme", theme)
        put("autoStart", autoStart); put("startOnBoot", startOnBoot); put("notifications", notifications)
        put("autoConnect", autoConnect); put("autoReconnect", autoReconnect); put("killSwitch", killSwitch)
        put("dnsMode", dnsMode); put("customDns", customDns); put("routing", routing); put("ipv6", ipv6)
        put("timeoutSec", timeoutSec); if (defaultConfigId != null) put("defaultConfigId", defaultConfigId)
        put("autoUpdateSubs", autoUpdateSubs); put("animations", animations); put("mapStyle", mapStyle)
        put("debugMode", debugMode); put("tunnelMode", tunnelMode); put("sortMode", sortMode)
    }

    companion object {
        fun fromJson(s: String?): AppSettings {
            if (s.isNullOrBlank()) return AppSettings()
            return try {
                val o = JSONObject(s)
                val d = AppSettings()
                AppSettings(
                    onboarded = o.optBoolean("onboarded", d.onboarded),
                    language = o.optString("language", d.language),
                    theme = o.optString("theme", d.theme),
                    autoStart = o.optBoolean("autoStart", d.autoStart),
                    startOnBoot = o.optBoolean("startOnBoot", d.startOnBoot),
                    notifications = o.optBoolean("notifications", d.notifications),
                    autoConnect = o.optBoolean("autoConnect", d.autoConnect),
                    autoReconnect = o.optBoolean("autoReconnect", d.autoReconnect),
                    killSwitch = o.optBoolean("killSwitch", d.killSwitch),
                    dnsMode = o.optString("dnsMode", d.dnsMode),
                    customDns = o.optString("customDns", d.customDns),
                    routing = o.optString("routing", d.routing),
                    ipv6 = o.optBoolean("ipv6", d.ipv6),
                    timeoutSec = o.optInt("timeoutSec", d.timeoutSec),
                    defaultConfigId = if (o.has("defaultConfigId")) o.optString("defaultConfigId") else null,
                    autoUpdateSubs = o.optBoolean("autoUpdateSubs", d.autoUpdateSubs),
                    animations = o.optBoolean("animations", d.animations),
                    mapStyle = o.optString("mapStyle", d.mapStyle),
                    debugMode = o.optBoolean("debugMode", d.debugMode),
                    tunnelMode = o.optString("tunnelMode", d.tunnelMode),
                    sortMode = o.optString("sortMode", d.sortMode),
                )
            } catch (e: Exception) { AppSettings() }
        }
    }
}

data class Totals(
    val connections: Int = 0,
    val rx: Long = 0L,
    val tx: Long = 0L,
    val seconds: Long = 0L,
    val pingSum: Long = 0L,
    val pingCount: Int = 0,
) {
    val avgPing: Int get() = if (pingCount == 0) -1 else (pingSum / pingCount).toInt()

    fun toJson(): JSONObject = JSONObject().apply {
        put("c", connections); put("rx", rx); put("tx", tx); put("s", seconds); put("ps", pingSum); put("pc", pingCount)
    }

    companion object {
        fun fromJson(s: String?): Totals = try {
            if (s.isNullOrBlank()) Totals() else JSONObject(s).let {
                Totals(it.optInt("c"), it.optLong("rx"), it.optLong("tx"), it.optLong("s"), it.optLong("ps"), it.optInt("pc"))
            }
        } catch (e: Exception) { Totals() }
    }
}
