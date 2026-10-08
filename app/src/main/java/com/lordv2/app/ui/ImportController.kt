package com.lordv2.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.lordv2.app.data.LinkParser
import com.lordv2.app.data.LogLevel
import com.lordv2.app.data.Net
import com.lordv2.app.data.Repo
import com.lordv2.app.ui.components.TextInputDialog
import com.lordv2.app.ui.theme.Lord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns every "Add Configuration" path. Lives at the app root so activity results are never lost. */
class ImportController(private val ctx: Context, private val scope: CoroutineScope, private val nav: NavController) {
    var sheetOpen by mutableStateOf(false)
    var urlDialog by mutableStateOf(false)
    var busy by mutableStateOf(false)
    lateinit var fileLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>
    lateinit var qrLauncher: ManagedActivityResultLauncher<ScanOptions, ScanIntentResult>

    fun open() { sheetOpen = true }

    fun fromClipboard() {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx)?.toString() else null
        if (text.isNullOrBlank()) { UiEvents.toast("Clipboard is empty"); return }
        importText(text, "clipboard")
    }

    fun fromFile() = fileLauncher.launch(arrayOf("*/*"))

    fun fromQr() = qrLauncher.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan a Lord V2 / V2Ray configuration QR code")
            .setBeepEnabled(false)
    )

    fun manual() = nav.navigate("edit/new")

    fun subscription() = nav.navigate("subs_add")

    fun importText(text: String, source: String) {
        if (Repo.isBackup(text)) {
            val n = Repo.importBackup(text)
            if (n < 0) UiEvents.invalidConfig() else UiEvents.toast("Backup restored · $n configurations")
            return
        }
        val r = LinkParser.parseMany(text)
        if (r.profiles.isEmpty()) { UiEvents.invalidConfig(); return }
        val added = Repo.addProfiles(r.profiles)
        Repo.log(LogLevel.SUCCESS, "Imported $added configuration(s) from $source")
        val skipped = if (r.failed > 0) " · ${r.failed} skipped" else ""
        UiEvents.toast(if (added == 0) "Already in your list$skipped" else "Imported $added configuration${if (added > 1) "s" else ""}$skipped")
    }

    fun fromUrl(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            importText(url, "link"); return
        }
        busy = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { Net.fetchText(url, Repo.settings.value.timeoutSec * 1000 + 5000) }
                importText(text, "URL")
            } catch (e: Exception) {
                UiEvents.show(UiDialog("Import Failed", Repo.friendlyNetError(e), primary = "Retry", onPrimary = { fromUrl(url) }, secondary = "Close"))
            } finally { busy = false }
        }
    }

    fun onFile(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            if (text.isNullOrBlank()) UiEvents.invalidConfig() else importText(text, "file")
        }
    }

    fun onQr(res: ScanIntentResult) {
        val contents = res.contents ?: return
        importText(contents, "QR code")
    }
}

val LocalImporter = staticCompositionLocalOf<ImportController?> { null }

@Composable
fun rememberImportController(nav: NavController): ImportController {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { ImportController(ctx, scope, nav) }
    controller.fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { controller.onFile(it) }
    controller.qrLauncher = rememberLauncherForActivityResult(ScanContract()) { controller.onQr(it) }
    return controller
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConfigSheet(ctrl: ImportController) {
    val c = Lord.colors
    if (ctrl.sheetOpen) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { ctrl.sheetOpen = false },
            sheetState = state,
            containerColor = c.surface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = c.faint) },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                Text("Add Configuration", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Choose how you want to add a config", color = c.muted, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                fun go(a: () -> Unit) { ctrl.sheetOpen = false; a() }
                AddOption(Icons.Rounded.ContentPaste, "Import from Clipboard", "Paste vmess, vless, trojan, ss links") { go { ctrl.fromClipboard() } }
                AddOption(Icons.Rounded.FolderOpen, "Import from File", "Text file with links or a Lord V2 backup") { go { ctrl.fromFile() } }
                AddOption(Icons.Rounded.Link, "Import from URL", "Download configs from a link") { go { ctrl.urlDialog = true } }
                AddOption(Icons.Rounded.QrCodeScanner, "Scan QR Code", "Use your camera") { go { ctrl.fromQr() } }
                AddOption(Icons.Rounded.Edit, "Manual Configuration", "Enter server details yourself") { go { ctrl.manual() } }
                AddOption(Icons.Rounded.CloudDownload, "Subscription URL", "Auto-updating list of configs") { go { ctrl.subscription() } }
            }
        }
    }
    if (ctrl.urlDialog) {
        TextInputDialog(
            title = "Import from URL",
            label = "URL",
            placeholder = "https://example.com/configs.txt",
            confirm = "Import",
            onConfirm = { ctrl.urlDialog = false; ctrl.fromUrl(it) },
            onDismiss = { ctrl.urlDialog = false },
        )
    }
}

@Composable
private fun AddOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val c = Lord.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(c.card).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.cyan, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = c.muted, fontSize = 12.sp)
        }
    }
}
