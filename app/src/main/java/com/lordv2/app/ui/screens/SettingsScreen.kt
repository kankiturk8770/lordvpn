package com.lordv2.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lordv2.app.BuildConfig
import com.lordv2.app.core.CoreBridge
import com.lordv2.app.data.LogLevel
import com.lordv2.app.data.Repo
import com.lordv2.app.data.XrayConfig
import com.lordv2.app.ui.LocalImporter
import com.lordv2.app.ui.UiEvents
import com.lordv2.app.ui.components.*
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

private enum class Picker { LANGUAGE, THEME, ROUTING, TIMEOUT, DEFAULT_CONFIG, MAP, TUNNEL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val c = Lord.colors
    val ctx = LocalContext.current
    val s by Repo.settings.collectAsState()
    val profiles by Repo.profiles.collectAsState()
    val importer = LocalImporter.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<Picker?>(null) }
    var dnsSheet by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var netInfo by remember { mutableStateOf(false) }
    fun upd(f: (com.lordv2.app.data.AppSettings) -> com.lordv2.app.data.AppSettings) = Repo.updateSettings(f)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Repo.exportBackup().toByteArray()) } }.isSuccess
            }
            if (ok) { Repo.log(LogLevel.INFO, "Backup exported"); UiEvents.toast("Backup exported") } else UiEvents.toast("Export failed")
        }
    }

    val themeLabel = mapOf("system" to "System", "dark" to "Dark", "light" to "Light", "amoled" to "AMOLED")
    val routingLabel = mapOf("global" to "Global", "rule" to "Rule", "direct" to "Direct")
    val dnsLabel = mapOf("auto" to "Automatic", "system" to "System DNS", "custom" to "Custom")
    val mapLabel = mapOf("dots" to "Dotted", "minimal" to "Minimal", "off" to "Hidden")

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Settings")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SectionLabel("General", Modifier.padding(top = 0.dp))
            SettingsGroup {
                SettingRow(Icons.Rounded.Translate, "Language", value = if (s.language == "fa") "فارسی" else "English", onClick = { picker = Picker.LANGUAGE })
                GroupDivider()
                SettingRow(Icons.Rounded.Palette, "Theme", value = themeLabel[s.theme] ?: "Dark", onClick = { picker = Picker.THEME })
                GroupDivider()
                SettingSwitch(Icons.Rounded.PlayArrow, "Auto Start", "Connect when Lord V2 opens", s.autoStart) { v -> upd { it.copy(autoStart = v) } }
                GroupDivider()
                SettingSwitch(Icons.Rounded.RestartAlt, "Start on Boot", "Connect after the phone restarts", s.startOnBoot) { v -> upd { it.copy(startOnBoot = v) } }
                GroupDivider()
                SettingSwitch(Icons.Rounded.Notifications, "Notifications", "Show live speed in the connection notification", s.notifications) { v -> upd { it.copy(notifications = v) } }
            }

            SectionLabel("Connection")
            SettingsGroup {
                SettingSwitch(Icons.Rounded.Wifi, "Auto Connect", "Connect when a network becomes available", s.autoConnect) { v -> upd { it.copy(autoConnect = v) } }
                GroupDivider()
                SettingSwitch(Icons.Rounded.Autorenew, "Auto Reconnect", "Reconnect after network changes", s.autoReconnect) { v -> upd { it.copy(autoReconnect = v) } }
                GroupDivider()
                SettingSwitch(Icons.Rounded.Shield, "Kill Switch", "Block internet traffic when VPN connection is lost.", s.killSwitch) { v -> upd { it.copy(killSwitch = v) } }
                if (s.killSwitch) {
                    SettingRow(Icons.Rounded.Security, "System-level protection", "Enable Always-on VPN and “Block connections without VPN” for full coverage", onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    })
                }
                GroupDivider()
                SettingRow(Icons.Rounded.Dns, "DNS", value = dnsLabel[s.dnsMode], onClick = { dnsSheet = true })
                GroupDivider()
                SettingRow(Icons.Rounded.Tune, "Routing Mode", value = routingLabel[s.routing], onClick = { picker = Picker.ROUTING })
                GroupDivider()
                SettingSwitch(Icons.Rounded.Language, "IPv6", "Route IPv6 traffic through the tunnel", s.ipv6) { v -> upd { it.copy(ipv6 = v) } }
                GroupDivider()
                SettingRow(Icons.Rounded.Timer, "Connection Timeout", value = "${s.timeoutSec} s", onClick = { picker = Picker.TIMEOUT })
            }

            SectionLabel("Configurations")
            SettingsGroup {
                val def = profiles.firstOrNull { it.id == s.defaultConfigId }
                SettingRow(Icons.Rounded.Star, "Default Configuration", value = def?.name ?: "Last used", onClick = { picker = Picker.DEFAULT_CONFIG })
                GroupDivider()
                SettingSwitch(Icons.Rounded.Update, "Auto Update Subscription", "Refresh subscriptions in the background", s.autoUpdateSubs) { v -> upd { it.copy(autoUpdateSubs = v) } }
                GroupDivider()
                SettingRow(Icons.Rounded.Cloud, "Subscriptions", onClick = { nav.navigate("subs") })
                GroupDivider()
                SettingRow(Icons.Rounded.FileDownload, "Import", "Configs or a Lord V2 backup", onClick = { importer?.open() })
                GroupDivider()
                SettingRow(Icons.Rounded.FileUpload, "Export Backup", "Save all configs to a file", onClick = { confirmExport = true })
            }

            SectionLabel("Appearance")
            SettingsGroup {
                AppearanceRow(Icons.Rounded.DarkMode, "Dark Mode", s.theme == "dark") { upd { it.copy(theme = "dark") } }
                GroupDivider()
                AppearanceRow(Icons.Rounded.LightMode, "Light Mode", s.theme == "light") { upd { it.copy(theme = "light") } }
                GroupDivider()
                AppearanceRow(Icons.Rounded.InvertColors, "AMOLED Mode", s.theme == "amoled") { upd { it.copy(theme = "amoled") } }
                GroupDivider()
                SettingSwitch(Icons.Rounded.Animation, "Animation", "Turn off to save battery", s.animations) { v -> upd { it.copy(animations = v) } }
                GroupDivider()
                SettingRow(Icons.Rounded.Map, "Map Style", value = mapLabel[s.mapStyle], onClick = { picker = Picker.MAP })
            }

            SectionLabel("Advanced")
            SettingsGroup {
                SettingRow(Icons.AutoMirrored.Rounded.List, "Logs", onClick = { nav.navigate("logs") })
                GroupDivider()
                SettingRow(Icons.Rounded.BarChart, "Statistics", onClick = { nav.navigate("stats") })
                GroupDivider()
                SettingRow(Icons.Rounded.Router, "Tunnel Mode", value = if (s.tunnelMode == "full") "Full (TUN)" else "Proxy", onClick = { picker = Picker.TUNNEL })
                GroupDivider()
                SettingSwitch(Icons.Rounded.BugReport, "Debug Mode", "Verbose engine logs (secrets stay hidden)", s.debugMode) { v -> upd { it.copy(debugMode = v) } }
                GroupDivider()
                SettingRow(Icons.Rounded.Info, "Network Information", onClick = { netInfo = true })
                GroupDivider()
                SettingRow(Icons.Rounded.RestartAlt, "Reset Settings", danger = true, onClick = { confirmReset = true })
            }

            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                LordLogo(36.dp)
                Spacer(Modifier.height(8.dp))
                Text("LORD V2", color = c.text, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("Version ${BuildConfig.VERSION_NAME} · Engine: ${CoreBridge.version()}", color = c.faint, fontSize = 12.sp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    when (picker) {
        Picker.LANGUAGE -> OptionDialog(
            "Language",
            listOf(Triple("en", "English", null), Triple("fa", "فارسی", "Persian UI strings are coming soon")),
            s.language, { v -> upd { it.copy(language = v) } }, { picker = null },
        )
        Picker.THEME -> OptionDialog(
            "Theme",
            listOf(Triple("system", "System", "Follow the device setting"), Triple("dark", "Dark", null), Triple("light", "Light", null), Triple("amoled", "AMOLED", "Pure black, saves battery on OLED")),
            s.theme, { v -> upd { it.copy(theme = v) } }, { picker = null },
        )
        Picker.ROUTING -> OptionDialog(
            "Routing Mode",
            listOf(
                Triple("global", "Global", "All traffic goes through the VPN"),
                Triple("rule", "Rule", "Local network and .ir domains connect directly"),
                Triple("direct", "Direct", "Nothing is proxied (testing only)"),
            ),
            s.routing, { v -> upd { it.copy(routing = v) } }, { picker = null },
        )
        Picker.TIMEOUT -> OptionDialog(
            "Connection Timeout",
            listOf(5, 10, 15, 20, 30).map { Triple(it.toString(), "$it seconds", null) },
            s.timeoutSec.toString(), { v -> upd { it.copy(timeoutSec = v.toInt()) } }, { picker = null },
        )
        Picker.DEFAULT_CONFIG -> OptionDialog(
            "Default Configuration",
            listOf<Triple<String, String, String?>>(Triple("", "Last used", "Use the most recently selected config")) + profiles.take(50).map { Triple(it.id, it.name, null) },
            s.defaultConfigId ?: "", { v -> upd { it.copy(defaultConfigId = v.ifBlank { null }) } }, { picker = null },
        )
        Picker.MAP -> OptionDialog(
            "Map Style",
            listOf(Triple("dots", "Dotted", "Full dotted world map"), Triple("minimal", "Minimal", "Lighter map, fewer dots"), Triple("off", "Hidden", "No map on the Home screen")),
            s.mapStyle, { v -> upd { it.copy(mapStyle = v) } }, { picker = null },
        )
        Picker.TUNNEL -> OptionDialog(
            "Tunnel Mode",
            listOf(
                Triple("proxy", "Proxy", "System HTTP proxy through the VPN (Android 10+). Most compatible."),
                Triple("full", "Full (TUN)", "Experimental. Every app is tunneled; needs an Xray core with TUN support."),
            ),
            s.tunnelMode, { v -> upd { it.copy(tunnelMode = v) } }, { picker = null },
        )
        null -> Unit
    }

    if (dnsSheet) DnsSheet(s.dnsMode, s.customDns, onSave = { mode, custom -> upd { it.copy(dnsMode = mode, customDns = custom) } }) { dnsSheet = false }

    if (confirmReset) LordAlert(
        "Reset settings?", "All settings return to their defaults. Your configurations stay untouched.",
        confirm = "Reset" to { Repo.resetSettings(); confirmReset = false; UiEvents.toast("Settings reset") },
        dismiss = "Cancel" to { confirmReset = false }, onDismiss = { confirmReset = false }, danger = true,
    )
    if (confirmExport) LordAlert(
        "Export backup?", "The backup file contains every configuration including private keys and passwords. Keep it somewhere safe.",
        confirm = "Export" to { confirmExport = false; exportLauncher.launch("lordv2-backup.json") },
        dismiss = "Cancel" to { confirmExport = false }, onDismiss = { confirmExport = false },
    )
    if (netInfo) NetworkInfoDialog(ctx) { netInfo = false }
}

@Composable
private fun AppearanceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, selected: Boolean, onClick: () -> Unit) {
    val c = Lord.colors
    SettingRow(icon, title, onClick = onClick) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = c.cyan, unselectedColor = c.faint))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsSheet(mode: String, custom: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    val c = Lord.colors
    var m by remember { mutableStateOf(mode) }
    var text by remember { mutableStateOf(custom) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.faint) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Text("DNS", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Choose how domain names are resolved", color = c.muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            listOf(
                Triple("auto", "Automatic", "Cloudflare 1.1.1.1 + Google 8.8.8.8 through the tunnel"),
                Triple("system", "System DNS", "Use the DNS servers of your network"),
                Triple("custom", "Custom DNS", "Enter your own servers"),
            ).forEach { (value, label, desc) ->
                SettingRow(Icons.Rounded.Dns, label, desc, onClick = { m = value }) {
                    RadioButton(selected = m == value, onClick = { m = value }, colors = RadioButtonDefaults.colors(selectedColor = c.cyan, unselectedColor = c.faint))
                }
            }
            if (m == "custom") {
                Spacer(Modifier.height(8.dp))
                LordField("DNS servers", text, { text = it }, placeholder = "1.1.1.1, 9.9.9.9")
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Save", Modifier.fillMaxWidth()) {
                val clean = text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
                val valid = clean.all { Regex("^[0-9a-fA-F:.]+$").matches(it) }
                if (m == "custom" && (clean.isEmpty() || !valid)) {
                    UiEvents.toast("Enter valid DNS IP addresses")
                    return@PrimaryButton
                }
                onSave(m, clean.joinToString(", ").ifBlank { text })
                onDismiss()
            }
        }
    }
}

@Composable
private fun NetworkInfoDialog(ctx: Context, onDismiss: () -> Unit) {
    val c = Lord.colors
    val status by VpnState.status.collectAsState()
    val s by Repo.settings.collectAsState()
    val rows = remember(status.state) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val localIp = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.hostAddress.orEmpty().startsWith("10.88.") }?.hostAddress
        }.getOrNull()
        listOf(
            "Status" to status.state.name.lowercase().replaceFirstChar { it.uppercase() },
            "Network" to type,
            "Local IP" to (localIp ?: "—"),
            "VPN IP" to (status.ip ?: "—"),
            "Engine" to CoreBridge.version(),
            "Tunnel" to (if (s.tunnelMode == "full") "Full (TUN)" else "Proxy"),
            "Local SOCKS" to "127.0.0.1:${XrayConfig.SOCKS_PORT}",
            "Local HTTP" to "127.0.0.1:${XrayConfig.HTTP_PORT}",
            "DNS" to (XrayConfig.dnsServers(s).joinToString(", ").ifBlank { "System" }),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Network Information", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (k, v) ->
                    Row {
                        Text(k, color = c.muted, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                        Text(v, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.cyan) } },
    )
}
