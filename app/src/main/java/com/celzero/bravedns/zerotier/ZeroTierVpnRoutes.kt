/*
 * Copyright 2026 RethinkDNS and its authors
 * Licensed under the Apache License, Version 2.0
 */
package com.celzero.bravedns.zerotier

import inet.ipaddr.IPAddressString
import java.net.InetAddress

internal data class ZeroTierVpnRoute(
    val cidr: String,
    val prefix: Int,
    val metric: Int,
    val networkId: String,
    val address: InetAddress
)

/** Build Android VPN routes only from authorized ZeroTier network configuration. */
internal fun zeroTierVpnRoutes(networks: Iterable<ZeroTierNetworkState>): List<ZeroTierVpnRoute> {
    val candidates = networks.flatMap { network ->
        if (!network.configurationStatus.equals("OK", ignoreCase = true)) {
            return@flatMap emptyList()
        }
        (network.routes + network.assignedAddresses).mapNotNull { encoded ->
            try {
                val metric = encoded.substringAfterLast('@', "0").toIntOrNull() ?: 0
                val route = encoded.substringBeforeLast('@')
                val cidr = route.substringBefore('=')
                val addressText = cidr.substringBefore('/')
                val prefix = cidr.substringAfter('/', "").toInt()
                val parsed = IPAddressString(addressText).toAddress() ?: return@mapNotNull null
                if (prefix !in 0..parsed.bitCount) return@mapNotNull null
                // VpnService.Builder.addRoute requires the address with host bits cleared.
                val prefixBlock = parsed.toPrefixBlock(prefix)
                ZeroTierVpnRoute(
                    "${prefixBlock.toNormalizedString().substringBefore('/')}/$prefix",
                    prefix,
                    metric,
                    network.networkId,
                    prefixBlock.toInetAddress()
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    // Android's Builder is prefix-based, so duplicate targets use Firestack's winner:
    // lowest metric, then lexicographically smallest network ID.
    return candidates.groupBy { it.cidr }.values.map { group ->
        group.sortedWith(compareBy<ZeroTierVpnRoute>({ -it.prefix }, { it.metric }, { it.networkId })).first()
    }.sortedWith(compareBy({ -it.prefix }, { it.metric }, { it.networkId }))
}
