package com.malikfikret.ttlvpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Connecting : VpnState
    data class Connected(val ttl: Int) : VpnState
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
