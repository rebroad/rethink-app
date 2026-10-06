/*
 * Copyright 2026 RethinkDNS and its authors
 * Licensed under the Apache License, Version 2.0
 */
package com.celzero.bravedns.zerotier

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** One route supplied by the ZeroTier network controller. */
data class ZeroTierControlRoute(
    val target: String,
    val via: String? = null,
    val flags: Int = 0,
    val metric: Int = 0
)

/** Snapshot fields needed by upstream zerotier-cli's status and network endpoints. */
data class ZeroTierControlNetwork(
    val networkId: String,
    val mac: String = "",
    val name: String = "",
    val status: String = "REQUESTING_CONFIGURATION",
    val type: String = "PRIVATE",
    val mtu: Int = 2800,
    val dhcp: Boolean = false,
    val bridge: Boolean = false,
    val broadcastEnabled: Boolean = true,
    val portError: Int = 0,
    val netconfRevision: Long = 0,
    val portDeviceName: String = "",
    val allowManaged: Boolean = true,
    val allowGlobal: Boolean = false,
    val allowDefault: Boolean = false,
    val allowDNS: Boolean = true,
    val assignedAddresses: List<String> = emptyList(),
    val routes: List<ZeroTierControlRoute> = emptyList(),
    val dnsDomain: String = "",
    val dnsServers: List<String> = emptyList()
)

data class ZeroTierControlSnapshot(
    val address: String,
    val online: Boolean,
    val version: String,
    val publicIdentity: String = "",
    val clock: Long = System.currentTimeMillis(),
    val primaryPort: Int = 9993,
    val networks: List<ZeroTierControlNetwork> = emptyList()
)

/**
 * Small loopback-only HTTP/JSON control API understood by upstream zerotier-cli.
 * The owner supplies current state and serialized join/leave operations; this class owns no node
 * lifecycle and can therefore be tested independently with fakes.
 */
class ZeroTierControlServer(
    token: String,
    private val snapshot: () -> ZeroTierControlSnapshot,
    private val join: (String) -> Unit,
    private val leave: (String) -> Unit,
    port: Int = DEFAULT_PORT
) : AutoCloseable {
    private val expectedToken = token.toByteArray(StandardCharsets.UTF_8)
    private val requestedPort = port
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var worker: Thread? = null

    init {
        require(token.isNotEmpty()) { "ZeroTier control token must not be empty" }
        require(port in 0..65535) { "Port must be between 0 and 65535" }
    }

    /** Actual port after start; useful for port 0 test instances. */
    val localPort: Int
        get() = serverSocket?.localPort ?: requestedPort

    @Synchronized
    fun start() {
        if (running.get()) return
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), requestedPort), 16)
        } catch (error: Exception) {
            try { socket.close() } catch (_: IOException) { }
            throw error
        }
        serverSocket = socket
        running.set(true)
        worker = Thread({ acceptLoop(socket) }, "zerotier-control-http").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: IOException) { }
        serverSocket = null
        worker?.interrupt()
        worker = null
    }

    override fun close() = stop()

    private fun acceptLoop(listener: ServerSocket) {
        while (running.get() && serverSocket === listener) {
            try {
                val client = listener.accept()
                if (!client.inetAddress.isLoopbackAddress) {
                    client.close()
                    continue
                }
                // Requests are local and low-volume; handle serially to bound thread/resource use.
                client.use { handle(it) }
            } catch (_: IOException) {
                if (running.get() && serverSocket === listener) continue
                return
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = IO_TIMEOUT_MILLIS
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input, MAX_LINE_BYTES) ?: return
            val request = requestLine.split(' ')
            if (request.size != 3 || !request[2].startsWith("HTTP/1.")) {
                respond(socket, 400, "Bad Request", "{\"error\":\"bad request\"}")
                return
            }
            val headers = LinkedHashMap<String, String>()
            var headerBytes = 0
            while (true) {
                val line = readLine(input, MAX_LINE_BYTES) ?: throw IOException("Incomplete headers")
                headerBytes += line.length
                if (headerBytes > MAX_HEADER_BYTES) throw RequestTooLarge()
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) {
                    respond(socket, 400, "Bad Request", "{\"error\":\"bad header\"}")
                    return
                }
                headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            }
            val suppliedToken = headers[AUTH_HEADER.lowercase(Locale.ROOT)]
                ?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
            if (!MessageDigest.isEqual(expectedToken, suppliedToken)) {
                respond(socket, 401, "Unauthorized", "{\"error\":\"unauthorized\"}")
                return
            }
            if (headers.containsKey("transfer-encoding")) {
                respond(socket, 400, "Bad Request", "{\"error\":\"transfer encoding unsupported\"}")
                return
            }
            val contentLength = headers["content-length"]
            val bodyLength = if (contentLength == null) 0 else contentLength.toIntOrNull()
                ?: throw IOException("Invalid content length")
            if (bodyLength < 0 || bodyLength > MAX_BODY_BYTES) throw RequestTooLarge()
            drain(input, bodyLength)
            dispatch(request[0].uppercase(Locale.ROOT), request[1].substringBefore('?'), socket)
        } catch (_: RequestTooLarge) {
            respond(socket, 413, "Payload Too Large", "{\"error\":\"request too large\"}")
        } catch (_: IOException) {
            try { respond(socket, 400, "Bad Request", "{\"error\":\"bad request\"}") } catch (_: Exception) { }
        } catch (_: Exception) {
            try { respond(socket, 500, "Internal Server Error", "{\"error\":\"internal server error\"}") } catch (_: Exception) { }
        }
    }

    private fun dispatch(method: String, path: String, socket: Socket) {
        if (path == "/status") {
            if (method != "GET") return methodNotAllowed(socket, "GET")
            val state = snapshot()
            val versionParts = state.version.split('.').map { it.toIntOrNull() ?: 0 }
            val address = state.address.lowercase(Locale.ROOT).filter { it in '0'..'9' || it in 'a'..'f' }.takeLast(10).padStart(10, '0')
            val settings = "{\"allowTcpFallbackRelay\":true,\"forceTcpRelay\":false,\"primaryPort\":${state.primaryPort.coerceIn(0, 65535)},\"secondaryPort\":0,\"tertiaryPort\":0}"
            val json = "{" +
                "\"address\":${quote(address)}," +
                "\"publicIdentity\":${quote(state.publicIdentity)}," +
                "\"online\":${state.online}," +
                "\"tcpFallbackActive\":false," +
                "\"versionMajor\":${versionParts.getOrElse(0) { 0 }}," +
                "\"versionMinor\":${versionParts.getOrElse(1) { 0 }}," +
                "\"versionRev\":${versionParts.getOrElse(2) { 0 }}," +
                "\"versionBuild\":0," +
                "\"version\":${quote(state.version)}," +
                "\"clock\":${state.clock}," +
                "\"config\":{\"settings\":$settings}}"
            respond(socket, 200, "OK", json)
            return
        }

        if (path == "/network") {
            if (method != "GET") return methodNotAllowed(socket, "GET")
            respond(socket, 200, "OK", snapshot().networks.mapNotNull { network ->
                canonicalId(network.networkId)?.let { networkJson(network, it) }
            }.joinToString(prefix = "[", postfix = "]"))
            return
        }

        val match = NETWORK_PATH.matchEntire(path)
        if (match == null) {
            respond(socket, 404, "Not Found", "{}")
            return
        }
        val networkId = match.groupValues[1].lowercase(Locale.ROOT)
        when (method) {
            "GET" -> {
                val network = snapshot().networks.firstOrNull { canonicalId(it.networkId) == networkId }
                if (network == null) respond(socket, 404, "Not Found", "{}")
                else respond(socket, 200, "OK", networkJson(network, networkId))
            }
            "POST" -> {
                join(networkId)
                val network = snapshot().networks.firstOrNull { canonicalId(it.networkId) == networkId }
                respond(socket, 200, "OK", network?.let { networkJson(it, networkId) } ?: "{}")
            }
            "DELETE" -> {
                val existed = snapshot().networks.any { canonicalId(it.networkId) == networkId }
                leave(networkId)
                respond(socket, 200, "OK", if (existed) "{\"result\":true}" else "{}")
            }
            else -> methodNotAllowed(socket, "GET, POST, DELETE")
        }
    }

    private fun networkJson(network: ZeroTierControlNetwork, id: String): String {
        val status = network.status.uppercase(Locale.ROOT).takeIf { STATUS.matches(it) } ?: "REQUESTING_CONFIGURATION"
        val type = network.type.uppercase(Locale.ROOT).takeIf { TYPE.matches(it) } ?: "PRIVATE"
        val addresses = network.assignedAddresses.joinToString(prefix = "[", postfix = "]", transform = ::quote)
        val routes = network.routes.joinToString(prefix = "[", postfix = "]") {
            "{\"target\":${quote(it.target)},\"via\":${it.via?.let(::quote) ?: "null"},\"flags\":${it.flags},\"metric\":${it.metric}}"
        }
        val dnsServers = network.dnsServers.joinToString(prefix = "[", postfix = "]", transform = ::quote)
        return "{" +
            "\"id\":${quote(id)},\"nwid\":${quote(id)},\"mac\":${quote(network.mac)}," +
            "\"name\":${quote(network.name)},\"status\":${quote(status)},\"type\":${quote(type)}," +
            "\"mtu\":${network.mtu.coerceAtLeast(0)},\"dhcp\":${network.dhcp},\"bridge\":${network.bridge}," +
            "\"broadcastEnabled\":${network.broadcastEnabled},\"portError\":${network.portError}," +
            "\"netconfRevision\":${network.netconfRevision.coerceAtLeast(0)},\"portDeviceName\":${quote(network.portDeviceName)}," +
            "\"allowManaged\":${network.allowManaged},\"allowGlobal\":${network.allowGlobal}," +
            "\"allowDefault\":${network.allowDefault},\"allowDNS\":${network.allowDNS}," +
            "\"assignedAddresses\":$addresses,\"routes\":$routes," +
            "\"multicastSubscriptions\":[],\"dns\":{\"domain\":${quote(network.dnsDomain)},\"servers\":$dnsServers}}"
    }

    private fun methodNotAllowed(socket: Socket, allowed: String) =
        respond(socket, 405, "Method Not Allowed", "{\"error\":\"method not allowed\"}", "Allow: $allowed\r\n")

    private fun respond(socket: Socket, code: Int, reason: String, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" + extraHeaders + "\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream, maxBytes: Int): String? {
        val out = ByteArrayOutputStream()
        var previous = -1
        while (out.size() <= maxBytes) {
            val next = input.read()
            if (next < 0) return if (out.size() == 0) null else throw IOException("Incomplete line")
            if (previous == '\r'.code && next == '\n'.code) {
                val bytes = out.toByteArray()
                return String(bytes, 0, (bytes.size - 1).coerceAtLeast(0), StandardCharsets.US_ASCII)
            }
            out.write(next)
            previous = next
        }
        throw RequestTooLarge()
    }

    private fun drain(input: BufferedInputStream, length: Int) {
        var remaining = length
        val buffer = ByteArray(1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) throw IOException("Incomplete request body")
            remaining -= count
        }
    }

    private fun canonicalId(value: String): String? =
        value.takeIf { NETWORK_ID.matches(it) }?.lowercase(Locale.ROOT)

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private class RequestTooLarge : IOException()

    companion object {
        const val DEFAULT_PORT = 9993
        const val AUTH_HEADER = "X-ZT1-Auth"
        private const val IO_TIMEOUT_MILLIS = 3000
        private const val MAX_LINE_BYTES = 8192
        private const val MAX_HEADER_BYTES = 32768
        private const val MAX_BODY_BYTES = 16384
        private val NETWORK_ID = Regex("[0-9a-fA-F]{16}")
        private val NETWORK_PATH = Regex("/network/([0-9a-fA-F]{16})")
        private val STATUS = Regex("[A-Z_]{1,48}")
        private val TYPE = Regex("[A-Z_]{1,24}")
    }
}
