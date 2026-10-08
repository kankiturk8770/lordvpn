package com.lordv2.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Single source of truth for configs, subscriptions, settings, logs and statistics. */
object Repo {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Mutex()

    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val settings = MutableStateFlow(AppSettings())
    val selectedId = MutableStateFlow<String?>(null)
    val totals = MutableStateFlow(Totals())
    val updatingSubs = MutableStateFlow<Set<String>>(emptySet())

    private val profilesFile get() = File(app.filesDir, "profiles.enc")
    private val subsFile get() = File(app.filesDir, "subscriptions.enc")

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("lordv2", Context.MODE_PRIVATE)
        settings.value = AppSettings.fromJson(prefs.getString("settings", null))
        selectedId.value = prefs.getString("selected", null)
        totals.value = Totals.fromJson(prefs.getString("totals", null))
        profiles.value = SecureStore.read(profilesFile)?.let { txt -> parseList(txt) { Profile.fromJson(it) } } ?: emptyList()
        subscriptions.value = SecureStore.read(subsFile)?.let { txt -> parseList(txt) { Subscription.fromJson(it) } } ?: emptyList()
    }

    private fun <T> parseList(text: String, f: (JSONObject) -> T): List<T> = try {
        val a = JSONArray(text)
        (0 until a.length()).map { f(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun persistProfiles() {
        val snapshot = profiles.value
        scope.launch {
            saveLock.withLock {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson()) }
                SecureStore.write(profilesFile, arr.toString())
            }
        }
    }

    private fun persistSubs() {
        val snapshot = subscriptions.value
        scope.launch {
            saveLock.withLock {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson()) }
                SecureStore.write(subsFile, arr.toString())
            }
        }
    }

    // ------------------------------------------------------------ settings

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        settings.update(f)
        prefs.edit().putString("settings", settings.value.toJson().toString()).apply()
    }

    fun resetSettings() = updateSettings { AppSettings(onboarded = true) }

    // ------------------------------------------------------------ profiles

    fun profile(id: String?): Profile? = if (id == null) null else profiles.value.firstOrNull { it.id == id }

    fun select(id: String?) {
        selectedId.value = id
        prefs.edit().putString("selected", id).apply()
    }

    /** Configs that should be visible (configs of disabled subscriptions are hidden). */
    fun visible(list: List<Profile>, subs: List<Subscription>): List<Profile> {
        val disabled = subs.filter { !it.enabled }.map { it.id }.toSet()
        return if (disabled.isEmpty()) list else list.filter { it.subscriptionId == null || it.subscriptionId !in disabled }
    }

    fun connectTargetId(): String? {
        val vis = visible(profiles.value, subscriptions.value)
        return selectedId.value?.takeIf { id -> vis.any { it.id == id } }
            ?: settings.value.defaultConfigId?.takeIf { id -> vis.any { it.id == id } }
            ?: vis.filter { it.ping > 0 }.minByOrNull { it.ping }?.id
            ?: vis.firstOrNull()?.id
    }

    fun addProfiles(list: List<Profile>): Int {
        if (list.isEmpty()) return 0
        val existing = profiles.value.map { it.identityKey() }.toHashSet()
        val fresh = list.filter { existing.add(it.identityKey()) }
        if (fresh.isEmpty()) return 0
        profiles.update { it + fresh }
        if (profile(selectedId.value) == null) select(fresh.first().id)
        persistProfiles()
        return fresh.size
    }

    fun upsert(p: Profile) {
        profiles.update { l -> if (l.any { it.id == p.id }) l.map { if (it.id == p.id) p else it } else l + p }
        if (profile(selectedId.value) == null) select(p.id)
        persistProfiles()
    }

    fun delete(id: String) {
        profiles.update { l -> l.filterNot { it.id == id } }
        if (selectedId.value == id) select(profiles.value.firstOrNull()?.id)
        if (settings.value.defaultConfigId == id) updateSettings { it.copy(defaultConfigId = null) }
        persistProfiles()
    }

    fun duplicate(id: String) {
        val p = profile(id) ?: return
        upsert(
            p.copy(
                id = UUID.randomUUID().toString(), name = p.name + " (copy)", subscriptionId = null,
                createdAt = System.currentTimeMillis(), lastUsed = 0L,
                // Change identity slightly so it is not treated as a duplicate on import.
                params = p.params + ("lordCopy" to System.currentTimeMillis().toString())
            )
        )
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name.trim().ifBlank { it.name }) }
    fun setPing(id: String, ms: Int) = edit(id) { it.copy(ping = ms) }
    fun markUsed(id: String) = edit(id) { it.copy(lastUsed = System.currentTimeMillis()) }

    private fun edit(id: String, f: (Profile) -> Profile) {
        profiles.update { l -> l.map { if (it.id == id) f(it) else it } }
        persistProfiles()
    }

    // ------------------------------------------------------------ subscriptions

    fun addSubscription(name: String, url: String): Subscription {
        val s = Subscription(name = name.trim().ifBlank { "Subscription" }, url = url.trim())
        subscriptions.update { it + s }
        persistSubs()
        log(LogLevel.INFO, "Subscription added: ${s.name}")
        return s
    }

    fun updateSubscription(s: Subscription) {
        subscriptions.update { l -> l.map { if (it.id == s.id) s else it } }
        persistSubs()
    }

    fun deleteSubscription(id: String) {
        val name = subscriptions.value.firstOrNull { it.id == id }?.name
        subscriptions.update { l -> l.filterNot { it.id == id } }
        profiles.update { l -> l.filterNot { it.subscriptionId == id } }
        if (profile(selectedId.value) == null) select(profiles.value.firstOrNull()?.id)
        persistSubs(); persistProfiles()
        log(LogLevel.INFO, "Subscription deleted: ${name ?: ""}")
    }

    suspend fun refreshSubscription(id: String): Result<Int> {
        val sub = subscriptions.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalStateException("Subscription not found"))
        updatingSubs.update { it + id }
        return try {
            val text = withContext(Dispatchers.IO) { Net.fetchText(sub.url, settings.value.timeoutSec * 1000 + 5000) }
            val parsed = LinkParser.parseMany(text, sub.id)
            if (parsed.profiles.isEmpty()) throw IllegalStateException("No valid configurations were found in this subscription.")
            val old = profiles.value.filter { it.subscriptionId == id }.associateBy { it.identityKey() }
            val merged = parsed.profiles.map { np ->
                old[np.identityKey()]?.let { o -> np.copy(id = o.id, ping = o.ping, lastUsed = o.lastUsed) } ?: np
            }
            profiles.update { l -> l.filterNot { it.subscriptionId == id } + merged }
            if (profile(selectedId.value) == null) select(merged.first().id)
            persistProfiles()
            val latest = subscriptions.value.firstOrNull { it.id == id } ?: sub
            updateSubscription(latest.copy(lastUpdated = System.currentTimeMillis(), count = merged.size, lastError = null))
            log(LogLevel.SUCCESS, "Subscription updated: ${sub.name} · ${merged.size} configs")
            Result.success(merged.size)
        } catch (e: Exception) {
            val msg = friendlyNetError(e)
            val latest = subscriptions.value.firstOrNull { it.id == id } ?: sub
            updateSubscription(latest.copy(lastError = msg))
            log(LogLevel.ERROR, "Subscription update failed: ${sub.name} · $msg")
            Result.failure(IllegalStateException(msg))
        } finally {
            updatingSubs.update { it - id }
        }
    }

    fun startAutoUpdates() {
        scope.launch {
            delay(4000)
            while (isActive) {
                if (settings.value.autoUpdateSubs) {
                    val now = System.currentTimeMillis()
                    subscriptions.value
                        .filter { it.enabled && it.autoUpdate && now - it.lastUpdated > it.intervalHours * 3_600_000L }
                        .forEach { refreshSubscription(it.id) }
                }
                delay(15 * 60_000L)
            }
        }
    }

    fun friendlyNetError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Couldn't reach the server. Check the URL and your internet connection."
        is java.net.SocketTimeoutException -> "The server took too long to respond."
        is javax.net.ssl.SSLException -> "Secure connection to the server failed."
        is java.net.MalformedURLException -> "The URL is not valid."
        else -> e.message?.take(140) ?: "Something went wrong."
    }

    // ------------------------------------------------------------ logs & stats

    fun log(level: LogLevel, msg: String) {
        if (level == LogLevel.DEBUG && !settings.value.debugMode) return
        val e = LogEntry(System.currentTimeMillis(), level, Sanitizer.clean(msg))
        logs.update { (listOf(e) + it).take(500) }
    }

    fun clearLogs() { logs.value = emptyList() }

    fun recordConnection() = saveTotals { it.copy(connections = it.connections + 1) }
    fun addPingSample(ms: Int) { if (ms > 0) saveTotals { it.copy(pingSum = it.pingSum + ms, pingCount = it.pingCount + 1) } }
    fun recordSession(durationMs: Long, rx: Long, tx: Long) =
        saveTotals { it.copy(seconds = it.seconds + durationMs / 1000, rx = it.rx + rx, tx = it.tx + tx) }
    fun resetTotals() = saveTotals { Totals() }

    private fun saveTotals(f: (Totals) -> Totals) {
        totals.update(f)
        prefs.edit().putString("totals", totals.value.toJson().toString()).apply()
    }

    // ------------------------------------------------------------ backup

    fun exportBackup(): String {
        val o = JSONObject()
        o.put("app", "LordV2"); o.put("version", 1)
        o.put("profiles", JSONArray().also { a -> profiles.value.forEach { a.put(it.toJson()) } })
        o.put("subscriptions", JSONArray().also { a -> subscriptions.value.forEach { a.put(it.toJson()) } })
        return o.toString(2)
    }

    fun isBackup(text: String): Boolean = text.trimStart().startsWith("{") && text.contains("\"LordV2\"")

    /** Returns the number of imported configs, or -1 if the backup is invalid. */
    fun importBackup(text: String): Int = try {
        val o = JSONObject(text)
        val subs = parseList(o.optJSONArray("subscriptions")?.toString() ?: "[]") { Subscription.fromJson(it) }
        val known = subscriptions.value.map { it.id }.toSet()
        val newSubs = subs.filter { it.id !in known }
        if (newSubs.isNotEmpty()) { subscriptions.update { it + newSubs }; persistSubs() }
        val ps = parseList(o.optJSONArray("profiles")?.toString() ?: "[]") { Profile.fromJson(it) }
        addProfiles(ps)
    } catch (e: Exception) { -1 }
}
