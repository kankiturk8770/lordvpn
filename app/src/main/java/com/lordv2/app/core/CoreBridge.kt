package com.lordv2.app.core

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Pluggable bridge to the Xray core shipped as `libv2ray.aar` (AndroidLibXrayLite).
 *
 * The core is accessed through reflection so the project always compiles, with or without the AAR,
 * and so it keeps working across the different AndroidLibXrayLite API generations
 * (`newCoreController` / `startLoop` and the older `newV2RayPoint` / `runLoop`).
 * To plug another engine (sing-box, hev-socks5-tunnel…), implement the same three calls.
 */
object CoreBridge {
    private const val TAG = "LordV2Core"
    private var handle: Any? = null

    fun isAvailable(): Boolean = try { Class.forName("libv2ray.Libv2ray"); true } catch (e: Throwable) { false }

    fun version(): String = try {
        val lib = Class.forName("libv2ray.Libv2ray")
        val m = lib.methods.firstOrNull { it.name == "checkVersionX" && it.parameterTypes.isEmpty() }
        m?.invoke(null)?.toString() ?: "Xray core"
    } catch (e: Throwable) { "Not bundled" }

    /** Starts the core. Throws with a readable message on failure. */
    @Synchronized
    fun start(ctx: Context, config: String, tunFd: Int, protect: (Int) -> Boolean) {
        stop()
        val lib = Class.forName("libv2ray.Libv2ray")
        val envPath = ctx.filesDir.absolutePath
        val init = lib.methods.firstOrNull { (it.name == "initCoreEnv" || it.name == "initV2Env") && it.parameterTypes.size == 2 }
        if (init != null) runCatching { init.invoke(null, envPath, "") }

        val newController = lib.methods.firstOrNull { it.name == "newCoreController" && it.parameterTypes.size == 1 }
        if (newController != null) {
            val callback = callbackFor(newController.parameterTypes[0], protect)
            val controller = newController.invoke(null, callback) ?: error("Core controller unavailable")
            val loop2 = controller.javaClass.methods.firstOrNull { it.name == "startLoop" && it.parameterTypes.size == 2 }
            val loop1 = controller.javaClass.methods.firstOrNull { it.name == "startLoop" && it.parameterTypes.size == 1 }
            when {
                loop2 != null -> loop2.invoke(controller, config, intArg(loop2.parameterTypes[1], tunFd))
                loop1 != null -> loop1.invoke(controller, config)
                else -> error("Unsupported Xray core API")
            }
            handle = controller
            return
        }

        val newPoint = lib.methods.firstOrNull { it.name == "newV2RayPoint" } ?: error("Unsupported Xray core API")
        val callback = callbackFor(newPoint.parameterTypes[0], protect)
        val point = if (newPoint.parameterTypes.size == 2) newPoint.invoke(null, callback, false) else newPoint.invoke(null, callback)
        val pt: Any = point ?: error("Core instance unavailable")
        pt.javaClass.methods.firstOrNull { it.name == "setConfigureFileContent" }?.invoke(pt, config)
        val run = pt.javaClass.methods.firstOrNull { it.name == "runLoop" } ?: error("Unsupported Xray core API")
        if (run.parameterTypes.size == 1) run.invoke(pt, false) else run.invoke(pt)
        handle = pt
    }

    @Synchronized
    fun stop() {
        val h = handle ?: return
        handle = null
        try {
            val m = h.javaClass.methods.firstOrNull { (it.name == "stopLoop") && it.parameterTypes.isEmpty() }
            m?.invoke(h)
        } catch (e: Throwable) {
            Log.w(TAG, "stop failed: ${e.javaClass.simpleName}")
        }
    }

    private fun intArg(type: Class<*>, value: Int): Any = when (type) {
        java.lang.Long.TYPE, java.lang.Long::class.java -> value.toLong()
        else -> value
    }

    private fun callbackFor(type: Class<*>, protect: (Int) -> Boolean): Any {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "protect" -> {
                    val fd = (args?.getOrNull(0) as? Number)?.toInt() ?: -1
                    box(method.returnType, protect(fd))
                }
                "onEmitStatus" -> {
                    Log.d(TAG, "core status")
                    box(method.returnType, true)
                }
                "toString" -> "LordV2CoreCallback"
                "hashCode" -> 0
                "equals" -> false
                else -> box(method.returnType, true)
            }
        }
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)
    }

    private fun box(type: Class<*>, ok: Boolean): Any? = when (type) {
        java.lang.Boolean.TYPE -> ok
        java.lang.Long.TYPE -> 0L
        java.lang.Integer.TYPE -> 0
        java.lang.Void.TYPE -> null
        else -> null
    }
}
