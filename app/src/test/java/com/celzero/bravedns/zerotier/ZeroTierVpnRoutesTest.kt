package com.celzero.bravedns.zerotier

import org.junit.Assert.assertEquals
import org.junit.Test

class ZeroTierVpnRoutesTest {
    @Test
    fun `default route is included only when an authorized network advertises it`() {
        val network = ZeroTierNetworkState(
            networkId = "8056c2e21c000001",
            configurationStatus = "OK",
            assignedAddresses = listOf("192.168.192.7/24")
        )

        assertEquals(
            listOf("192.168.192.0/24"),
            zeroTierVpnRoutes(listOf(network)).map { it.cidr }
        )
        assertEquals(
            listOf("192.168.192.0/24", "0.0.0.0/0"),
            zeroTierVpnRoutes(listOf(network.copy(routes = listOf("0.0.0.0/0@100"))))
                .map { it.cidr }
        )
    }

    @Test
    fun `unauthorized network does not contribute routes or assigned prefixes`() {
        val denied = ZeroTierNetworkState(
            networkId = "8056c2e21c000001",
            configurationStatus = "ACCESS DENIED",
            assignedAddresses = listOf("192.168.192.7/24"),
            routes = listOf("0.0.0.0/0@0")
        )

        assertEquals(emptyList<ZeroTierVpnRoute>(), zeroTierVpnRoutes(listOf(denied)))
    }

    @Test
    fun `duplicate targets choose lowest metric then network id`() {
        val routes = zeroTierVpnRoutes(
            listOf(
                ZeroTierNetworkState(
                    networkId = "ffffffffffffffff",
                    configurationStatus = "OK",
                    routes = listOf("10.0.0.0/24@5")
                ),
                ZeroTierNetworkState(
                    networkId = "8056c2e21c000001",
                    configurationStatus = "OK",
                    routes = listOf("10.0.0.0/24@5")
                ),
                ZeroTierNetworkState(
                    networkId = "0000000000000001",
                    configurationStatus = "OK",
                    routes = listOf("10.0.0.0/24@10")
                )
            )
        )

        assertEquals(1, routes.size)
        assertEquals("8056c2e21c000001", routes.single().networkId)
    }
}
