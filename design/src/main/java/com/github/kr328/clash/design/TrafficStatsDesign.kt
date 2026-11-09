package com.github.kr328.clash.design

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.databinding.DesignStatsBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Statistics page: how much traffic this session has used, live
 * speeds with a curve, and the connection duration. All values are
 * fed from the core's real traffic counters by TrafficStatsActivity.
 */
class TrafficStatsDesign(context: Context) : Design<TrafficStatsDesign.Request>(context) {
    enum class Request {
        OpenHome,
        OpenHub,
        OpenSettings,
    }

    private val binding = DesignStatsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private fun color(resId: Int) = ContextCompat.getColor(context, resId)

    suspend fun setTotals(upload: String, download: String, total: String) {
        withContext(Dispatchers.Main) {
            binding.uploadTotalText.text = upload
            binding.downloadTotalText.text = download
            binding.totalText.text = total
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

    suspend fun setDuration(text: String) {
        withContext(Dispatchers.Main) {
            binding.durationText.text = text
        }
    }

    suspend fun clearLive() {
        withContext(Dispatchers.Main) {
            binding.trafficSpark.clear()
            binding.uploadText.text = "0 B/s"
            binding.downloadText.text = "0 B/s"
        }
    }

    init {
        binding.self = this

        val nav = binding.navBar
        nav.navHomeIcon.setColorFilter(color(R.color.home_text_secondary))
        nav.navHomeText.setTextColor(color(R.color.home_text_secondary))
        nav.navStatsIcon.setColorFilter(color(R.color.home_accent))
        nav.navStatsText.setTextColor(color(R.color.home_accent))
        nav.navHubIcon.setColorFilter(color(R.color.home_text_secondary))
        nav.navHubText.setTextColor(color(R.color.home_text_secondary))
        nav.navSettingsIcon.setColorFilter(color(R.color.home_text_secondary))
        nav.navSettingsText.setTextColor(color(R.color.home_text_secondary))
        nav.navHome.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenHome) }
        nav.navHub.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenHub) }
        nav.navSettings.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenSettings) }

        binding.totalText.text = "0 Bytes"
        binding.uploadTotalText.text = "0 Bytes"
        binding.downloadTotalText.text = "0 Bytes"
        binding.uploadText.text = "0 B/s"
        binding.downloadText.text = "0 B/s"
        binding.durationText.text = "00:00:00"
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
