package com.lordv2.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lordv2.app.data.Repo
import com.lordv2.app.ui.components.*
import com.lordv2.app.ui.formatBytes
import com.lordv2.app.ui.formatDuration
import com.lordv2.app.ui.formatSpeed
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.vpn.ConnState
import com.lordv2.app.vpn.VpnState
import kotlinx.coroutines.delay

@Composable
fun StatisticsScreen(nav: NavController) {
    val c = Lord.colors
    val status by VpnState.status.collectAsState()
    val samples by VpnState.samples.collectAsState()
    val totals by Repo.totals.collectAsState()
    val connected = status.state == ConnState.CONNECTED
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connected) { while (connected) { now = System.currentTimeMillis(); delay(1000) } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Statistics", if (connected) "Live session" else "Not connected", onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            GlassCard(Modifier.fillMaxWidth(), highlight = connected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Legend(c.cyan, "Download"); Spacer(Modifier.width(12.dp)); Legend(c.primary, "Upload")
                }
                Spacer(Modifier.height(12.dp))
                SpeedChart(samples, Modifier.fillMaxWidth().height(160.dp))
                Spacer(Modifier.height(10.dp))
                Row {
                    Text("↓ ${formatSpeed(status.rxSpeed)}", color = c.cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("↑ ${formatSpeed(status.txSpeed)}", color = c.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            val sessionTime = if (connected) now - status.since else 0L
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigStat(Icons.Rounded.Timer, "Connection Time", formatDuration(sessionTime), "Total ${formatDuration(totals.seconds * 1000 + sessionTime)}", Modifier.weight(1f))
                BigStat(Icons.Rounded.Speed, "Current Speed", formatSpeed(status.rxSpeed + status.txSpeed), "Down + Up", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigStat(Icons.Rounded.ArrowDownward, "Downloaded", formatBytes(status.rxBytes), "Total ${formatBytes(totals.rx + status.rxBytes)}", Modifier.weight(1f))
                BigStat(Icons.Rounded.ArrowUpward, "Uploaded", formatBytes(status.txBytes), "Total ${formatBytes(totals.tx + status.txBytes)}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigStat(Icons.Rounded.NetworkCheck, "Average Ping", if (totals.avgPing > 0) "${totals.avgPing} ms" else "—", if (connected && status.ping > 0) "Now ${status.ping} ms" else "All sessions", Modifier.weight(1f))
                BigStat(Icons.Rounded.Link, "Total Connections", "${totals.connections}", "Since install", Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            GhostButton("Reset statistics", Modifier.fillMaxWidth(), Icons.Rounded.RestartAlt) { Repo.resetTotals() }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    val c = Lord.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = c.muted, fontSize = 12.sp)
    }
}

@Composable
private fun BigStat(icon: ImageVector, label: String, value: String, sub: String, modifier: Modifier) {
    val c = Lord.colors
    GlassCard(modifier, corner = 18.dp, padding = PaddingValues(14.dp)) {
        Icon(icon, null, tint = c.cyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, color = c.muted, fontSize = 12.sp)
        Text(value, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(sub, color = c.faint, fontSize = 11.sp, maxLines = 1)
    }
}
