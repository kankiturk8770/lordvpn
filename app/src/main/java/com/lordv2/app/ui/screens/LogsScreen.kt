package com.lordv2.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lordv2.app.data.LogLevel
import com.lordv2.app.data.Repo
import com.lordv2.app.ui.clock
import com.lordv2.app.ui.components.*
import com.lordv2.app.ui.theme.Lord

@Composable
fun LogsScreen(nav: NavController) {
    val c = Lord.colors
    val logs by Repo.logs.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Connection Logs", "${logs.size} events", onBack = { nav.popBackStack() }) {
            if (logs.isNotEmpty()) RoundIconButton(Icons.Rounded.Delete, "Clear Logs", { confirm = true }, tint = c.danger)
        }
        if (logs.isEmpty()) {
            EmptyState(Icons.AutoMirrored.Rounded.List, "No logs yet", "Connection events will appear here.")
        } else {
            GlassCard(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), padding = PaddingValues(vertical = 6.dp)) {
                LazyColumn {
                    items(logs) { e ->
                        val col = when (e.level) {
                            LogLevel.SUCCESS -> c.success
                            LogLevel.ERROR -> c.danger
                            LogLevel.WARN -> c.warning
                            LogLevel.DEBUG -> c.faint
                            LogLevel.INFO -> c.primary
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
                            Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(col))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.message, color = if (e.level == LogLevel.DEBUG) c.muted else c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(clock(e.time), color = c.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm) LordAlert(
        "Clear logs?", "All connection events will be removed.",
        confirm = "Clear" to { Repo.clearLogs(); confirm = false },
        dismiss = "Cancel" to { confirm = false }, onDismiss = { confirm = false }, danger = true,
    )
}
