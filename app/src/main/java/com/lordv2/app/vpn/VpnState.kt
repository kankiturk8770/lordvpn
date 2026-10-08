package com.lordv2.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class VpnStatus(
    val state: ConnState = ConnState.DISCONNECTED,
    val profileId: String? = null,
    val since: Long = 0L,
    val ip: String? = null,
    val ping: Int = -1,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxSpeed: Long = 0L,
    val txSpeed: Long = 0L,
    val error: String? = null,
    /** Kill switch is holding the tunnel open and blocking traffic. */
    val blocking: Boolean = false,
)

data class SpeedSample(val rx: Long, val tx: Long)

object VpnState {
    val status = MutableStateFlow(VpnStatus())
    val samples = MutableStateFlow<List<SpeedSample>>(emptyList())

    fun update(f: (VpnStatus) -> VpnStatus) = status.update(f)

    fun pushSample(rx: Long, tx: Long) = samples.update { (it + SpeedSample(rx, tx)).takeLast(60) }

    fun clearSamples() { samples.value = emptyList() }

    fun dismissError() = status.update {
        when {
            it.blocking -> it.copy(error = null)
            it.state == ConnState.ERROR -> it.copy(state = ConnState.DISCONNECTED, error = null)
            else -> it
        }
    }
}
