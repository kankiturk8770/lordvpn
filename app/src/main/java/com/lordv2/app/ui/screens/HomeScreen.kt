package com.lordv2.app.ui.screens

import android.telephony.TelephonyManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lordv2.app.data.Countries
import com.lordv2.app.data.Profile
import com.lordv2.app.data.Repo
import com.lordv2.app.ui.LocalActions
import com.lordv2.app.ui.LocalImporter
import com.lordv2.app.ui.components.*
import com.lordv2.app.ui.formatBytes
import com.lordv2.app.ui.formatDuration
import com.lordv2.app.ui.formatSpeed
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.vpn.ConnState
import com.lordv2.app.vpn.VpnState
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun HomeScreen(nav: NavController) {
    val c = Lord.colors
    val ctx = LocalContext.current
    val status by VpnState.status.collectAsState()
    val profiles by Repo.profiles.collectAsState()
    val subs by Repo.subscriptions.collectAsState()
    val selectedId by Repo.selectedId.collectAsState()
    val settings by Repo.settings.collectAsState()
    val actions = LocalActions.current
    val importer = LocalImporter.current
    val haptics = LocalHapticFeedback.current

    val visible = remember(profiles, subs) { Repo.visible(profiles, subs) }
    val activeId = if (status.state != ConnState.DISCONNECTED && status.profileId != null) status.profileId else selectedId
    val active: Profile? = visible.firstOrNull { it.id == activeId } ?: visible.firstOrNull { it.id == selectedId }
    val home = remember {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        (tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country).uppercase(Locale.US)
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.state) {
        while (status.state == ConnState.CONNECTED) { now = System.currentTimeMillis(); delay(1000) }
    }

    val connected = status.state == ConnState.CONNECTED
    val glow by animateColorAsState(if (connected) c.cyan.copy(alpha = 0.16f) else c.primary.copy(alpha = 0.06f), tween(900), label = "glow")

    Column(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width / 2f, size.height * 0.30f), radius = size.width * 0.95f))
            }
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // ---- top bar
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            LordLogo(34.dp)
            Spacer(Modifier.width(10.dp))
            Text("LORD V2", color = c.text, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.5.sp, modifier = Modifier.weight(1f))
            RoundIconButton(Icons.Rounded.BarChart, "Statistics", { nav.navigate("stats") }, size = 40.dp)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.AutoMirrored.Rounded.List, "Connection logs", { nav.navigate("logs") }, size = 40.dp)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Rounded.Add, "Add configuration", { importer?.open() }, tint = c.cyan, size = 40.dp)
        }

        Spacer(Modifier.height(22.dp))
        StatusHeader(status.state, status.blocking, active, settings.animations)

        // ---- map
        if (settings.mapStyle != "off") {
            WorldMap(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                servers = visible.map { it.countryCode },
                selected = active?.countryCode,
                home = home,
                state = status.state,
                animate = settings.animations,
                style = settings.mapStyle,
            )
        } else Spacer(Modifier.height(24.dp))

        // ---- connect button
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectButton(status.state, status.blocking, settings.animations) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                actions.toggle()
            }
        }
        Spacer(Modifier.height(18.dp))

        // ---- active config
        if (active != null) {
            ActiveConfigCard(active, connected, status.ping.takeIf { connected } ?: active.ping) { nav.navigate("configs") { launchSingleTop = true } }
        } else {
            GlassCard(Modifier.fillMaxWidth(), onClick = { importer?.open() }, highlight = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AddCircle, null, tint = c.cyan, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Add your first configuration", color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Clipboard, QR code, file, URL or subscription", color = c.muted, fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.faint)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // ---- stat cards
        val duration = if (connected) formatDuration(now - status.since) else "00:00:00"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(Icons.Rounded.Language, "IP", if (connected) status.ip ?: "…" else "—", Modifier.weight(1f))
            StatCard(Icons.Rounded.NetworkCheck, "Ping", if (connected && status.ping > 0) "${status.ping} ms" else "—", Modifier.weight(1f))
            StatCard(Icons.Rounded.Timer, "Time", duration, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(Icons.Rounded.ArrowDownward, "Download", if (connected) formatSpeed(status.rxSpeed) else "—", Modifier.weight(1f), sub = if (connected) formatBytes(status.rxBytes) else null)
            StatCard(Icons.Rounded.ArrowUpward, "Upload", if (connected) formatSpeed(status.txSpeed) else "—", Modifier.weight(1f), sub = if (connected) formatBytes(status.txBytes) else null)
            StatCard(Icons.Rounded.VpnKey, "Config", active?.name ?: "None", Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusHeader(state: ConnState, blocking: Boolean, active: Profile?, animate: Boolean) {
    val c = Lord.colors
    val (label, color) = when (state) {
        ConnState.CONNECTED -> "CONNECTED" to c.cyan
        ConnState.CONNECTING -> "CONNECTING..." to c.primary
        ConnState.ERROR -> (if (blocking) "BLOCKED · KILL SWITCH" else "CONNECTION FAILED") to c.danger
        ConnState.DISCONNECTED -> "NOT CONNECTED" to c.muted
    }
    val col by animateColorAsState(color, tween(500), label = "status")
    var alpha = 1f
    if (state == ConnState.CONNECTING && animate) {
        val t = rememberInfiniteTransition(label = "blink")
        val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
        alpha = a
    }
    val country = active?.let { Countries.get(it.countryCode) }
    val subtitle = when (state) {
        ConnState.CONNECTED -> "${country?.name ?: "Server"} • ${active?.name ?: ""}"
        ConnState.CONNECTING -> "Securing your connection"
        ConnState.ERROR -> if (blocking) "Internet is blocked to protect you" else "Tap connect to try again"
        ConnState.DISCONNECTED -> "Your connection is not protected"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = col.copy(alpha = alpha), fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConnectButton(state: ConnState, blocking: Boolean, animate: Boolean, onClick: () -> Unit) {
    val c = Lord.colors
    val shape = RoundedCornerShape(30.dp)
    val connected = state == ConnState.CONNECTED
    val connecting = state == ConnState.CONNECTING
    val label = when {
        connecting -> "CONNECTING…"
        connected || blocking -> "DISCONNECT"
        else -> "CONNECT"
    }
    val bg = when {
        connected || blocking -> Brush.linearGradient(listOf(c.card, c.cardHi))
        connecting -> Brush.linearGradient(listOf(c.primary.copy(alpha = 0.85f), c.cyan.copy(alpha = 0.85f)))
        else -> Brush.linearGradient(listOf(c.primary, c.primary))
    }
    val fg = when {
        connected -> c.cyan
        blocking -> c.danger
        else -> Color.White
    }
    Box(
        Modifier
            .width(232.dp)
            .height(58.dp)
            .then(if (connected) Modifier.shadow(18.dp, shape, ambientColor = c.cyan, spotColor = c.cyan) else Modifier)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (connected) c.cyan.copy(alpha = 0.7f) else Color.Transparent, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (connecting && animate) ConnectingShimmer()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PowerSettingsNew, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun BoxScope.ConnectingShimmer() {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-0.5f, 1.5f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "x")
    Box(
        Modifier.matchParentSize().drawBehind {
            val w = size.width
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                    start = Offset(w * x - w * 0.35f, 0f),
                    end = Offset(w * x + w * 0.35f, size.height),
                )
            )
        }
    )
}

@Composable
private fun ActiveConfigCard(p: Profile, connected: Boolean, ping: Int, onClick: () -> Unit) {
    val c = Lord.colors
    val country = Countries.get(p.countryCode)
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick, highlight = connected, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlagBubble(p.countryCode, 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (connected) "ACTIVE CONFIG" else "SELECTED CONFIG", color = if (connected) c.cyan else c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                }
                Text(p.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(country.name, color = c.muted, fontSize = 12.sp)
                    Tag(p.protocol.label)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                PingBadge(ping)
                Spacer(Modifier.height(6.dp))
                Text("Change", color = c.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatCard(icon: ImageVector, label: String, value: String, modifier: Modifier, sub: String? = null) {
    val c = Lord.colors
    GlassCard(modifier, corner = 16.dp, padding = PaddingValues(horizontal = 12.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.cyan, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(label.uppercase(), color = c.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text(value, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, color = c.muted, fontSize = 11.sp, maxLines = 1)
    }
}
