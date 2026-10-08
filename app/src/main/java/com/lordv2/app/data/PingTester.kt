package com.lordv2.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object PingTester {
    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing

    suspend fun test(p: Profile): Int {
        _testing.update { it + p.id }
        val timeout = (Repo.settings.value.timeoutSec * 1000 / 2).coerceIn(1500, 8000)
        val ms = Net.tcping(p.address, p.port, timeout)
        Repo.setPing(p.id, ms)
        _testing.update { it - p.id }
        return ms
    }

    suspend fun testAll(list: List<Profile>) {
        if (list.isEmpty()) return
        Repo.log(LogLevel.INFO, "Testing ${list.size} configuration(s)…")
        val sem = Semaphore(8)
        coroutineScope { list.map { p -> async { sem.withPermit { test(p) } } }.awaitAll() }
        val ok = Repo.profiles.value.count { p -> list.any { it.id == p.id } && p.ping > 0 }
        Repo.log(LogLevel.INFO, "Ping test finished · $ok/${list.size} reachable")
    }

    fun quality(ms: Int): String = when {
        ms == Profile.PING_UNTESTED -> "Untested"
        ms < 0 -> "Timeout"
        ms < 50 -> "Excellent"
        ms < 100 -> "Good"
        ms < 200 -> "Average"
        else -> "Poor"
    }
}
