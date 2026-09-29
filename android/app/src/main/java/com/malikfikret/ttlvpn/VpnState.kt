package com.malikfikret.ttlvpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Connecting : VpnState
    // Baselines are this app's UID byte counters when the VPN started, or
    // TrafficStats.UNSUPPORTED (-1) if the device doesn't report them.
    // startedAt is SystemClock.elapsedRealtime(), which is immune to wall-clock changes.
    data class Connected(
        val ttl: Int,
        val rxBaseline: Long,
        val txBaseline: Long,
        val startedAt: Long
    ) : VpnState
    data class Error(val message: String) : VpnState
}

// Process-wide, so it outlives activity recreation. If the process dies, the service
// dies with it, and starting fresh at Disconnected is correct.
object VpnStateRepository {
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    // Only TtlVpnService writes the state.
    internal fun update(newState: VpnState) {
        _state.value = newState
    }
}
