/*
 * Copyright 2026 RethinkDNS and its authors
 * Licensed under the Apache License, Version 2.0
 */
package com.celzero.bravedns.ui.activity

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.zerotier.ZeroTierManager
import com.celzero.bravedns.zerotier.ZeroTierQrCode
import com.celzero.bravedns.zerotier.ZeroTierState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.koin.android.ext.android.inject

class ZeroTierActivity : BaseActivity(R.layout.activity_zerotier) {
    private val manager by inject<ZeroTierManager>()
    private val persistentState by inject<PersistentState>()
    private lateinit var networkId: EditText
    private lateinit var status: TextView
    private lateinit var hostId: TextView
    private lateinit var statusDetails: TextView
    private lateinit var networkList: LinearLayout
    private lateinit var error: TextView

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val scannedId = ZeroTierQrCode.networkId(contents)
        if (scannedId == null) {
            error.text = getString(R.string.zerotier_qr_invalid)
        } else {
            networkId.setText(scannedId)
            error.text = ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        title = getString(R.string.zerotier_title)

        status = findViewById(R.id.zerotier_status)
        hostId = findViewById(R.id.zerotier_host_id)
        statusDetails = findViewById(R.id.zerotier_status_details)
        networkId = findViewById(R.id.zerotier_network_id)
        error = findViewById(R.id.zerotier_error)
        networkList = findViewById(R.id.zerotier_network_list)

        findViewById<com.google.android.material.button.MaterialButton>(R.id.zerotier_copy_cli_token)
            .setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ZeroTier CLI token", manager.controlAuthToken()))
                Toast.makeText(this, R.string.zerotier_cli_token_copied, Toast.LENGTH_SHORT).show()
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.zerotier_scan_qr)
            .setOnClickListener {
                qrScanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setOrientationLocked(false)
                        .setBeepEnabled(false)
                        .setPrompt(getString(R.string.zerotier_scan_qr_prompt))
                )
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.zerotier_join)
            .setOnClickListener {
                val id = networkId.text.toString()
                lifecycleScope.launch {
                    val result = manager.join(id)
                    error.text = result.exceptionOrNull()?.localizedMessage.orEmpty()
                    if (result.isSuccess) networkId.text?.clear()
                }
            }

        lifecycleScope.launch { manager.start() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.state.collect(::render)
            }
        }
    }

    private fun render(state: ZeroTierState) {
        status.text = getString(if (state.online) R.string.zerotier_node_online else R.string.zerotier_node_offline)
        hostId.text = getString(R.string.zerotier_host_id, state.hostId.ifBlank { "—" })
        statusDetails.text = listOfNotNull(
            state.transportError?.let { "UDP underlay: $it" },
            state.controlError?.let { "CLI control endpoint: $it" }
        ).joinToString("\n")
        error.text = state.error.orEmpty()
        networkList.removeAllViews()
        state.networks.forEach { network ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_zerotier_network, networkList, false)
            val details = buildList {
                if (network.assignedAddresses.isNotEmpty()) add(network.assignedAddresses.joinToString())
                if (network.routes.isNotEmpty()) add("Routes: ${network.routes.joinToString()}")
            }.joinToString("\n")
            card.findViewById<TextView>(R.id.zerotier_network_status).text =
                "${network.networkId} · ${network.configurationStatus}"
            card.findViewById<TextView>(R.id.zerotier_network_details).apply {
                text = details
                if (details.isBlank()) visibility = android.view.View.GONE
            }
            card.findViewById<com.google.android.material.button.MaterialButton>(R.id.zerotier_leave)
                .setOnClickListener {
                    lifecycleScope.launch {
                        val result = manager.leave(network.networkId)
                        this@ZeroTierActivity.error.text = result.exceptionOrNull()?.localizedMessage.orEmpty()
                    }
                }
            networkList.addView(card, matchWidth())
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun Context.isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

}
