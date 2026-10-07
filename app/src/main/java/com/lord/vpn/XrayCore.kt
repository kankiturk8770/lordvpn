package com.lord.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.Proxy

/**
 * Wrapper around libv2ray.aar (AndroidLibXrayLite, the Xray core used by v2rayNG).
 * Reflection is used so the project still compiles if the aar is missing, and so it
 * works with both the new (CoreController) and old (V2RayPoint) libv2ray APIs.
 */
object XrayCore {
    private const val TAG = "XrayCore"
    private var controller: Any? = null
    private var envReady = false
    var lastError: String? = null

    fun isAvailable(): Boolean = try { Class.forName("libv2ray.Libv2ray"); true } catch (_: Throwable) { false }

    private val lib by lazy { Class.forName("libv2ray.Libv2ray") }

    /** Copies geoip/geosite from assets and initialises the core environment. */
    @Synchronized
    fun init(ctx: Context) {
        if (envReady || !isAvailable()) return
        val dir = ctx.filesDir
        for (f in listOf("geoip.dat", "geosite.dat")) {
            val out = File(dir, f)
            if (!out.exists()) try {
                ctx.assets.open(f).use { i -> out.outputStream().use { i.copyTo(it) } }
            } catch (_: Exception) { Log.w(TAG, "$f not bundled") }
        }
        val path = dir.absolutePath
        val m = lib.methods.firstOrNull { it.name == "initCoreEnv" } ?: lib.methods.firstOrNull { it.name == "initV2Env" }
        m?.invoke(null, path, "")
        envReady = true
    }

    fun hasGeo(ctx: Context) = File(ctx.filesDir, "geoip.dat").exists() && File(ctx.filesDir, "geosite.dat").exists()

    fun version(): String = try { lib.getMethod("checkVersionX").invoke(null) as String } catch (_: Throwable) { "Xray" }

    @Synchronized
    fun start(ctx: Context, configJson: String) {
        init(ctx)
        stop()
        val cbClass = listOf("libv2ray.CoreCallbackHandler", "libv2ray.V2RayVPNServiceSupportsSet")
            .firstNotNullOfOrNull { try { Class.forName(it) } catch (_: Throwable) { null } }
            ?: throw IllegalStateException("libv2ray callback interface not found")

        val callback = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, method, args ->
            when (method.name) {
                "onEmitStatus" -> Log.i(TAG, "status: ${args?.getOrNull(1)}")
                "protect" -> return@newProxyInstance true
                "toString" -> return@newProxyInstance "LordCallback"
                "hashCode" -> return@newProxyInstance 0
                "equals" -> return@newProxyInstance false
            }
            when (method.returnType) {
                java.lang.Long.TYPE -> 0L
                java.lang.Boolean.TYPE -> true
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        }

        val newCtl = lib.methods.firstOrNull { it.name == "newCoreController" }
        if (newCtl != null) {
            // New API: CoreController.startLoop(config) or startLoop(config, tunFd)
            val c = newCtl.invoke(null, callback)!!
            val loop = c.javaClass.methods.filter { it.name == "startLoop" }.minBy { it.parameterCount }
            if (loop.parameterCount == 1) loop.invoke(c, configJson) else loop.invoke(c, configJson, 0)
            controller = c
        } else {
            // Old API: V2RayPoint
            val p = lib.methods.first { it.name == "newV2RayPoint" }.let {
                if (it.parameterCount == 2) it.invoke(null, callback, false) else it.invoke(null, callback)
            }!!
            p.javaClass.getMethod("setConfigureFileContent", String::class.java).invoke(p, configJson)
            p.javaClass.getMethod("setDomainName", String::class.java).invoke(p, "")
            p.javaClass.methods.first { it.name == "runLoop" }.let {
                if (it.parameterCount == 1) it.invoke(p, false) else it.invoke(p)
            }
            controller = p
        }
    }

    @Synchronized
    fun stop() {
        val c = controller ?: return
        try { c.javaClass.getMethod("stopLoop").invoke(c) } catch (e: Throwable) { Log.w(TAG, "stop: $e") }
        controller = null
    }

    /** Bytes since last query (Xray stats counters reset on read). */
    fun queryStats(tag: String, direct: String): Long = try {
        val c = controller ?: return 0
        c.javaClass.getMethod("queryStats", String::class.java, String::class.java).invoke(c, tag, direct) as Long
    } catch (_: Throwable) { 0 }

    /** Real delay test of an outbound config (ms), -1 on failure. */
    fun measureDelay(ctx: Context, configJson: String, url: String = "https://www.gstatic.com/generate_204"): Long = try {
        init(ctx)
        val m = lib.methods.first { it.name == "measureOutboundDelay" }
        (m.invoke(null, configJson, url) as Long).let { if (it <= 0) -1 else it }
    } catch (e: Throwable) {
        lastError = (e.cause ?: e).message; -1
    }
}
