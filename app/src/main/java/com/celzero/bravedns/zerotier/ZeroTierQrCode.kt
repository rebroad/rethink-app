package com.celzero.bravedns.zerotier

/** Extracts one unambiguous 16-digit network ID from a plain QR value or a share URL. */
object ZeroTierQrCode {
    private val networkIdPattern = Regex("(?i)(?<![0-9a-f])[0-9a-f]{16}(?![0-9a-f])")

    fun networkId(contents: String): String? {
        val matches = networkIdPattern.findAll(contents.trim()).toList()
        if (matches.size != 1) return null
        return matches.single().value.lowercase()
    }
}
