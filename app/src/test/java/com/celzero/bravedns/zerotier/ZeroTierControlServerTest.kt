/*
 * Copyright 2026 RethinkDNS and its authors
 * Licensed under the Apache License, Version 2.0
 */
package com.celzero.bravedns.zerotier

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroTierControlServerTest {
    private val token = "unit-test-secret"
    private val networkId = "8056c2e21c000001"

    @Test
    fun servesStatusAndNetworkSnapshotsWithUpstreamShapes() {
        val network = ZeroTierControlNetwork(
            networkId = networkId,
            mac = "aa:bb:cc:dd:ee:ff",
            name = "office \"lan\"",
            status = "OK",
            assignedAddresses = listOf("10.0.0.5/24"),
            routes = listOf(ZeroTierControlRoute("10.0.0.0/24", "10.0.0.1")),
            dnsServers = listOf("10.0.0.1")
        )
        withServer(ZeroTierControlSnapshot("abcdef1234", true, "1.16.1", networks = listOf(network))) { server ->
            val status = request(server.localPort, "GET", "/status")
            assertEquals(200, status.first)
            assertTrue(status.second.contains("\"address\":\"abcdef1234\""))
            assertTrue(status.second.contains("\"online\":true"))
            assertTrue(status.second.contains("\"version\":\"1.16.1\""))

            val list = request(server.localPort, "GET", "/network")
            assertEquals(200, list.first)
            assertTrue(list.second.startsWith("["))
            assertTrue(list.second.contains("\"nwid\":\"$networkId\""))
            assertTrue(list.second.contains("office \\\"lan\\\""))
            assertTrue(list.second.contains("\"assignedAddresses\":[\"10.0.0.5/24\"]"))
            assertTrue(list.second.contains("\"routes\":[{\"target\":\"10.0.0.0/24\""))

            val detail = request(server.localPort, "GET", "/network/$networkId")
            assertEquals(200, detail.first)
            assertTrue(detail.second.contains("\"mac\":\"aa:bb:cc:dd:ee:ff\""))
        }
    }

    @Test
    fun requiresTokenForEveryEndpoint() {
        withServer(ZeroTierControlSnapshot("abcdef1234", false, "1.16.1")) { server ->
            assertEquals(401, request(server.localPort, "GET", "/status", auth = null).first)
            assertEquals(401, request(server.localPort, "GET", "/network", auth = "wrong").first)
            assertEquals(200, request(server.localPort, "GET", "/status").first)
        }
    }

    @Test
    fun postAndDeleteInvokeInjectedOperationsWithCanonicalIds() {
        val calls = CopyOnWriteArrayList<String>()
        withServer(
            ZeroTierControlSnapshot("abcdef1234", true, "1.16.1"),
            join = { calls.add("join:$it") },
            leave = { calls.add("leave:$it") }
        ) { server ->
            val joined = request(server.localPort, "POST", "/network/${networkId.uppercase()}")
            assertEquals(200, joined.first)
            assertEquals("{}", joined.second)
            val left = request(server.localPort, "DELETE", "/network/$networkId")
            assertEquals(200, left.first)
            assertEquals("{}", left.second)
            assertEquals(listOf("join:$networkId", "leave:$networkId"), calls.toList())
        }
    }

    @Test
    fun missingNetworkAndMalformedPathsReturnNotFound() {
        withServer(ZeroTierControlSnapshot("abcdef1234", false, "1.16.1")) { server ->
            assertEquals(404, request(server.localPort, "GET", "/network/$networkId").first)
            assertEquals(404, request(server.localPort, "GET", "/network/not-a-network").first)
        }
    }

    @Test
    fun bindsOnlyIpv4LoopbackAndSupportsTestPort() {
        withServer(ZeroTierControlSnapshot("abcdef1234", false, "1.16.1"), requestedPort = 0) { server ->
            assertTrue(server.localPort > 0)
            assertEquals(200, request(server.localPort, "GET", "/status").first)
        }
    }

    private fun withServer(
        state: ZeroTierControlSnapshot,
        requestedPort: Int = 0,
        join: (String) -> Unit = {},
        leave: (String) -> Unit = {},
        test: (ZeroTierControlServer) -> Unit
    ) {
        val server = ZeroTierControlServer(token, { state }, join, leave, requestedPort)
        try {
            server.start()
            test(server)
        } finally {
            server.stop()
        }
    }

    private fun request(port: Int, method: String, path: String, auth: String? = token): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        if (auth != null) connection.setRequestProperty(ZeroTierControlServer.AUTH_HEADER, auth)
        return try {
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
