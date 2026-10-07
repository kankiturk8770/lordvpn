package com.lord.vpn

/**
 * JNI bindings for hev-socks5-tunnel (tun2socks, same one v2rayNG uses).
 * The .so is built by CI with -DPKGNAME=com/lord/vpn, so natives register on this class.
 */
class TProxyService {
    companion object {
        var loaded = false
            private set

        init {
            loaded = try { System.loadLibrary("hev-socks5-tunnel"); true } catch (_: Throwable) { false }
        }

        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic external fun TProxyStopService()
        @JvmStatic external fun TProxyGetStats(): LongArray?
    }
}
