package com.lordv2.app.data

import android.util.Base64

object Codec {
    fun b64decode(input: String): String? = try {
        var t = input.trim().replace("\n", "").replace("\r", "").replace(" ", "")
            .replace('-', '+').replace('_', '/')
        t = t.trimEnd('=')
        val pad = (4 - t.length % 4) % 4
        t += "=".repeat(pad)
        String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) { null }

    fun b64encode(s: String, urlSafe: Boolean = false): String =
        Base64.encodeToString(
            s.toByteArray(Charsets.UTF_8),
            if (urlSafe) Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING else Base64.NO_WRAP
        )
}

/** Removes secrets from anything that is about to be written to the visible logs. */
object Sanitizer {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val link = Regex("(vmess|vless|trojan|ss|socks)://\\S+", RegexOption.IGNORE_CASE)
    private val longToken = Regex("[A-Za-z0-9+/=_-]{32,}")

    fun clean(s: String): String = s.replace(link, "\$1://•••").replace(uuid, "••••••••").replace(longToken, "•••")

    fun mask(secret: String): String =
        if (secret.length <= 6) "••••••" else secret.take(3) + "••••••••" + secret.takeLast(2)
}
