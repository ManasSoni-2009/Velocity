package com.velocity.app.platform

import com.velocity.app.domain.BondingMode
import com.velocity.app.domain.ConnectionState
import com.velocity.app.service.VpnStateHolder
import org.junit.Assert.*
import org.junit.Test

class AndroidPlatformBridgeTest {

    @Test
    fun testVpnStateObservability() {
        val stateFlow = VpnStateHolder.connectionState
        val initial = stateFlow.value
        assertEquals(ConnectionState.DISCONNECTED, initial.state)

        VpnStateHolder.updateState(
            state = ConnectionState.BONDED,
            message = "Bonded via dual link",
            mode = BondingMode.SPEED,
        )

        assertEquals(ConnectionState.BONDED, stateFlow.value.state)
        assertEquals(BondingMode.SPEED, stateFlow.value.activeMode)
    }
}
