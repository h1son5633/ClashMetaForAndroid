package com.github.kr328.clash.design

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.databinding.DesignHubBinding
import com.github.kr328.clash.design.util.HomeNavStyle
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

/**
 * Shell that hosts the existing ProxyDesign and ProfilesDesign in a
 * single page, switched by a segmented control (the bottom-nav "hub"
 * tab). The hosted designs keep their own layouts and logic; this
 * class only owns the switcher and the shared bottom navigation.
 */
class HubDesign(context: Context) : Design<HubDesign.Request>(context) {
    enum class Request {
        ShowProxy,
        ShowProfiles,
        OpenHome,
        OpenStats,
        OpenSettings,
    }

    private val binding = DesignHubBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private fun color(resId: Int) = ContextCompat.getColor(context, resId)

    fun attachDesign(designRoot: View) {
        if (designRoot.parent != null) {
            (designRoot.parent as ViewGroup).removeView(designRoot)
        }
        binding.hubContainer.addView(
            designRoot,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        designRoot.visibility = View.GONE
    }

    fun showContent(proxyRoot: View?, profilesRoot: View?, proxy: Boolean) {
        proxyRoot?.visibility = if (proxy) View.VISIBLE else View.GONE
        profilesRoot?.visibility = if (proxy) View.GONE else View.VISIBLE

        binding.tabProxy.setBackgroundResource(
            if (proxy) R.drawable.home_power_btn_on else 0
        )
        binding.tabProxy.setTextColor(
            color(if (proxy) R.color.home_on_accent_ink else R.color.home_text_secondary)
        )
        binding.tabProfiles.setBackgroundResource(
            if (proxy) 0 else R.drawable.home_power_btn_on
        )
        binding.tabProfiles.setTextColor(
            color(if (proxy) R.color.home_text_secondary else R.color.home_on_accent_ink)
        )
    }

    init {
        binding.self = this

        binding.tabProxy.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.ShowProxy) }
        binding.tabProfiles.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.ShowProfiles) }

        // Bottom navigation: Hub is the current page.
        val nav = binding.navBar
        HomeNavStyle.attach(nav, 2)
        nav.navHomeIcon.setColorFilter(color(R.color.home_text_secondary))
        nav.navHomeText.setTextColor(color(R.color.home_text_secondary))
        nav.navStatsIcon.setColorFilter(color(R.color.home_text_secondary))
        nav.navStatsText.setTextColor(color(R.color.home_text_secondary))
        nav.navHubIcon.setColorFilter(color(R.color.home_accent))
        nav.navHubText.setTextColor(color(R.color.home_accent))
        nav.navSettingsIcon.setColorFilter(color(R.color.home_text_secondary))
        nav.navSettingsText.setTextColor(color(R.color.home_text_secondary))
        nav.navHome.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenHome) }
        nav.navStats.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenStats) }
        nav.navSettings.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); request(Request.OpenSettings) }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
