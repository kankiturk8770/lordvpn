package com.lordv2.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

object Net {
    private const val UA = "LordV2/1.0 (v2rayNG compatible)"

    fun localProxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", XrayConfig.HTTP_PORT))

    fun fetchText(url: String, timeoutMs: Int, proxy: Proxy = Proxy.NO_PROXY): String {
        val c = URL(url).openConnection(proxy) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Server responded with HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** TCP handshake time to the server (does not need the VPN core). */
    suspend fun tcping(host: String, port: Int, timeoutMs: Int = 3000): Int = withContext(Dispatchers.IO) {
        try {
            val addr = InetAddress.getByName(host)
            Socket().use { s ->
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(addr, port), timeoutMs)
                ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1)
            }
        } catch (e: Exception) { Profile.PING_FAILED }
    }

    /** Real latency through the running core (HTTP 204 probe over the local proxy). Blocking. */
    fun realDelay(timeoutMs: Int): Int = try {
        val t0 = System.nanoTime()
        val c = URL("https://www.gstatic.com/generate_204").openConnection(localProxy()) as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", UA)
        val code = c.responseCode
        c.disconnect()
        if (code == 204 || code == 200) ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1) else Profile.PING_FAILED
    } catch (e: Exception) { Profile.PING_FAILED }

    fun publicIp(timeoutMs: Int): String? = try {
        fetchText("https://api.ipify.org", timeoutMs, localProxy()).trim().takeIf { it.length in 3..45 }
    } catch (e: Exception) { null }
}
