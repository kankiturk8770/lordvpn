package com.lord.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Xray (libv2ray) runs in-process with SOCKS on 127.0.0.1.
 * TUN -> hev-socks5-tunnel -> SOCKS -> Xray -> server.
 * The app itself is excluded from the VPN, so Xray's own sockets never loop back.
 */
class LordVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.lord.vpn.START"
        const val ACTION_STOP = "com.lord.vpn.STOP"
        const val ACTION_EVENT = "com.lord.vpn.EVENT"
        private const val CHANNEL = "lordvpn"
        @Volatile var running = false
    }

    private var tun: ParcelFileDescriptor? = null
    private var hevStarted = false
    @Volatile private var statsOn = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(1, notification("Connecting…"))
                val link = intent.getStringExtra("config") ?: ""
                val s = JSONObject(intent.getStringExtra("settings") ?: "{}")
                thread(name = "lord-start") { start(link, s) }
            }
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(link: String, s: JSONObject) {
        try {
            if (!XrayCore.isAvailable()) throw IllegalStateException("Xray core missing (libv2ray.aar)")
            stopInternal()
            XrayCore.init(this)
            val mode = s.optString("mode", "vpn")
            val socks = s.optString("socks", "10808").toIntOrNull() ?: 10808

            val cfg = ConfigBuilder.build(link, s, XrayCore.hasGeo(this))
            emit("log", "Starting ${XrayCore.version()} · ${link.substringBefore("://")} · mode $mode")
            XrayCore.start(this, cfg)

            if (mode != "proxy") {
                if (!TProxyService.loaded) throw IllegalStateException("tun2socks library missing")
                val b = Builder()
                    .setSession("Lord VPN")
                    .setMtu(1500)
                    .addAddress("10.10.14.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                try { b.addAddress("fd00:10:14::1", 126); b.addRoute("::", 0) } catch (_: Exception) {}
                b.addDisallowedApplication(packageName)
                if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
                tun = b.establish() ?: throw IllegalStateException("VPN permission not granted")

                val yaml = """
                    tunnel:
                      mtu: 1500
                      ipv4: 10.10.14.1
                      ipv6: 'fd00:10:14::1'
                    socks5:
                      port: $socks
                      address: 127.0.0.1
                      udp: 'udp'
                    misc:
                      tcp-read-write-timeout: 300000
                      udp-read-write-timeout: 60000
                      log-level: warn
                """.trimIndent()
                val f = File(filesDir, "hev.yml").apply { writeText(yaml) }
                TProxyService.TProxyStartService(f.absolutePath, tun!!.fd)
                hevStarted = true
            } else {
                emit("log", "Proxy only: SOCKS 127.0.0.1:$socks, HTTP 127.0.0.1:${s.optString("http", "10809")}")
            }

            running = true
            startForeground(1, notification("Connected"))
            emit("connected", "")
            startStats()
        } catch (e: Throwable) {
            emit("error", (e.cause ?: e).message ?: "Failed to connect")
            stop()
        }
    }

    private fun startStats() {
        statsOn = true
        thread(name = "lord-stats") {
            while (statsOn) {
                Thread.sleep(1000)
                val up = XrayCore.queryStats("proxy", "uplink")
                val down = XrayCore.queryStats("proxy", "downlink")
                emit("stats", JSONObject().put("up", up / 1024).put("down", down / 1024).toString())
            }
        }
    }

    private fun stopInternal() {
        statsOn = false
        if (hevStarted) { try { TProxyService.TProxyStopService() } catch (_: Throwable) {}; hevStarted = false }
        try { XrayCore.stop() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        running = false
    }

    private fun stop() {
        stopInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        emit("stopped", "")
    }

    override fun onRevoke() = stop()
    override fun onDestroy() { stopInternal(); super.onDestroy() }

    private fun emit(event: String, data: String) {
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName)
            .putExtra("event", event).putExtra("data", data))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, LordVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setContentTitle("Lord VPN").setContentText(text)
            .setSmallIcon(R.drawable.ic_shield).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stopPi).build())
            .build()
    }
}
