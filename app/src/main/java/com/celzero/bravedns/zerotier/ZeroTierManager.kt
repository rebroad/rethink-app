/*
 * Copyright 2026 RethinkDNS and its authors
 * Licensed under the Apache License, Version 2.0
 */
package com.celzero.bravedns.zerotier

import android.content.Context
import android.net.Network
import android.util.Base64
import com.zerotier.sdk.DataStoreGetListener
import com.zerotier.sdk.DataStorePutListener
import com.zerotier.sdk.EventListener
import com.zerotier.sdk.Node
import com.zerotier.sdk.NodeStatus
import com.zerotier.sdk.PacketSender
import com.zerotier.sdk.PathChecker
import com.zerotier.sdk.VirtualNetworkConfig
import com.zerotier.sdk.VirtualNetworkConfigListener
import com.zerotier.sdk.VirtualNetworkConfigOperation
import com.zerotier.sdk.VirtualNetworkFrameListener
import com.zerotier.sdk.ResultCode
import java.io.File
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.DatagramSocket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * I/O boundary for the ZeroTier Java SDK. The eventual Firestack bridge supplies this
 * implementation; keeping it injectable lets lifecycle and status behavior be tested without
 * native libraries or a live network.
 */
interface ZeroTierPacketAdapter {
    fun setVirtualFrameReceiver(receiver: (String, ByteArray) -> Unit) = Unit
    fun attachUnderlay(protect: (DatagramSocket) -> Boolean, network: Network?): String? = null
    fun detachUnderlay() = Unit
    fun attachTunnel(tunnel: ZeroTierTunnelApi?) = Unit
    fun setWirePacketReceiver(receiver: ZeroTierWirePacketReceiver) = Unit
    fun sendWirePacket(localSocket: Long, remote: InetSocketAddress, packet: ByteArray): Int
    fun receiveVirtualFrame(networkId: Long, sourceMac: Long, destinationMac: Long,
                            etherType: Long, vlanId: Long, payload: ByteArray): Int
    fun onNetworkConfiguration(networkId: Long, operation: VirtualNetworkConfigOperation,
                               config: VirtualNetworkConfig?): Int
    fun isPathAllowed(nodeAddress: Long, localSocket: Long, remote: InetSocketAddress): Boolean = true
    fun lookupPath(nodeAddress: Long, family: Int): InetSocketAddress? = null
}

/** Minimal bridge implemented by GoVpnAdapter around the Firestack Tunnel API. */
interface ZeroTierTunnelApi {
    fun configureZeroTier(networkId: String, mac: String, addressesCsv: String, routesCsv: String,
                          frameSink: (String, ByteArray) -> Unit): Boolean
    fun injectZeroTierFrame(networkId: String, frame: ByteArray): Boolean
    fun clearZeroTier(networkId: String): Boolean
}

fun interface ZeroTierWirePacketReceiver {
    fun onWirePacket(localSocket: Long, remote: InetSocketAddress, packet: ByteArray)
}

data class ZeroTierNetworkState(
    val networkId: String,
    val configurationStatus: String = "Waiting for configuration",
    val mac: String = "",
    val name: String = "",
    val type: String = "",
    val mtu: Int = 0,
    val assignedAddresses: List<String> = emptyList(),
    val routes: List<String> = emptyList()
)

data class ZeroTierState(
    val initialized: Boolean = false,
    val online: Boolean = false,
    val hostId: String = "",
    val publicIdentity: String = "",
    val networks: List<ZeroTierNetworkState> = emptyList(),
    val error: String? = null,
    val transportError: String? = null,
    val controlError: String? = null
)

internal fun zeroTierNetworkStates(
    joined: Iterable<Long>,
    configurations: Iterable<VirtualNetworkConfig>
): List<ZeroTierNetworkState> {
    val configs = configurations.associateBy { it.nwid }
    return joined.map { id ->
        val config = configs[id]
        ZeroTierNetworkState(
            ZeroTierManager.formatNetworkId(id),
            config?.status?.name?.removePrefix("NETWORK_STATUS_")?.replace('_', ' ')
                ?: "Waiting for configuration",
            config?.mac?.let(AndroidZeroTierPacketAdapter::macAddress).orEmpty(),
            config?.name.orEmpty(),
            config?.type?.name.orEmpty(),
            config?.mtu ?: 0,
            config?.assignedAddresses.orEmpty().map { ZeroTierManager.socketCidr(it) },
            config?.routes.orEmpty().mapNotNull { route ->
                val target = route.target?.let { ZeroTierManager.socketCidr(it) } ?: return@mapNotNull null
                val gateway = route.via?.address?.hostAddress
                val via = if (gateway.isNullOrBlank()) target else "$target=$gateway"
                "$via@${route.metric}"
            }
        )
    }
}

internal fun zeroTierState(
    status: NodeStatus,
    networks: List<ZeroTierNetworkState>,
    transportError: String? = null,
    controlError: String? = null
): ZeroTierState = ZeroTierState(
    initialized = true,
    online = status.isOnline,
    hostId = ZeroTierManager.formatHostId(status.address),
    publicIdentity = status.publicIdentity,
    networks = networks,
    transportError = transportError,
    controlError = controlError
)

/** A process-lifetime ZeroTier node whose identity and SDK data live in app-private files. */
class ZeroTierManager(
    context: Context,
    private val packetAdapter: ZeroTierPacketAdapter,
    private val nodeFactory: (Long) -> Node = { Node(it) }
) {
    private val appContext = context.applicationContext
    private val store = ZeroTierDataStore(File(appContext.filesDir, "zerotier"))
    private val preferences = appContext.getSharedPreferences("zerotier", Context.MODE_PRIVATE)
    private val controlToken: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        preferences.getString(KEY_CONTROL_TOKEN, null)?.takeIf(String::isNotBlank) ?: run {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val generated = Base64.encodeToString(
                bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            check(preferences.edit().putString(KEY_CONTROL_TOKEN, generated).commit()) {
                "Unable to persist ZeroTier CLI token"
            }
            generated
        }
    }
    private val controlServer: ZeroTierControlServer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ZeroTierControlServer(
            token = controlToken,
            snapshot = ::controlSnapshot,
            join = { networkId -> runBlocking { join(networkId).getOrThrow() } },
            leave = { networkId -> runBlocking { leave(networkId).getOrThrow() } }
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(ZeroTierState())
    val state: StateFlow<ZeroTierState> = mutableState.asStateFlow()
    fun snapshot(): ZeroTierState = mutableState.value

    private var node: Node? = null
    private var taskJob: Job? = null
    private val joined = linkedSetOf<Long>()
    private val pendingMulticast = linkedMapOf<Long, Set<MulticastSubscription>>()
    private val activeMulticast = linkedMapOf<Long, Set<MulticastSubscription>>()

    suspend fun start() = mutex.withLock {
        ensureControlServer()
        if (started.get()) return@withLock
        try {
            joined.clear()
            preferences.getStringSet(KEY_NETWORKS, emptySet()).orEmpty().forEach { value ->
                parseNetworkId(value)?.let(joined::add)
            }
            val n = nodeFactory(System.currentTimeMillis())
            val result = n.init(
                DataStoreGetListener { name, buffer -> store.get(name, buffer) },
                object : DataStorePutListener {
                    override fun onDataStorePut(name: String, buffer: ByteArray, secure: Boolean) =
                        store.put(name, buffer, secure)
                    override fun onDelete(name: String) = store.delete(name)
                },
                PacketSender { local, remote, bytes, _ ->
                    packetAdapter.sendWirePacket(local, remote, bytes)
                },
                // JNI callbacks can run inside node calls; status reads happen in the worker loop.
                object : EventListener {
                    override fun onEvent(event: com.zerotier.sdk.Event) = Unit
                    override fun onTrace(message: String) = Unit
                },
                VirtualNetworkFrameListener { nwid, src, dest, etherType, vlan, data ->
                    packetAdapter.receiveVirtualFrame(nwid, src, dest, etherType, vlan, data)
                },
                VirtualNetworkConfigListener { nwid, op, config ->
                    val callbackResult = packetAdapter.onNetworkConfiguration(nwid, op, config)
                    synchronized(pendingMulticast) {
                        pendingMulticast[nwid] = if (
                            op == VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY ||
                            op == VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN
                        ) emptySet() else config?.assignedAddresses.orEmpty().mapNotNull { resolutionGroup(it) }.toSet()
                    }
                    scope.launch { mutex.withLock { node?.let(::refreshSnapshot) } }
                    callbackResult
                },
                object : PathChecker {
                    override fun onPathCheck(ztAddress: Long, localSocket: Long, remoteAddress: InetSocketAddress) =
                        packetAdapter.isPathAllowed(ztAddress, localSocket, remoteAddress)
                    override fun onPathLookup(ztAddress: Long, ssFamily: Int) =
                        packetAdapter.lookupPath(ztAddress, ssFamily)
                }
            )
            if (result != ResultCode.RESULT_OK) error("ZeroTier node init failed: $result")
            node = n
            packetAdapter.setWirePacketReceiver { local, remote, bytes ->
                scope.launch { processWirePacket(local, remote, bytes) }
            }
            packetAdapter.setVirtualFrameReceiver { networkId, frame ->
                scope.launch { processVirtualEthernetFrame(networkId, frame) }
            }
            joined.forEach { n.join(it) }
            started.set(true)
            refreshSnapshot(n)
            taskJob = scope.launch {
                while (true) {
                    mutex.withLock {
                        node?.let { current ->
                            val deadline = longArrayOf(0L)
                            current.processBackgroundTasks(System.currentTimeMillis(), deadline)
                            applyMulticastChanges(current)
                            refreshSnapshot(current)
                        }
                    }
                    delay(500)
                }
            }
        } catch (e: LinkageError) {
            mutableState.value = mutableState.value.copy(
                error = "ZeroTier native library is unavailable: ${e.message ?: e.javaClass.simpleName}"
            )
        } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(error = e.message ?: "Unable to start ZeroTier")
        }
    }

    suspend fun join(networkId: String): Result<Unit> {
        val id = parseNetworkId(networkId)
            ?: return Result.failure(IllegalArgumentException("Enter a 16-digit hexadecimal network ID"))
        start()
        return mutex.withLock {
            val current = node ?: return@withLock Result.failure(
                IllegalStateException(mutableState.value.error ?: "ZeroTier is unavailable")
            )
            try {
                val result = current.join(id)
                if (result != ResultCode.RESULT_OK && result != ResultCode.RESULT_OK_IGNORED) {
                    return@withLock Result.failure(IllegalStateException("Join failed: $result"))
                }
                joined.add(id)
                persistNetworks()
                refreshSnapshot(current)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    /** Called by the VPN service so node UDP traffic is protected from Rethink's TUN. */
    fun attachUnderlay(protect: (DatagramSocket) -> Boolean, network: Network?) {
        val error = packetAdapter.attachUnderlay(protect, network)
        mutableState.value = mutableState.value.copy(transportError = error)
    }

    fun detachUnderlay() {
        packetAdapter.detachUnderlay()
        mutableState.value = mutableState.value.copy(transportError = null)
    }

    fun attachTunnel(tunnel: ZeroTierTunnelApi?) = packetAdapter.attachTunnel(tunnel)

    /** Token for clients running locally on this device against the loopback-only control endpoint. */
    fun controlAuthToken(): String = controlToken

    private fun ensureControlServer() {
        try {
            controlServer.start()
            if (mutableState.value.controlError != null) {
                mutableState.value = mutableState.value.copy(controlError = null)
            }
        } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(
                controlError = e.message ?: "Unable to start the local ZeroTier CLI endpoint"
            )
        }
    }

    private fun controlSnapshot(): ZeroTierControlSnapshot {
        val current = mutableState.value
        return ZeroTierControlSnapshot(
            address = current.hostId,
            online = current.online,
            version = CONTROL_VERSION,
            publicIdentity = current.publicIdentity,
            networks = current.networks.map { network ->
                ZeroTierControlNetwork(
                    networkId = network.networkId,
                    mac = network.mac,
                    name = network.name,
                    status = network.configurationStatus.uppercase().replace(' ', '_'),
                    type = network.type.uppercase(),
                    mtu = network.mtu,
                    assignedAddresses = network.assignedAddresses,
                    routes = network.routes.map { parseControlRoute(it) }
                )
            }
        )
    }

    private fun parseControlRoute(value: String): ZeroTierControlRoute {
        val metricSeparator = value.lastIndexOf('@')
        val metric = if (metricSeparator >= 0) value.substring(metricSeparator + 1).toIntOrNull() ?: 0 else 0
        val route = if (metricSeparator >= 0) value.substring(0, metricSeparator) else value
        val gatewaySeparator = route.indexOf('=')
        return if (gatewaySeparator >= 0) {
            ZeroTierControlRoute(
                target = route.substring(0, gatewaySeparator),
                via = route.substring(gatewaySeparator + 1),
                metric = metric
            )
        } else {
            ZeroTierControlRoute(target = route, metric = metric)
        }
    }

    suspend fun leave(networkId: String): Result<Unit> = mutex.withLock {
        val id = parseNetworkId(networkId)
            ?: return@withLock Result.failure(IllegalArgumentException("Invalid network ID"))
        val current = node ?: return@withLock Result.failure(IllegalStateException("ZeroTier is not running"))
        try {
            val result = current.leave(id)
            if (result != ResultCode.RESULT_OK && result != ResultCode.RESULT_OK_IGNORED) {
                return@withLock Result.failure(IllegalStateException("Leave failed: $result"))
            }
            joined.remove(id)
            persistNetworks()
            refreshSnapshot(current)
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Submit a physical UDP packet received by the packet adapter to the ZeroTier node. */
    suspend fun processWirePacket(localSocket: Long, remote: InetSocketAddress, packet: ByteArray) =
        mutex.withLock {
            val current = node ?: return@withLock
            current.processWirePacket(System.currentTimeMillis(), localSocket, remote, packet, longArrayOf(0L))
            refreshSnapshot(current)
        }

    /** Submit a virtual Ethernet frame from Rethink's TUN bridge to the ZeroTier network. */
    suspend fun processVirtualNetworkFrame(
        networkId: Long,
        sourceMac: Long,
        destinationMac: Long,
        etherType: Int,
        vlanId: Int,
        payload: ByteArray
    ) = mutex.withLock {
        val current = node ?: return@withLock
        current.processVirtualNetworkFrame(
            System.currentTimeMillis(), networkId, sourceMac, destinationMac,
            etherType, vlanId, payload, longArrayOf(0L)
        )
        refreshSnapshot(current)
    }

    /** Accept a complete Ethernet frame from Firestack and submit it to the SDK. */
    suspend fun processVirtualEthernetFrame(networkId: String, frame: ByteArray) {
        if (frame.size < 14) return
        val destination = macToLong(frame, 0)
        val source = macToLong(frame, 6)
        var etherType = ((frame[12].toInt() and 0xff) shl 8) or (frame[13].toInt() and 0xff)
        var vlan = 0
        var payloadOffset = 14
        if ((etherType == 0x8100 || etherType == 0x88a8) && frame.size >= 18) {
            vlan = (((frame[14].toInt() and 0xff) shl 8) or (frame[15].toInt() and 0xff)) and 0x0fff
            etherType = ((frame[16].toInt() and 0xff) shl 8) or (frame[17].toInt() and 0xff)
            payloadOffset = 18
        }
        val id = parseNetworkId(networkId) ?: return
        processVirtualNetworkFrame(id, source, destination, etherType, vlan, frame.copyOfRange(payloadOffset, frame.size))
    }

    private fun persistNetworks() {
        check(preferences.edit().putStringSet(KEY_NETWORKS, joined.mapTo(linkedSetOf()) { formatNetworkId(it) }).commit()) {
            "Unable to persist ZeroTier network membership"
        }
    }

    private fun applyMulticastChanges(current: Node) {
        val changes = synchronized(pendingMulticast) { pendingMulticast.toMap() }
        changes.forEach { (networkId, desired) ->
            val active = activeMulticast[networkId].orEmpty()
            (active - desired).forEach { current.multicastUnsubscribe(networkId, it.mac, it.adi) }
            (desired - active).forEach { current.multicastSubscribe(networkId, it.mac, it.adi) }
            activeMulticast[networkId] = desired
        }
    }

    private fun refreshSnapshot(current: Node) {
        try {
            val status: NodeStatus = current.status()
            val networks = zeroTierNetworkStates(joined, current.networkConfigs().orEmpty().asIterable())
            val updated = zeroTierState(
                status,
                networks,
                transportError = mutableState.value.transportError,
                controlError = mutableState.value.controlError
            )
            if (updated != mutableState.value) mutableState.value = updated
        } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(error = e.message ?: "Unable to read ZeroTier status")
        }
    }

    companion object {
        private const val KEY_NETWORKS = "joined_network_ids"
        private const val KEY_CONTROL_TOKEN = "cli_control_token"
        private const val CONTROL_VERSION = "1.16.2"
        fun parseNetworkId(value: String): Long? {
            val clean = value.trim().removePrefix("0x").removePrefix("0X")
            if (!clean.matches(Regex("[0-9a-fA-F]{16}"))) return null
            return clean.toULongOrNull(16)?.toLong()
        }
        fun formatNetworkId(value: Long): String = value.toULong().toString(16).padStart(16, '0')
        fun formatHostId(value: Long): String = (value and 0xffffffffffL).toString(16).padStart(10, '0')

        internal data class MulticastSubscription(val mac: Long, val adi: Long)

        /** Build ZeroTier's address-resolution subscription for one SDK-assigned IP. */
        internal fun resolutionGroup(address: InetSocketAddress): MulticastSubscription? {
            val bytes = address.address?.address ?: return null
            return when {
                address.address is Inet4Address && bytes.size == 4 -> MulticastSubscription(
                    0xffffffffffffL,
                    ((bytes[0].toLong() and 0xff) shl 24) or
                        ((bytes[1].toLong() and 0xff) shl 16) or
                        ((bytes[2].toLong() and 0xff) shl 8) or (bytes[3].toLong() and 0xff)
                )
                address.address is Inet6Address && bytes.size == 16 -> MulticastSubscription(
                    0x3333ff000000L or ((bytes[13].toLong() and 0xff) shl 16) or
                        ((bytes[14].toLong() and 0xff) shl 8) or (bytes[15].toLong() and 0xff), 0
                )
                else -> null
            }
        }

        private fun macToLong(bytes: ByteArray, offset: Int): Long {
            var result = 0L
            for (i in 0 until 6) result = (result shl 8) or (bytes[offset + i].toLong() and 0xff)
            return result
        }

        /** The SDK stores a CIDR prefix in InetSocketAddress.port for assigned addresses/routes. */
        fun socketCidr(address: InetSocketAddress): String {
            val host = address.address?.hostAddress ?: address.hostString
            val prefix = if ((host == "0.0.0.0" || host == "::" || host == "0:0:0:0:0:0:0:0") && address.port == 0) {
                0
            } else {
                address.port
            }
            return "$host/$prefix"
        }
    }
}

/** Safe file-backed implementation of the SDK's opaque object store. */
internal class ZeroTierDataStore(private val root: File) {
    @Synchronized fun get(name: String, buffer: ByteArray): Long = try {
        val file = resolve(name) ?: return -2
        if (!file.isFile) return -1
        file.inputStream().use { input ->
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                if (count == 0) continue
                total += count
            }
            total.toLong()
        }
    } catch (_: Exception) { -2 }

    @Synchronized fun put(name: String, buffer: ByteArray, secure: Boolean): Int = try {
        val target = resolve(name) ?: return -2
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(buffer)
        if (secure) temp.setReadable(false, false).also { temp.setReadable(true, true) }
        if (secure) temp.setWritable(false, false).also { temp.setWritable(true, true) }
        if (!temp.renameTo(target)) { temp.delete(); return -2 }
        0
    } catch (e: Exception) { e.printStackTrace(); -2 }

    @Synchronized fun delete(name: String): Int = try {
        val file = resolve(name) ?: return -2
        if (!file.exists() || file.delete()) 0 else -2
    } catch (_: Exception) { -2 }

    private fun resolve(name: String): File? {
        if (name.isBlank() || name.contains('\\') || name.split('/').any { it == ".." || it.isBlank() }) return null
        val base = root.canonicalFile
        val target = File(base, name).canonicalFile
        return target.takeIf { it.path.startsWith(base.path + File.separator) }
    }
}
