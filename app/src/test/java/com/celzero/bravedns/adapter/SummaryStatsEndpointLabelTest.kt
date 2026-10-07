package com.celzero.bravedns.adapter

import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.statsAppLabel
import com.celzero.bravedns.data.unknownEndpointLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryStatsEndpointLabelTest {
    @Test
    fun unknownEndpointLabelIncludesPort() {
        assertEquals(
            "192.168.192.7:8022",
            connection("192.168.192.7", 8022).unknownEndpointLabel()
        )
    }

    @Test
    fun unknownIpv6EndpointLabelBracketsAddress() {
        assertEquals(
            "[fd00::7]:8022",
            connection("fd00::7", 8022).unknownEndpointLabel()
        )
    }

    @Test
    fun endpointLabelDoesNotDependOnAppNamePlaceholder() {
        // The Stats DAO's projection decides whether this row is an endpoint
        // row, so its label should not depend on an app-name placeholder.
        assertEquals(
            "192.168.192.7:8022",
            connection("192.168.192.7", 8022, "Unknown (uid 0)").unknownEndpointLabel()
        )
    }

    @Test
    fun projectedEndpointPrecedesCachedUnknownAppName() {
        assertEquals(
            "192.168.192.7:8022",
            connection("192.168.192.7", 8022).statsAppLabel("Unknown")
        )
    }

    @Test
    fun protocolMarkedUnknownUsesEndpointEvenWithResolvedFallback() {
        assertEquals(
            "192.168.192.7:8022",
            connection("192.168.192.7", 8022, "Unknown").copy(flag = "6")
                .statsAppLabel("Unknown")
        )
    }

    @Test
    fun cachedAppNameRemainsForRowsWithoutProjectedEndpoint() {
        assertEquals(
            "Termux",
            connection("", 0, "Unknown").statsAppLabel("Termux")
        )
    }

    @Test
    fun missingEndpointReturnsNull() {
        assertNull(connection("", 0).unknownEndpointLabel())
    }

    private fun connection(ip: String, port: Int, name: String = "Unknown") = AppConnection(
        uid = -1,
        ipAddress = ip,
        port = port,
        count = 1,
        flag = "6",
        blocked = false,
        appOrDnsName = name
    )
}
