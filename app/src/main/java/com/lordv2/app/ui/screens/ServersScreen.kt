package com.lordv2.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lordv2.app.data.Countries
import com.lordv2.app.data.PingTester
import com.lordv2.app.data.Profile
import com.lordv2.app.data.Repo
import com.lordv2.app.ui.LocalActions
import com.lordv2.app.ui.LocalImporter
import com.lordv2.app.ui.UiDialog
import com.lordv2.app.ui.UiEvents
import com.lordv2.app.ui.components.*
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.vpn.ConnState
import com.lordv2.app.vpn.VpnState
import kotlinx.coroutines.launch

/** Estimated load (configs carry no server load info, so it is derived from measured latency). */
private fun estimatedLoad(ms: Int): Int = when {
    ms <= 0 -> -1
    else -> (ms / 3).coerceIn(8, 96)
}

@Composable
fun ServersScreen(nav: NavController) {
    val c = Lord.colors
    val profiles by Repo.profiles.collectAsState()
    val subs by Repo.subscriptions.collectAsState()
    val selectedId by Repo.selectedId.collectAsState()
    val status by VpnState.status.collectAsState()
    val testing by PingTester.testing.collectAsState()
    val actions = LocalActions.current
    val importer = LocalImporter.current
    val scope = rememberCoroutineScope()
    var autoSelecting by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    val visible = remember(profiles, subs) { Repo.visible(profiles, subs) }
    val groups = remember(visible) {
        visible.groupBy { it.countryCode }.toList().sortedWith(
            compareBy<Pair<String, List<Profile>>> { (code, _) -> Countries.featured.indexOf(code).let { if (it < 0) 99 else it } }
                .thenBy { (code, _) -> if (code == "UN") "zzz" else Countries.get(code).name }
        ).map { (code, list) -> code to list.sortedWith(compareBy<Profile> { if (it.ping > 0) it.ping else Int.MAX_VALUE }.thenBy { it.name }) }
    }

    fun autoSelect() {
        if (visible.isEmpty()) { importer?.open(); return }
        autoSelecting = true
        scope.launch {
            val untested = visible.filter { it.ping == Profile.PING_UNTESTED }
            PingTester.testAll(if (untested.isNotEmpty()) untested else visible)
            val fresh = Repo.visible(Repo.profiles.value, Repo.subscriptions.value)
            val best = fresh.filter { it.ping > 0 }.minByOrNull { it.ping }
            autoSelecting = false
            if (best == null) {
                UiEvents.show(UiDialog("No reachable servers", "None of your configurations responded. Check your internet connection or update your subscriptions."))
            } else {
                Repo.select(best.id)
                UiEvents.toast("Auto selected ${best.name} · ${best.ping} ms")
                actions.connect(best.id)
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Servers", "${groups.size} location${if (groups.size == 1) "" else "s"} · ${visible.size} servers") {
            RoundIconButton(Icons.Rounded.Speed, "Test all", { scope.launch { PingTester.testAll(visible) } })
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "auto") {
                GlassCard(Modifier.fillMaxWidth(), onClick = { if (!autoSelecting) autoSelect() }, highlight = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c.primary, c.cyan))),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (autoSelecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.bg)
                            else Icon(Icons.Rounded.Bolt, null, tint = c.bg)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Auto Select", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(if (autoSelecting) "Finding the fastest server…" else "Connect to the fastest server by ping", color = c.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    EmptyState(Icons.Rounded.Public, "No servers yet", "Servers come from your configurations and subscriptions.", "Add Configuration") { importer?.open() }
                }
            }
            groups.forEach { (code, list) ->
                val country = Countries.get(code)
                val isCollapsed = collapsed[code] == true
                item(key = "h_$code") {
                    val rot by animateFloatAsState(if (isCollapsed) -90f else 0f, label = "rot")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable { collapsed[code] = !isCollapsed }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(country.flag, fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(country.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${list.size}", color = c.muted, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.ExpandMore, null, tint = c.muted, modifier = Modifier.rotate(rot))
                    }
                }
                if (!isCollapsed) {
                    items(list, key = { "s_" + it.id }) { p ->
                        val here = status.profileId == p.id && (status.state == ConnState.CONNECTED || status.state == ConnState.CONNECTING)
                        ServerRow(p, p.id == selectedId, here, p.id in testing) {
                            if (here) actions.disconnect() else actions.connect(p.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(p: Profile, selected: Boolean, connectedHere: Boolean, testing: Boolean, onConnect: () -> Unit) {
    val c = Lord.colors
    val fast = p.ping in 1..100
    val load = estimatedLoad(p.ping)
    GlassCard(Modifier.fillMaxWidth(), onClick = { Repo.select(p.id) }, highlight = selected, corner = 16.dp, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (fast) { Spacer(Modifier.width(6.dp)); Tag("FAST", c.success) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(p.protocol.label)
                    PingBadge(p.ping, testing)
                    if (load >= 0) {
                        Text("Load", color = c.faint, fontSize = 11.sp)
                        Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.stroke)) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(load / 100f).clip(RoundedCornerShape(3.dp))
                                    .background(if (load < 40) c.success else if (load < 70) c.warning else c.danger)
                            )
                        }
                        Text("$load%", color = c.muted, fontSize = 11.sp)
                    }
                }
            }
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (connectedHere) c.cyan.copy(alpha = 0.2f) else c.primary.copy(alpha = 0.14f)).clickable(onClick = onConnect),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (connectedHere) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew, if (connectedHere) "Disconnect" else "Connect", tint = c.cyan, modifier = Modifier.size(19.dp))
            }
        }
    }
}
