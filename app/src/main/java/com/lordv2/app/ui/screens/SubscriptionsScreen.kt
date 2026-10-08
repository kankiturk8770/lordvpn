package com.lordv2.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lordv2.app.data.Repo
import com.lordv2.app.data.Subscription
import com.lordv2.app.ui.UiDialog
import com.lordv2.app.ui.UiEvents
import com.lordv2.app.ui.components.*
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.ui.timeAgo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun refresh(scope: CoroutineScope, s: Subscription) {
    scope.launch {
        val r = Repo.refreshSubscription(s.id)
        r.onSuccess { n -> UiEvents.toast("${s.name} updated · $n configs") }
        r.onFailure { e ->
            UiEvents.show(
                UiDialog(
                    "Subscription Update Failed",
                    e.message ?: "Please try again.",
                    primary = "Retry", onPrimary = { refresh(scope, s) },
                    secondary = "Close",
                )
            )
        }
    }
}

@Composable
fun SubscriptionsScreen(nav: NavController, openAdd: Boolean) {
    val c = Lord.colors
    val subs by Repo.subscriptions.collectAsState()
    val updating by Repo.updatingSubs.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(openAdd) }
    var editing by remember { mutableStateOf<Subscription?>(null) }
    var deleting by remember { mutableStateOf<Subscription?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Subscriptions", "${subs.size} subscription${if (subs.size == 1) "" else "s"}", onBack = { nav.popBackStack() }) {
            if (subs.isNotEmpty()) RoundIconButton(Icons.Rounded.Refresh, "Update all", { subs.filter { it.enabled }.forEach { refresh(scope, it) } })
            RoundIconButton(Icons.Rounded.Add, "Add Subscription", { adding = true }, tint = c.cyan)
        }
        if (subs.isEmpty()) {
            EmptyState(Icons.Rounded.CloudDownload, "No subscriptions", "Add a subscription URL to receive and auto-update configs from your provider.", "Add Subscription") { adding = true }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(subs, key = { it.id }) { s ->
                    SubscriptionCard(
                        s = s,
                        updating = s.id in updating,
                        onUpdate = { refresh(scope, s) },
                        onToggle = { v -> Repo.updateSubscription(s.copy(enabled = v)) },
                        onAuto = { v -> Repo.updateSubscription(s.copy(autoUpdate = v)) },
                        onInterval = { h -> Repo.updateSubscription(s.copy(intervalHours = h)) },
                        onEdit = { editing = s },
                        onDelete = { deleting = s },
                    )
                }
            }
        }
    }

    if (adding) SubscriptionDialog(null, onDismiss = { adding = false }) { name, url ->
        adding = false
        val s = Repo.addSubscription(name, url)
        refresh(scope, s)
    }
    editing?.let { e ->
        SubscriptionDialog(e, onDismiss = { editing = null }) { name, url ->
            editing = null
            Repo.updateSubscription(e.copy(name = name.ifBlank { e.name }, url = url))
        }
    }
    deleting?.let { d ->
        LordAlert(
            "Delete subscription?", "\"${d.name}\" and its ${d.count} configurations will be removed.",
            confirm = "Delete" to { Repo.deleteSubscription(d.id); deleting = null },
            dismiss = "Cancel" to { deleting = null }, onDismiss = { deleting = null }, danger = true,
        )
    }
}

@Composable
private fun SubscriptionCard(
    s: Subscription,
    updating: Boolean,
    onUpdate: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAuto: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Lord.colors
    var expanded by remember { mutableStateOf(false) }
    val host = remember(s.url) { runCatching { Uri.parse(s.url).host }.getOrNull() ?: "link" }
    GlassCard(Modifier.fillMaxWidth(), onClick = { expanded = !expanded }, highlight = s.enabled && s.lastError == null && s.count > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = if (s.enabled) c.text else c.muted, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$host/•••", color = c.faint, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag("${s.count} configs", c.cyan)
                    Text("Updated ${timeAgo(s.lastUpdated)}", color = c.muted, fontSize = 12.sp)
                }
            }
            if (updating) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.cyan)
            else IconButton(onClick = onUpdate) { Icon(Icons.Rounded.Refresh, "Update Subscription", tint = c.cyan) }
            LordSwitch(s.enabled, onToggle)
        }
        if (s.lastError != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.lastError, color = c.danger, fontSize = 12.sp)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = c.stroke)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Auto Update", color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                LordSwitch(s.autoUpdate, onAuto)
            }
            if (s.autoUpdate) {
                Text("Update Interval", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                ChipRow(listOf(3, 6, 12, 24, 48).map { it.toString() to "${it}h" }, s.intervalHours.toString()) { onInterval(it.toInt()) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Edit", Modifier.weight(1f), Icons.Rounded.Edit, onClick = onEdit)
                GhostButton("Delete", Modifier.weight(1f), Icons.Rounded.Delete, color = c.danger, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun SubscriptionDialog(existing: Subscription?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val c = Lord.colors
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(24.dp),
        title = { Text(if (existing == null) "Add Subscription" else "Edit Subscription", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LordField("Subscription Name", name, { name = it }, placeholder = "My provider")
                LordField("Subscription URL", url, { url = it.trim(); error = null }, placeholder = "https://…", secret = existing != null)
                if (error != null) Text(error ?: "", color = c.danger, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    error = "Enter a valid http(s) URL"
                } else {
                    onSave(name, url)
                }
            }) { Text(if (existing == null) "Add" else "Save", color = c.cyan, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}
