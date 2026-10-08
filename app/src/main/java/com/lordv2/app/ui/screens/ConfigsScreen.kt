package com.lordv2.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PersistableBundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.lordv2.app.data.*
import com.lordv2.app.ui.LocalActions
import com.lordv2.app.ui.LocalImporter
import com.lordv2.app.ui.UiEvents
import com.lordv2.app.ui.components.*
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.ui.timeAgo
import com.lordv2.app.vpn.ConnState
import com.lordv2.app.vpn.VpnState
import kotlinx.coroutines.launch

enum class ConfigAction { EDIT, DUPLICATE, RENAME, TEST, EXPORT, DELETE }

fun sortProfiles(list: List<Profile>, mode: String): List<Profile> = when (mode) {
    "ping" -> list.sortedWith(compareBy<Profile> { if (it.ping > 0) 0 else 1 }.thenBy { if (it.ping > 0) it.ping else Int.MAX_VALUE }.thenBy { it.name.lowercase() })
    "recent" -> list.sortedByDescending { it.lastUsed }
    "country" -> list.sortedWith(compareBy<Profile> { Countries.get(it.countryCode).name }.thenBy { it.name.lowercase() })
    else -> list.sortedBy { it.name.lowercase() }
}

@Composable
fun ConfigsScreen(nav: NavController) {
    val c = Lord.colors
    val profiles by Repo.profiles.collectAsState()
    val subs by Repo.subscriptions.collectAsState()
    val selectedId by Repo.selectedId.collectAsState()
    val settings by Repo.settings.collectAsState()
    val status by VpnState.status.collectAsState()
    val testing by PingTester.testing.collectAsState()
    val actions = LocalActions.current
    val importer = LocalImporter.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }

    val visible = remember(profiles, subs, query, settings.sortMode) {
        val q = query.trim().lowercase()
        val base = Repo.visible(profiles, subs).filter { p ->
            q.isEmpty() || p.name.lowercase().contains(q) || p.address.lowercase().contains(q) ||
                Countries.get(p.countryCode).name.lowercase().contains(q) || p.protocol.label.lowercase().contains(q)
        }
        sortProfiles(base, settings.sortMode)
    }

    var renameTarget by remember { mutableStateOf<Profile?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    var exportTarget by remember { mutableStateOf<Profile?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Configurations", "${visible.size} config${if (visible.size == 1) "" else "s"}") {
            RoundIconButton(Icons.Rounded.Speed, "Test all", { scope.launch { PingTester.testAll(visible) } })
            RoundIconButton(Icons.Rounded.Cloud, "Subscriptions", { nav.navigate("subs") })
            RoundIconButton(Icons.Rounded.Add, "Add configuration", { importer?.open() }, tint = c.cyan)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search configurations") },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.muted) },
            trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = c.muted) } } } else null,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = lordFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = 20.dp)) {
            ChipRow(
                listOf("name" to "Name", "ping" to "Ping", "recent" to "Recently Used", "country" to "Country"),
                settings.sortMode,
            ) { m -> Repo.updateSettings { it.copy(sortMode = m) } }
        }
        Spacer(Modifier.height(6.dp))

        if (visible.isEmpty()) {
            if (query.isNotEmpty()) EmptyState(Icons.Rounded.Search, "No results", "Nothing matches \"$query\".")
            else EmptyState(Icons.Rounded.Layers, "No configurations yet", "Import from clipboard, QR code, file, URL or add a subscription.", "Add Configuration") { importer?.open() }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.id }) { p ->
                    val here = status.profileId == p.id && (status.state == ConnState.CONNECTED || status.state == ConnState.CONNECTING)
                    ConfigCard(
                        p = p,
                        active = p.id == selectedId,
                        connectedHere = here,
                        testing = p.id in testing,
                        onSelect = { Repo.select(p.id) },
                        onConnect = { if (here) actions.disconnect() else actions.connect(p.id) },
                        onAction = { a ->
                            when (a) {
                                ConfigAction.EDIT -> nav.navigate("edit/${p.id}")
                                ConfigAction.DUPLICATE -> { Repo.duplicate(p.id); UiEvents.toast("Duplicated") }
                                ConfigAction.RENAME -> { renameTarget = p }
                                ConfigAction.TEST -> { scope.launch { PingTester.test(p) } }
                                ConfigAction.EXPORT -> { exportTarget = p }
                                ConfigAction.DELETE -> { deleteTarget = p }
                            }
                            Unit
                        },
                    )
                }
            }
        }
    }

    renameTarget?.let { p ->
        TextInputDialog("Rename", "Name", initial = p.name, onConfirm = { Repo.rename(p.id, it); renameTarget = null }, onDismiss = { renameTarget = null })
    }
    deleteTarget?.let { p ->
        LordAlert(
            title = "Delete configuration?",
            message = "\"${p.name}\" will be removed from this device.",
            confirm = "Delete" to { Repo.delete(p.id); deleteTarget = null; UiEvents.toast("Configuration deleted") },
            dismiss = "Cancel" to { deleteTarget = null },
            onDismiss = { deleteTarget = null },
            danger = true,
        )
    }
    exportTarget?.let { p -> ExportDialog(p) { exportTarget = null } }
}

@Composable
fun ConfigCard(
    p: Profile,
    active: Boolean,
    connectedHere: Boolean,
    testing: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onAction: (ConfigAction) -> Unit,
) {
    val c = Lord.colors
    val country = Countries.get(p.countryCode)
    var menu by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), onClick = onSelect, highlight = active, padding = PaddingValues(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlagBubble(p.countryCode, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(country.name, color = c.muted, fontSize = 12.sp, maxLines = 1)
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.CheckCircle, "Active", tint = c.cyan, modifier = Modifier.size(14.dp))
                        Text(" ACTIVE", color = c.cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
                Text(p.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(p.protocol.label)
                    Tag(p.network.uppercase(), c.cyan)
                    PingBadge(p.ping, testing)
                }
                Spacer(Modifier.height(4.dp))
                val statusText = when {
                    connectedHere -> "Connected"
                    p.ping > 0 -> "Online"
                    p.ping == Profile.PING_FAILED -> "Unreachable"
                    else -> "Not tested"
                }
                Text("${p.address}:${p.port} · $statusText · ${timeAgo(p.lastUsed)}", color = c.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (connectedHere) c.cyan.copy(alpha = 0.18f) else c.primary.copy(alpha = 0.14f))
                    .clickable(onClick = onConnect),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (connectedHere) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew, if (connectedHere) "Disconnect" else "Connect", tint = c.cyan, modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = c.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(Icons.Rounded.Edit, "Edit") { menu = false; onAction(ConfigAction.EDIT) }
                    MenuItem(Icons.Rounded.ContentCopy, "Duplicate") { menu = false; onAction(ConfigAction.DUPLICATE) }
                    MenuItem(Icons.Rounded.TextFields, "Rename") { menu = false; onAction(ConfigAction.RENAME) }
                    MenuItem(Icons.Rounded.NetworkCheck, "Test Ping") { menu = false; onAction(ConfigAction.TEST) }
                    MenuItem(Icons.Rounded.Share, "Export") { menu = false; onAction(ConfigAction.EXPORT) }
                    MenuItem(Icons.Rounded.Delete, "Delete", danger = true) { menu = false; onAction(ConfigAction.DELETE) }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Lord.colors
    val col = if (danger) c.danger else c.text
    DropdownMenuItem(
        text = { Text(text, color = col) },
        leadingIcon = { Icon(icon, null, tint = if (danger) c.danger else c.muted) },
        onClick = onClick,
    )
}

fun copySensitive(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}

@Composable
fun ExportDialog(p: Profile, onDismiss: () -> Unit) {
    val c = Lord.colors
    val ctx = LocalContext.current
    val link = remember(p) { LinkParser.toLink(p) }
    var showQr by remember { mutableStateOf(false) }
    val qr: Bitmap? = remember(link, showQr) {
        if (!showQr) null else runCatching { BarcodeEncoder().encodeBitmap(link, BarcodeFormat.QR_CODE, 640, 640) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Export configuration", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("This contains private credentials. Only share it with people you trust.", color = c.muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                if (qr != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(qr.asImageBitmap(), "QR code", Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text(
                        Sanitizer.clean(link),
                        color = c.faint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).padding(12.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Copy", Modifier.weight(1f), Icons.Rounded.ContentCopy) {
                        copySensitive(ctx, "Lord V2 config", link)
                        Repo.log(LogLevel.INFO, "Exported \"${p.name}\" to clipboard")
                        UiEvents.toast("Copied to clipboard"); onDismiss()
                    }
                    GhostButton(if (showQr) "Hide QR" else "QR", Modifier.weight(1f), Icons.Rounded.QrCode) { showQr = !showQr }
                }
                Spacer(Modifier.height(8.dp))
                GhostButton("Share", Modifier.fillMaxWidth(), Icons.Rounded.Share) {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    ctx.startActivity(Intent.createChooser(i, "Export configuration"))
                    onDismiss()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.muted) } },
    )
}
