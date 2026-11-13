package com.github.kr328.clash.design

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.HomeNavStyle
import com.github.kr328.clash.design.util.NodeCountry
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Redesigned home screen (dark mint glass style).
 *
 * Keeps the original request surface intact — every pre-existing
 * function (toggle, proxy, profiles, providers, logs, settings, help,
 * about) is still reachable — and adds Stats / Hub / HealthCheck for
 * the new layout. All displayed values come from the real core state
 * fed in by MainActivity; nothing is simulated here.
 */
class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenHelp,
        OpenAbout,
        OpenStats,
        OpenHub,
        HealthCheck,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private fun color(resId: Int) = ContextCompat.getColor(context, resId)

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.statusText.text = context.getString(
                if (running) R.string.home_connected else R.string.home_disconnected
            )
            binding.statusDot.setBackgroundResource(
                if (running) R.drawable.home_dot_on else R.drawable.home_dot_off
            )
            binding.powerBtn.setBackgroundResource(
                if (running) R.drawable.home_power_btn_on else R.drawable.home_power_btn_off
            )
            binding.powerIcon.setColorFilter(
                color(if (running) R.color.home_on_accent_ink else R.color.home_text_primary)
            )
            binding.mapView.topInsetPx = surface.insets.top
            binding.mapView.setConnected(running)
            if (!running) {
                binding.durationText.text = "00:00:00"
                binding.trafficSpark.clear()
                binding.mapView.clearMarker()
            }
        }
    }

    suspend fun setDuration(text: String) {
        withContext(Dispatchers.Main) {
            binding.durationText.text = text
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            binding.modeText.text = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
            }
        }
    }

    /**
     * @param country country resolved from the node name, null when unknown
     * @param nodeName raw current node name, null when nothing is selected
     * @param delayMs last measured delay of the node, 0 when unmeasured
     * @param running whether the tunnel is up (marker only shows then)
     */
    suspend fun setNode(country: NodeCountry?, nodeName: String?, delayMs: Int, running: Boolean) {
        withContext(Dispatchers.Main) {
            if (nodeName == null) {
                binding.nodeFlag.text = ""
                binding.nodeLocation.text = context.getString(R.string.home_no_node)
                binding.nodeName.text = ""
                binding.nodeDelay.text = ""
            } else if (country == null) {
                binding.nodeFlag.text = "🌐"
                binding.nodeLocation.text = context.getString(R.string.home_unknown_location)
                binding.nodeName.text = nodeName
                binding.nodeDelay.text = if (delayMs > 0) "$delayMs ms" else "—"
            } else {
                binding.nodeFlag.text = country.flag
                binding.nodeLocation.text = "${country.displayName} · ${country.capital}"
                binding.nodeName.text = nodeName
                binding.nodeDelay.text = if (delayMs > 0) "$delayMs ms" else "—"
            }

            if (running && country != null) {
                binding.mapView.setMarker(country.latitude, country.longitude)
            } else {
                binding.mapView.clearMarker()
            }
        }
    }

    suspend fun setSpeeds(uploadText: String, downloadText: String) {
        withContext(Dispatchers.Main) {
            binding.uploadText.text = uploadText
            binding.downloadText.text = downloadText
        }
    }

    suspend fun pushTrafficSample(uploadPerSec: Long, downloadPerSec: Long) {
        withContext(Dispatchers.Main) {
            binding.trafficSpark.pushSample(uploadPerSec, downloadPerSec)
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    init {
        binding.self = this

        binding.topImport.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenProfiles) }
        binding.topSettings.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenSettings) }
        binding.powerBtn.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.ToggleStatus) }
        binding.flashBtn.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.HealthCheck) }
        binding.nodeCard.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenProxy) }
        binding.modeRow.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenProxy) }
        binding.providersRow.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenProviders) }
        binding.moreRow.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenLogs) }

        // Bottom navigation: Home is the current page.
        val nav = binding.navBar
        HomeNavStyle.attach(nav, 0)
        nav.navHomeIcon.setColorFilter(color(R.color.home_accent))
        nav.navHomeText.setTextColor(color(R.color.home_accent))
        nav.navStatsIcon.setColorFilter(color(R.color.home_text_secondary))
        nav.navStatsText.setTextColor(color(R.color.home_text_secondary))
        nav.navHubIcon.setColorFilter(color(R.color.home_text_secondary))
        nav.navHubText.setTextColor(color(R.color.home_text_secondary))
        nav.navSettingsIcon.setColorFilter(color(R.color.home_text_secondary))
        nav.navSettingsText.setTextColor(color(R.color.home_text_secondary))
        nav.navStats.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenStats) }
        nav.navHub.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenHub) }
        nav.navSettings.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenSettings) }

        binding.durationText.text = "00:00:00"
        binding.uploadText.text = "0 B/s"
        binding.downloadText.text = "0 B/s"
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
