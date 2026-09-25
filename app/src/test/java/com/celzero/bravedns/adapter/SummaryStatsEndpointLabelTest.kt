package com.celzero.bravedns.adapter

import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.unknownEndpointLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryStatsEndpointLabelTest {
    @Test
    fun unknownEndpointLabelIncludesPort() {
        assertEquals(
            "192.168.192.7:8022",
            connection("192.168.192.7", 8022).unknownEndpointLabel("Unknown")
        )
    }

    @Test
    fun unknownIpv6EndpointLabelBracketsAddress() {
        assertEquals(
            "[fd00::7]:8022",
            connection("fd00::7", 8022).unknownEndpointLabel("Unknown")
        )
    }

    @Test
    fun identifiedAppsDoNotGetRenamed() {
        assertNull(connection("192.168.192.7", 8022, "Termux").unknownEndpointLabel("Unknown"))
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
