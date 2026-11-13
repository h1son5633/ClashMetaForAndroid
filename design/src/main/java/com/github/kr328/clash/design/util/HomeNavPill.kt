package com.github.kr328.clash.design.util

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.github.kr328.clash.design.databinding.LayoutHomeNavBinding

/**
 * Sliding selection pill for the shared bottom navigation.
 *
 * The four tabs live in separate activities, so the pill cannot
 * physically travel across the activity switch. Instead each page
 * remembers the previously shown tab in [lastIndex]; when a page
 * opens, its pill starts at the previous tab's position and slides
 * to the current tab, which reads as one continuous motion. The
 * glass bar itself never moves — only the small pill inside it.
 */
object HomeNavPill {
    @Volatile
    private var lastIndex: Int = -1

    fun attach(nav: LayoutHomeNavBinding, currentIndex: Int) {
        val items = listOf(nav.navHome, nav.navStats, nav.navHub, nav.navSettings)
        if (currentIndex !in items.indices) return

        val pill = nav.navPill
        pill.post {
            val item = items[currentIndex]
            if (item.width == 0) return@post

            val density = pill.resources.displayMetrics.density
            val pillWidth = item.width - (12f * density).toInt()
            if (pillWidth <= 0) return@post

            val lp = pill.layoutParams
            lp.width = pillWidth
            pill.layoutParams = lp

            fun centerX(index: Int): Float {
                val it = items[index]
                return it.left + (it.width - pillWidth) / 2f
            }

            val target = centerX(currentIndex)
            val from = lastIndex
            pill.visibility = View.VISIBLE
            if (from in items.indices && from != currentIndex) {
                val start = centerX(from)
                pill.translationX = start
                ValueAnimator.ofFloat(start, target).apply {
                    duration = 320L
                    interpolator = DecelerateInterpolator(1.6f)
                    addUpdateListener { pill.translationX = it.animatedValue as Float }
                    start()
                }
            } else {
                pill.translationX = target
            }
            lastIndex = currentIndex
        }
    }
}
