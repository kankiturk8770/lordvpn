package com.lordv2.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import com.lordv2.app.data.Repo

/** Connects automatically when a network becomes available (Settings › Auto Connect). */
object AutoConnectWatcher {
    @Volatile var userDisconnected = false

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val s = Repo.settings.value
                if (!s.autoConnect || userDisconnected) return
                if (VpnState.status.value.state != ConnState.DISCONNECTED) return
                if (VpnService.prepare(app) != null) return
                val id = Repo.connectTargetId() ?: return
                runCatching { LordVpnService.connect(app, id) }
            }
        }
        runCatching { cm.registerNetworkCallback(req, cb) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Repo.settings.value.startOnBoot) return
        if (VpnService.prepare(context) != null) return
        val id = Repo.connectTargetId() ?: return
        runCatching { LordVpnService.connect(context, id) }
    }
}
