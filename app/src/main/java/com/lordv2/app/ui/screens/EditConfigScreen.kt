package com.lordv2.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lordv2.app.data.Countries
import com.lordv2.app.data.LogLevel
import com.lordv2.app.data.Profile
import com.lordv2.app.data.Protocol
import com.lordv2.app.data.Repo
import com.lordv2.app.ui.UiEvents
import com.lordv2.app.ui.components.*
import com.lordv2.app.ui.theme.Lord

private val SS_METHODS = listOf(
    "aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "none",
)

@Composable
fun EditConfigScreen(nav: NavController, id: String) {
    val c = Lord.colors
    val existing = remember(id) { if (id == "new") null else Repo.profile(id) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.VLESS) }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "443") }
    var country by remember { mutableStateOf(existing?.countryCode ?: "AUTO") }
    val params = remember { mutableStateMapOf<String, String>().apply { existing?.params?.let { putAll(it) } } }
    fun v(k: String, d: String = "") = params[k] ?: d
    fun set(k: String, value: String) { params[k] = value }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        ScreenHeader(if (existing == null) "Manual Configuration" else "Edit Configuration", onBack = { nav.popBackStack() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LordField("Name", name, { name = it }, placeholder = "Lord Server 01")
            SectionLabel("Protocol", Modifier.padding(top = 0.dp))
            ChipRow(Protocol.entries.map { it.name to it.label }, protocol.name) { protocol = Protocol.of(it) }
            LordField("Server address", address, { address = it.trim() }, placeholder = "example.com or 1.2.3.4")
            LordField("Port", port, { port = it.filter { ch -> ch.isDigit() }.take(5) }, number = true)

            when (protocol) {
                Protocol.VMESS, Protocol.VLESS -> {
                    LordField("UUID", v("id"), { set("id", it.trim()) }, secret = true)
                    if (protocol == Protocol.VMESS) {
                        LordField("Alter ID", v("aid", "0"), { set("aid", it.filter { ch -> ch.isDigit() }) }, number = true)
                        SectionLabel("Encryption")
                        ChipRow(listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none").map { it to it }, v("scy", "auto")) { set("scy", it) }
                    } else {
                        LordField("Flow", v("flow"), { set("flow", it.trim()) }, placeholder = "xtls-rprx-vision (optional)")
                    }
                }
                Protocol.TROJAN -> LordField("Password", v("password"), { set("password", it) }, secret = true)
                Protocol.SHADOWSOCKS -> {
                    SectionLabel("Method")
                    ChipRow(SS_METHODS.map { it to it }, v("method", "aes-256-gcm")) { set("method", it) }
                    LordField("Password", v("password"), { set("password", it) }, secret = true)
                }
                Protocol.SOCKS -> {
                    LordField("Username (optional)", v("user"), { set("user", it) })
                    LordField("Password (optional)", v("password"), { set("password", it) }, secret = true)
                }
            }

            if (protocol == Protocol.VMESS || protocol == Protocol.VLESS || protocol == Protocol.TROJAN) {
                SectionLabel("Transport")
                ChipRow(
                    listOf("tcp" to "TCP", "ws" to "WebSocket", "grpc" to "gRPC", "h2" to "HTTP/2", "httpupgrade" to "HTTPUpgrade", "xhttp" to "XHTTP"),
                    v("net", "tcp"),
                ) { set("net", it) }
                when (v("net", "tcp")) {
                    "ws", "h2", "httpupgrade", "xhttp" -> {
                        LordField("Host", v("host"), { set("host", it.trim()) }, placeholder = "optional")
                        LordField("Path", v("path"), { set("path", it.trim()) }, placeholder = "/")
                    }
                    "grpc" -> LordField("Service name", v("path"), { set("path", it.trim()) })
                    else -> Unit
                }
                SectionLabel("Security")
                ChipRow(listOf("none" to "None", "tls" to "TLS", "reality" to "REALITY"), v("security", if (protocol == Protocol.TROJAN) "tls" else "none")) { set("security", it) }
                val sec = v("security", if (protocol == Protocol.TROJAN) "tls" else "none")
                if (sec == "tls" || sec == "reality") {
                    LordField("SNI", v("sni"), { set("sni", it.trim()) }, placeholder = "server name")
                    SectionLabel("Fingerprint")
                    ChipRow(listOf("", "chrome", "firefox", "safari", "edge", "randomized").map { it to it.ifBlank { "default" } }, v("fp")) { set("fp", it) }
                }
                if (sec == "tls") {
                    LordField("ALPN", v("alpn"), { set("alpn", it.trim()) }, placeholder = "h2,http/1.1 (optional)")
                }
                if (sec == "reality") {
                    LordField("Public key", v("pbk"), { set("pbk", it.trim()) }, secret = true)
                    LordField("Short ID", v("sid"), { set("sid", it.trim()) }, secret = true)
                    LordField("SpiderX", v("spx"), { set("spx", it.trim()) }, placeholder = "optional")
                }
            }

            SectionLabel("Country")
            ChipRow(
                listOf("AUTO" to "Auto detect") + Countries.all.map { it.code to "${it.flag} ${it.name}" },
                country,
            ) { country = it }
            Text("Secrets are stored encrypted on this device and hidden by default.", color = c.faint, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        PrimaryButton(
            "Save Configuration",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).navigationBarsPadding(),
            icon = Icons.Rounded.Check,
        ) {
            val portInt = port.toIntOrNull() ?: 0
            val cred = when (protocol) {
                Protocol.VMESS, Protocol.VLESS -> v("id")
                Protocol.TROJAN, Protocol.SHADOWSOCKS -> v("password")
                Protocol.SOCKS -> "ok"
            }
            if (address.isBlank() || portInt !in 1..65535 || cred.isBlank()) {
                UiEvents.invalidConfig()
                return@PrimaryButton
            }
            val finalName = name.trim().ifBlank { "$address:$portInt" }
            val cc = if (country == "AUTO") Countries.detect(finalName, address) else country
            val clean = params.toMap().filterValues { it.isNotBlank() }
            val p = (existing ?: Profile(name = finalName, protocol = protocol, address = address, port = portInt))
                .copy(name = finalName, protocol = protocol, address = address, port = portInt, countryCode = cc, params = clean, ping = existing?.ping ?: Profile.PING_UNTESTED)
            Repo.upsert(p)
            Repo.log(LogLevel.INFO, if (existing == null) "Configuration added: $finalName" else "Configuration updated: $finalName")
            UiEvents.toast("Configuration saved")
            nav.popBackStack()
        }
    }
}
