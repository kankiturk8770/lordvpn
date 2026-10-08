package com.lordv2.app

import android.app.Application
import com.lordv2.app.data.Repo
import com.lordv2.app.vpn.AutoConnectWatcher
import com.lordv2.app.vpn.Notifications

class LordApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifications.createChannel(this)
        AutoConnectWatcher.register(this)
        Repo.startAutoUpdates()
    }
}
