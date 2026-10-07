/*
 * Copyright 2021 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.data

import com.celzero.bravedns.util.Constants

data class AppConnection(
    val uid: Int,
    val ipAddress: String,
    val port: Int,
    val count: Int,
    val flag: String,
    val blocked: Boolean,
    val appOrDnsName: String?,
    val downloadBytes: Long? = 0L,
    val uploadBytes: Long? = 0L,
    val totalBytes: Long? = 0L
)

/** Display an unattributed connection by endpoint instead of a generic app-name label. */
fun AppConnection.unknownEndpointLabel(): String? {
    if (ipAddress.isBlank()) return null
    if (port <= 0) return ipAddress

    val host = if (ipAddress.contains(':')) "[$ipAddress]" else ipAddress
    return "$host:$port"
}

private fun String?.isGenericUnknownName(): Boolean =
    this.isNullOrBlank() || this.equals(Constants.UNKNOWN_APP, ignoreCase = true) ||
        this.startsWith("${Constants.UNKNOWN_APP} (", ignoreCase = true)

/** Use the endpoint for unresolved Stats rows, preserving a real app name when available. */
fun AppConnection.statsAppLabel(resolvedAppName: String?): String? {
    val appName = resolvedAppName?.takeUnless { it.isGenericUnknownName() }
        ?: appOrDnsName?.takeUnless { it.isGenericUnknownName() }
    return appName ?: unknownEndpointLabel()
}

/** Resolve a Top Active Connections row, using its protocol marker for unknown app rows. */
fun AppConnection.topActiveConnectionLabel(resolvedAppName: String?): String? {
    // The query's protocol marker identifies unattributed rows, while the
    // stored app name is the fallback signal for rows from older or alternate
    // query paths. In either case, do not let a UID-level lookup replace the
    // endpoint with a generic or unrelated name.
    if (flag.isNotBlank() || appOrDnsName.isGenericUnknownName()) {
        return unknownEndpointLabel() ?: statsAppLabel(resolvedAppName)
    }
    return statsAppLabel(resolvedAppName)
}
