package com.lordv2.app.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.content.ContextCompat
import com.lordv2.app.MainActivity
import com.lordv2.app.core.CoreBridge
import com.lordv2.app.data.AppSettings
import com.lordv2.app.data.Countries
import com.lordv2.app.data.LogLevel
import com.lordv2.app.data.Net
import com.lordv2.app.data.Profile
import com.lordv2.app.data.Repo
import com.lordv2.app.data.XrayConfig
import com.lordv2.app.ui.formatSpeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

class LordVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.lordv2.app.CONNECT"
        const val ACTION_DISCONNECT = "com.lordv2.app.DISCONNECT"
        const val EXTRA_ID = "profile_id"

        fun connect(ctx: Context, id: String) {
            AutoConnectWatcher.userDisconnected = false
            val i = Intent(ctx, LordVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun disconnect(ctx: Context) {
            AutoConnectWatcher.userDisconnected = true
            try {
                ctx.startService(Intent(ctx, LordVpnService::class.java).setAction(ACTION_DISCONNECT))
            } catch (e: Exception) {
                VpnState.status.value = VpnStatus()
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var connectJob: Job? = null
    private var monitorJob: Job? = null
    private var current: Profile? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                AutoConnectWatcher.userDisconnected = true
                teardown(null)
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val p = Repo.profile(intent.getStringExtra(EXTRA_ID))
                if (p == null) {
                    startFg(Notifications.build(this, "Lord V2", "No configuration"))
                    teardown("Configuration not found.")
                    return START_NOT_STICKY
                }
                begin(p)
            }
            else -> {
                // Started by the system (Always-on VPN) or restarted after being killed.
                val p = Repo.profile(Repo.connectTargetId())
                if (p == null) {
                    startFg(Notifications.build(this, "Lord V2", "No configuration"))
                    teardown(null)
                    return START_NOT_STICKY
                }
                begin(p)
            }
        }
        return START_STICKY
    }

    private fun begin(p: Profile) {
        startFg(Notifications.build(this, "Connecting…", "${Countries.get(p.countryCode).name} • ${p.name}"))
        connectJob?.cancel()
        connectJob = scope.launch { connect(p) }
    }

    private fun startFg(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID, n)
        }
    }

    private suspend fun connect(p: Profile) {
        val s = Repo.settings.value
        val full = s.tunnelMode == "full"
        val country = Countries.get(p.countryCode)
        current = p
        monitorJob?.cancel()
        VpnState.status.value = VpnStatus(state = ConnState.CONNECTING, profileId = p.id)
        Repo.log(LogLevel.INFO, "Connecting to ${country.name} · ${p.name}")
        try {
            CoreBridge.stop()
            val config = XrayConfig.build(p, s, full)
            Repo.log(LogLevel.DEBUG, "Engine config ready (${p.protocol.label} / ${p.network} / ${p.security})")
            val pfd = establishTun(s, full) ?: throw IllegalStateException("VPN permission is required.")
            val old = tun
            tun = pfd
            runCatching { old?.close() }
            CoreBridge.start(this, config, pfd.fd) { fd -> protect(fd) }
            Repo.log(LogLevel.DEBUG, "Core started (${CoreBridge.version()})")

            val delayMs = Net.realDelay(s.timeoutSec * 1000)
            if (delayMs < 0) throw IOException("The server did not respond. Check the configuration or try another server.")

            val since = System.currentTimeMillis()
            VpnState.update { it.copy(state = ConnState.CONNECTED, since = since, ping = delayMs, error = null, blocking = false) }
            Repo.select(p.id)
            Repo.markUsed(p.id)
            Repo.setPing(p.id, delayMs)
            Repo.recordConnection()
            Repo.addPingSample(delayMs)
            Repo.log(LogLevel.SUCCESS, "Connected to ${country.name}")
            Repo.log(LogLevel.INFO, "Connection established")
            Repo.log(LogLevel.INFO, "Ping: $delayMs ms")
            refreshNotification()
            startMonitor()
            watchNetwork()
            val ip = Net.publicIp(s.timeoutSec * 1000)
            if (ip != null) VpnState.update { it.copy(ip = ip) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = friendlyError(e)
            CoreBridge.stop()
            if (s.killSwitch && tun != null) {
                VpnState.update { it.copy(state = ConnState.ERROR, error = msg, blocking = true) }
                Repo.log(LogLevel.ERROR, "Connection failed: $msg")
                Repo.log(LogLevel.WARN, "Kill Switch active · internet blocked until you reconnect or disconnect")
                Notifications.notify(this, Notifications.build(this, "Connection lost", "Kill Switch is blocking traffic"))
            } else {
                teardown(msg)
            }
        }
    }

    private fun establishTun(s: AppSettings, full: Boolean): ParcelFileDescriptor? {
        val b = Builder()
            .setSession("Lord V2")
            .setMtu(1500)
            .addAddress("10.88.0.1", 30)
        val routeAll = full || s.killSwitch
        if (routeAll) b.addRoute("0.0.0.0", 0)
        if (s.ipv6) {
            runCatching { b.addAddress("fd00:88::1", 126) }
            if (routeAll) runCatching { b.addRoute("::", 0) }
        }
        XrayConfig.dnsServers(s).forEach { d -> runCatching { b.addDnsServer(d) } }
        runCatching { b.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) {
            b.setMetered(false)
            if (!full) b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", XrayConfig.HTTP_PORT))
        }
        b.setConfigureIntent(
            PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        return b.establish()
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val uid = Process.myUid()
            val baseRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
            val baseTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            var lastRx = baseRx
            var lastTx = baseTx
            var tick = 0
            while (isActive) {
                delay(1000)
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
                val dRx = (rx - lastRx).coerceAtLeast(0)
                val dTx = (tx - lastTx).coerceAtLeast(0)
                lastRx = rx; lastTx = tx
                VpnState.update { it.copy(rxBytes = rx - baseRx, txBytes = tx - baseTx, rxSpeed = dRx, txSpeed = dTx) }
                VpnState.pushSample(dRx, dTx)
                tick++
                if (tick % 3 == 0) refreshNotification()
                if (tick % 30 == 0) {
                    val d = Net.realDelay(5000)
                    if (d > 0) { VpnState.update { it.copy(ping = d) }; Repo.addPingSample(d) }
                }
            }
        }
    }

    private fun refreshNotification() {
        val st = VpnState.status.value
        if (st.state != ConnState.CONNECTED) return
        val p = Repo.profile(st.profileId) ?: current ?: return
        val country = Countries.get(p.countryCode).name
        val text = if (Repo.settings.value.notifications) {
            "$country • ${st.ping} ms   ↓ ${formatSpeed(st.rxSpeed)}  ↑ ${formatSpeed(st.txSpeed)}"
        } else "$country • ${st.ping} ms"
        Notifications.notify(this, Notifications.build(this, "Connected", text, st.since))
    }

    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var lost = false
            override fun onLost(network: Network) {
                lost = true
                Repo.log(LogLevel.WARN, "Network lost")
            }
            override fun onAvailable(network: Network) {
                if (!lost) return
                lost = false
                val p = current ?: return
                if (!Repo.settings.value.autoReconnect) return
                Repo.log(LogLevel.INFO, "Network changed · reconnecting")
                connectJob?.cancel()
                connectJob = scope.launch { delay(1200); connect(p) }
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb); netCallback = cb }
    }

    private fun teardown(error: String?) {
        connectJob?.cancel()
        monitorJob?.cancel()
        val st = VpnState.status.value
        if (st.state == ConnState.CONNECTED && st.since > 0) {
            Repo.recordSession(System.currentTimeMillis() - st.since, st.rxBytes, st.txBytes)
        }
        CoreBridge.stop()
        runCatching { tun?.close() }
        tun = null
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        if (error != null) Repo.log(LogLevel.ERROR, "Connection failed: $error")
        else if (st.state != ConnState.DISCONNECTED) Repo.log(LogLevel.INFO, "Disconnected")
        VpnState.status.value = if (error != null) VpnStatus(state = ConnState.ERROR, profileId = st.profileId, error = error) else VpnStatus()
        VpnState.clearSamples()
        current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun friendlyError(e: Throwable): String {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return when (root) {
            is ClassNotFoundException, is NoClassDefFoundError ->
                "The Xray engine is not bundled in this build. Rebuild with libv2ray.aar in app/libs."
            is java.net.UnknownHostException -> "The server address could not be resolved."
            is java.net.SocketTimeoutException -> "Connection timed out."
            else -> root.message?.take(160) ?: "Unable to connect to this configuration."
        }
    }

    override fun onRevoke() {
        Repo.log(LogLevel.WARN, "VPN permission revoked by the system")
        teardown(null)
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        connectJob?.cancel()
        CoreBridge.stop()
        runCatching { tun?.close() }
        tun = null
        if (VpnState.status.value.state != ConnState.ERROR) VpnState.status.value = VpnStatus()
        scope.cancel()
        super.onDestroy()
    }
}
