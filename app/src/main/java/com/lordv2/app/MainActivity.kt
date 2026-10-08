package com.lordv2.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.lordv2.app.data.LinkParser
import com.lordv2.app.data.LogLevel
import com.lordv2.app.data.Repo
import com.lordv2.app.ui.AppActions
import com.lordv2.app.ui.LocalActions
import com.lordv2.app.ui.LordRoot
import com.lordv2.app.ui.UiDialog
import com.lordv2.app.ui.UiEvents
import com.lordv2.app.ui.theme.LordTheme
import com.lordv2.app.ui.theme.resolveColors
import com.lordv2.app.vpn.ConnState
import com.lordv2.app.vpn.LordVpnService
import com.lordv2.app.vpn.VpnState

class MainActivity : ComponentActivity() {

    private var pendingId: String? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingId
        pendingId = null
        if (r.resultCode == RESULT_OK && id != null) {
            LordVpnService.connect(this, id)
        } else {
            Repo.log(LogLevel.WARN, "VPN permission was not granted")
            UiEvents.show(UiDialog("Permission needed", "Lord V2 needs VPN permission to protect your connection."))
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val actions = object : AppActions {
        override fun connect(id: String) = requestConnect(id)
        override fun disconnect() = LordVpnService.disconnect(this@MainActivity)
        override fun toggle() {
            val st = VpnState.status.value
            if (st.state == ConnState.CONNECTED || st.state == ConnState.CONNECTING || st.blocking) {
                disconnect()
            } else {
                val id = Repo.connectTargetId()
                if (id == null) {
                    UiEvents.toast("Add a configuration to get started")
                    UiEvents.requestAdd()
                } else requestConnect(id)
            }
        }
    }

    private fun requestConnect(id: String) {
        Repo.select(id)
        val prepare = try { VpnService.prepare(this) } catch (e: Exception) { null }
        if (prepare != null) {
            pendingId = id
            vpnPermission.launch(prepare)
        } else {
            LordVpnService.connect(this, id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)

        if (savedInstanceState == null) {
            val s = Repo.settings.value
            if (s.onboarded && s.autoStart && VpnState.status.value.state == ConnState.DISCONNECTED) {
                Repo.connectTargetId()?.let { id -> if (VpnService.prepare(this) == null) LordVpnService.connect(this, id) }
            }
        }

        setContent {
            val settings by Repo.settings.collectAsState()
            val colors = resolveColors(settings.theme)
            DisposableEffect(colors.isDark) {
                val style = if (colors.isDark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                this@MainActivity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            LordTheme(colors) {
                CompositionLocalProvider(LocalActions provides actions) { LordRoot() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val r = LinkParser.parseMany(text)
        if (r.profiles.isEmpty()) { UiEvents.invalidConfig(); return }
        val n = Repo.addProfiles(r.profiles)
        Repo.log(LogLevel.SUCCESS, "Imported $n configuration(s) from share")
        UiEvents.toast(if (n == 0) "Already in your list" else "Imported $n configuration${if (n > 1) "s" else ""}")
    }
}
