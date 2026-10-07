/*
 * Copyright 2026 RethinkDNS and its authors
 * Licensed under the Apache License, Version 2.0
 */
package com.celzero.bravedns.zerotier

import android.net.Network
import com.zerotier.sdk.VirtualNetworkConfig
import com.zerotier.sdk.VirtualNetworkConfigOperation
import com.zerotier.sdk.VirtualNetworkStatus
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/** Joins the ZeroTier SDK's packet callbacks to Android UDP and Firestack's per-network TUN NICs. */
class AndroidZeroTierPacketAdapter : ZeroTierPacketAdapter {
    private val transport = ZeroTierUdpTransport()
    private val configs = ConcurrentHashMap<Long, VirtualNetworkConfig>()
    private val downNetworks = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var tunnel: ZeroTierTunnelApi? = null
    @Volatile private var frameReceiver: ((String, ByteArray) -> Unit)? = null

    override fun setWirePacketReceiver(receiver: ZeroTierWirePacketReceiver) {
        transport.setReceiver(receiver)
    }

    override fun setVirtualFrameReceiver(receiver: (String, ByteArray) -> Unit) {
        frameReceiver = receiver
        configureAll()
    }

    override fun attachUnderlay(protect: (DatagramSocket) -> Boolean, network: Network?): String? {
        return transport.attach(protect, network)
    }

    override fun detachUnderlay() {
        transport.detach()
    }

    override fun attachTunnel(tunnel: ZeroTierTunnelApi?) {
        val previous = this.tunnel
        this.tunnel = tunnel
        if (tunnel == null) {
            configs.keys.forEach { previous?.clearZeroTier(ZeroTierManager.formatNetworkId(it)) }
        } else {
            configureAll()
        }
    }

    override fun sendWirePacket(localSocket: Long, remote: InetSocketAddress, packet: ByteArray): Int =
        transport.send(remote, packet)

    override fun receiveVirtualFrame(
        networkId: Long,
        sourceMac: Long,
        destinationMac: Long,
        etherType: Long,
        vlanId: Long,
        payload: ByteArray
    ): Int {
        val id = ZeroTierManager.formatNetworkId(networkId)
        val frame = ethernetFrame(destinationMac, sourceMac, etherType.toInt(), vlanId.toInt(), payload)
        return try {
            if (tunnel?.injectZeroTierFrame(id, frame) == true) 0 else -1
        } catch (_: Exception) { -1 }
    }

    override fun onNetworkConfiguration(
        networkId: Long,
        operation: VirtualNetworkConfigOperation,
        config: VirtualNetworkConfig?
    ): Int {
        val id = ZeroTierManager.formatNetworkId(networkId)
        return try {
            when (operation) {
                VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY -> {
                    configs.remove(networkId)
                    downNetworks.remove(networkId)
                    tunnel?.clearZeroTier(id)
                    0
                }
                VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN -> {
                    downNetworks.add(networkId)
                    tunnel?.clearZeroTier(id)
                    0
                }
                else -> {
                    downNetworks.remove(networkId)
                    if (config != null) configs[networkId] = config
                    configure(networkId, config ?: configs[networkId])
                    0
                }
            }
        } catch (_: Exception) { -1 }
    }

    private fun configureAll() {
        configs.forEach { (networkId, config) ->
            if (networkId !in downNetworks) configure(networkId, config)
        }
    }

    private fun configure(networkId: Long, config: VirtualNetworkConfig?) {
        val currentTunnel = tunnel ?: return
        val currentConfig = config ?: return
        val id = ZeroTierManager.formatNetworkId(networkId)
        if (currentConfig.status != VirtualNetworkStatus.NETWORK_STATUS_OK) {
            currentTunnel.clearZeroTier(id)
            return
        }
        val addresses = currentConfig.assignedAddresses.orEmpty().joinToString(",") {
            ZeroTierManager.socketCidr(it)
        }
        val routes = currentConfig.routes.orEmpty().mapNotNull { route ->
            val target = route.target?.let { ZeroTierManager.socketCidr(it) } ?: return@mapNotNull null
            val gateway = route.via?.address?.hostAddress
            val spec = if (gateway.isNullOrBlank()) target else "$target=$gateway"
            "$spec@${route.metric}"
        }.joinToString(",")
        currentTunnel.configureZeroTier(
            id,
            macAddress(currentConfig.mac),
            addresses,
            routes
        ) { nwid, frame -> frameReceiver?.invoke(nwid, frame) }
    }

    companion object {
        fun ethernetFrame(destinationMac: Long, sourceMac: Long, etherType: Int, vlanId: Int,
                          payload: ByteArray): ByteArray {
            val tagged = vlanId in 1..4094
            val headerLength = if (tagged) 18 else 14
            val frame = ByteArray(headerLength + payload.size)
            writeMac(frame, 0, destinationMac)
            writeMac(frame, 6, sourceMac)
            if (tagged) {
                frame[12] = 0x81.toByte()
                frame[13] = 0x00
                frame[14] = ((vlanId shr 8) and 0x0f).toByte()
                frame[15] = (vlanId and 0xff).toByte()
                frame[16] = ((etherType shr 8) and 0xff).toByte()
                frame[17] = (etherType and 0xff).toByte()
            } else {
                frame[12] = ((etherType shr 8) and 0xff).toByte()
                frame[13] = (etherType and 0xff).toByte()
            }
            payload.copyInto(frame, headerLength)
            return frame
        }

        fun macAddress(mac: Long): String = (5 downTo 0).joinToString(":") { shift ->
            "%02x".format((mac ushr (shift * 8)) and 0xff)
        }

        private fun writeMac(out: ByteArray, offset: Int, mac: Long) {
            for (i in 0 until 6) out[offset + i] = (mac ushr ((5 - i) * 8)).toByte()
        }
    }
}

/** One protected physical datagram socket; closing it interrupts the receive loop on underlay loss. */
internal class ZeroTierUdpTransport {
    private val lock = Any()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var receiver: ZeroTierWirePacketReceiver? = null
    @Volatile private var receiveThread: Thread? = null

    fun setReceiver(receiver: ZeroTierWirePacketReceiver) {
        this.receiver = receiver
    }

    fun attach(protect: (DatagramSocket) -> Boolean, network: Network?): String? = synchronized(lock) {
        closeLocked()
        val candidate = DatagramSocket(null)
        try {
            // Protect before binding so this socket cannot route back into the Rethink TUN.
            if (!protect(candidate)) throw SocketException("VpnService refused to protect ZeroTier socket")
            network?.bindSocket(candidate)
            candidate.bind(InetSocketAddress(0))
            socket = candidate
            val thread = Thread({ receiveLoop(candidate) }, "ZeroTier-UDP-recv").apply { isDaemon = true }
            receiveThread = thread
            thread.start()
            null
        } catch (e: Exception) {
            candidate.close()
            e.message ?: "Unable to start ZeroTier UDP underlay"
        }
    }

    fun detach() = synchronized(lock) { closeLocked() }

    fun send(remote: InetSocketAddress, payload: ByteArray): Int {
        val current = socket ?: return -1
        return try {
            current.send(DatagramPacket(payload, payload.size, remote))
            0
        } catch (_: Exception) { -1 }
    }

    private fun receiveLoop(bound: DatagramSocket) {
        val buffer = ByteArray(65535)
        while (!bound.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                bound.receive(packet)
                receiver?.onWirePacket(
                    -1L,
                    InetSocketAddress(packet.address, packet.port),
                    packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                )
            } catch (_: SocketException) {
                if (!bound.isClosed) continue
                return
            } catch (_: Exception) {
                if (bound.isClosed) return
            }
        }
    }

    private fun closeLocked() {
        socket?.close()
        socket = null
        receiveThread = null
    }
}
