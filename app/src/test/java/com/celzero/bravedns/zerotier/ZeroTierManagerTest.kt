package com.celzero.bravedns.zerotier

import java.net.InetSocketAddress
import com.zerotier.sdk.NodeStatus
import com.zerotier.sdk.VirtualNetworkConfig
import com.zerotier.sdk.VirtualNetworkStatus
import com.zerotier.sdk.VirtualNetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZeroTierManagerTest {
    @get:Rule val temp = TemporaryFolder()
    private val networkId = "8056c2e21c000001"

    @Test fun `network id parser accepts only 16 hex digits`() {
        assertEquals(0x8056c2e21c000001UL.toLong(), ZeroTierManager.parseNetworkId("8056c2e21c000001"))
        assertEquals(0x8056c2e21c000001UL.toLong(), ZeroTierManager.parseNetworkId(" 0x8056C2E21C000001 "))
        assertNull(ZeroTierManager.parseNetworkId("8056c2e21c00000"))
        assertNull(ZeroTierManager.parseNetworkId("8056c2e21c00000g"))
    }

    @Test fun `QR scanner accepts a network ID or a share URL containing one ID`() {
        assertEquals("8056c2e21c000001", ZeroTierQrCode.networkId("8056C2E21C000001"))
        assertEquals(
            "8056c2e21c000001",
            ZeroTierQrCode.networkId("https://joinzt.com/addnetwork?nwid=8056C2E21C000001")
        )
        assertNull(ZeroTierQrCode.networkId("not a network ID"))
        assertNull(ZeroTierQrCode.networkId("8056c2e21c000001 and 8056c2e21c000002"))
    }

    @Test fun `formats network and host ids as fixed width lowercase hex`() {
        assertEquals("000000000000000a", ZeroTierManager.formatNetworkId(10))
        assertEquals("0123456789", ZeroTierManager.formatHostId(0x7f0123456789L))
    }

    @Test fun `datastore round trips data and rejects traversal`() {
        val root = temp.newFolder("zt")
        val store = ZeroTierDataStore(root)
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertEquals(0, store.put("identity.secret", bytes, true))
        val buffer = ByteArray(8)
        assertEquals(4L, ZeroTierDataStore(root).get("identity.secret", buffer))
        assertTrue(buffer.copyOfRange(0, 4).contentEquals(bytes))
        assertEquals(-1L, store.get("missing", buffer))
        assertEquals(-2L, store.get("../outside", buffer))
        assertEquals(-2, store.put("../outside", bytes, true))
    }

    @Test fun `node connectivity and network authorization remain independent`() {
        val id = ZeroTierManager.parseNetworkId(networkId)!!
        val authorized = networkConfig(VirtualNetworkStatus.NETWORK_STATUS_OK)
        val waiting = zeroTierNetworkStates(listOf(id, id + 1), listOf(authorized))
        assertEquals("OK", waiting[0].configurationStatus)
        assertEquals("Waiting for configuration", waiting[1].configurationStatus)

        val offlineNode = zeroTierState(NodeStatus(0x7f0123456789L, "public", "secret", false), waiting)
        assertEquals(false, offlineNode.online)
        assertEquals("0123456789", offlineNode.hostId)
        assertEquals("OK", offlineNode.networks[0].configurationStatus)

        val denied = zeroTierNetworkStates(
            listOf(id),
            listOf(networkConfig(VirtualNetworkStatus.NETWORK_STATUS_ACCESS_DENIED))
        )
        val onlineNode = zeroTierState(NodeStatus(0x7f0123456789L, "public", "secret", true), denied)
        assertEquals(true, onlineNode.online)
        assertEquals("ACCESS DENIED", onlineNode.networks.single().configurationStatus)
    }

    @Test fun `sdk socket target keeps prefix rather than socket port`() {
        assertEquals("10.1.2.0/24", ZeroTierManager.socketCidr(InetSocketAddress("10.1.2.0", 24)))
        assertEquals("0.0.0.0/0", ZeroTierManager.socketCidr(InetSocketAddress("0.0.0.0", 0)))
    }

    @Test fun `assigned ipv4 subscribes to broadcast with host-order address adi`() {
        val subscription = ZeroTierManager.resolutionGroup(InetSocketAddress("192.168.1.10", 24))!!
        assertEquals(0xffffffffffffL, subscription.mac)
        assertEquals(0xc0a8010aL, subscription.adi)
    }

    @Test fun `restored network config rebuilds address resolution subscriptions`() {
        val id = ZeroTierManager.parseNetworkId(networkId)!!
        val config = networkConfig(
            VirtualNetworkStatus.NETWORK_STATUS_OK,
            arrayOf(InetSocketAddress("192.168.192.7", 24))
        )

        assertEquals(
            setOf(ZeroTierMulticastSubscription(0xffffffffffffL, 0xc0a8c007L)),
            ZeroTierManager.multicastSubscriptions(listOf(config))[id]
        )
    }

    @Test fun `non-authorized restored network config has no address resolution subscriptions`() {
        val id = ZeroTierManager.parseNetworkId(networkId)!!
        val config = networkConfig(
            VirtualNetworkStatus.NETWORK_STATUS_ACCESS_DENIED,
            arrayOf(InetSocketAddress("192.168.192.7", 24))
        )

        assertEquals(emptySet<ZeroTierMulticastSubscription>(), ZeroTierManager.multicastSubscriptions(listOf(config))[id])
    }

    @Test fun `assigned ipv6 subscribes to solicited-node multicast mac`() {
        val subscription = ZeroTierManager.resolutionGroup(InetSocketAddress("2001:db8::12:34:ab:cd:ef", 64))!!
        assertEquals(0x3333ffcd00efL, subscription.mac)
        assertEquals(0L, subscription.adi)
    }

    @Test fun `ethernet frame conversion preserves macs ethertype vlan and payload`() {
        val payload = byteArrayOf(0x45, 0, 0, 1)
        val frame = AndroidZeroTierPacketAdapter.ethernetFrame(
            0x001122334455L, 0xaabbccddeeffL, 0x0800, 42, payload
        )
        assertEquals(18 + payload.size, frame.size)
        assertEquals(0x00, frame[0].toInt() and 0xff)
        assertEquals(0x55, frame[5].toInt() and 0xff)
        assertEquals(0xaa, frame[6].toInt() and 0xff)
        assertEquals(0xff, frame[11].toInt() and 0xff)
        assertEquals(0x81, frame[12].toInt() and 0xff)
        assertEquals(0x00, frame[13].toInt() and 0xff)
        assertEquals(42, (((frame[14].toInt() and 0xff) shl 8) or (frame[15].toInt() and 0xff)) and 0xfff)
        assertEquals(0x0800, ((frame[16].toInt() and 0xff) shl 8) or (frame[17].toInt() and 0xff))
        assertTrue(frame.copyOfRange(18, frame.size).contentEquals(payload))
    }

    private fun networkConfig(
        status: VirtualNetworkStatus,
        addresses: Array<InetSocketAddress> = emptyArray()
    ) = VirtualNetworkConfig(
        ZeroTierManager.parseNetworkId(networkId)!!,
        0xaabbccddeeffL,
        "test network",
        status,
        VirtualNetworkType.NETWORK_TYPE_PRIVATE,
        2800,
        false,
        false,
        true,
        0,
        1L,
        addresses,
        emptyArray(),
        null
    )
}
