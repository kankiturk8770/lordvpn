package com.lord.vpn

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pendingConfig: String? = null
    private var pendingSettings: String? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) startVpn()
        else emit("error", "VPN permission denied")
    }

    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            emit(i.getStringExtra("event") ?: return, i.getStringExtra("data") ?: "")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#06141F")
        window.navigationBarColor = Color.parseColor("#06141F")

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true   // configs are saved in localStorage
            settings.allowFileAccess = true
            setBackgroundColor(Color.parseColor("#06141F"))
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "LordNative")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)

        ContextCompat.registerReceiver(
            this, events, IntentFilter(LordVpnService.ACTION_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        thread { try { XrayCore.init(applicationContext) } catch (_: Throwable) {} }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun emit(event: String, data: String) = runOnUiThread {
        web.evaluateJavascript(
            "window.onNative && onNative(${JSONObject.quote(event)}, ${JSONObject.quote(data)})", null
        )
    }

    private fun startVpn() {
        val i = Intent(this, LordVpnService::class.java).apply {
            action = LordVpnService.ACTION_START
            putExtra("config", pendingConfig)
            putExtra("settings", pendingSettings)
        }
        ContextCompat.startForegroundService(this, i)
    }

    inner class Bridge {
        @JavascriptInterface
        fun start(rawLink: String, settingsJson: String) {
            pendingConfig = rawLink
            pendingSettings = settingsJson
            runOnUiThread {
                val prep = VpnService.prepare(this@MainActivity)
                if (prep != null) vpnPermission.launch(prep) else startVpn()
            }
        }

        @JavascriptInterface
        fun stop() {
            startService(Intent(this@MainActivity, LordVpnService::class.java).apply {
                action = LordVpnService.ACTION_STOP
            })
        }

        @JavascriptInterface
        fun ping(id: String, rawLink: String, settingsJson: String) {
            thread {
                val ms = try {
                    val cfg = ConfigBuilder.build(rawLink, JSONObject(settingsJson), false, forPing = true)
                    XrayCore.measureDelay(applicationContext, cfg)
                } catch (e: Throwable) { -1L }
                emit("ping", JSONObject().put("id", id).put("ms", ms).toString())
            }
        }

        @JavascriptInterface
        fun isRunning(): Boolean = LordVpnService.running

        @JavascriptInterface
        fun coreInfo(): String = if (XrayCore.isAvailable()) XrayCore.version() else "missing"

        @JavascriptInterface
        fun readClipboard(): String {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        unregisterReceiver(events)
        super.onDestroy()
    }
}
