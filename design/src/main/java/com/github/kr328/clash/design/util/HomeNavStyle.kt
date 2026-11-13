package com.github.kr328.clash.design.util

import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.LayoutHomeNavBinding

/**
 * VideoX-style floating bottom navigation: every tab is its own small
 * capsule button. There is no shared board and no sliding indicator —
 * the current page's capsule is simply the accent-tinted one, the rest
 * are near-solid surface capsules. Icon and label colours are set by
 * each page's design class; this only swaps the capsule backgrounds.
 * Plain shape drawables give no press/ripple effect, matching VideoX.
 */
object HomeNavStyle {
    fun attach(nav: LayoutHomeNavBinding, currentIndex: Int) {
        val items = listOf(nav.navHome, nav.navStats, nav.navHub, nav.navSettings)
        items.forEachIndexed { index, item ->
            item.setBackgroundResource(
                if (index == currentIndex) R.drawable.home_nav_btn_selected
                else R.drawable.home_nav_btn_normal
            )
        }
    }
}
