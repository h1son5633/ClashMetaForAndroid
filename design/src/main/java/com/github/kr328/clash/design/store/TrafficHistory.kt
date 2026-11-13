package com.github.kr328.clash.design.store

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Per-day traffic history, persisted locally in SharedPreferences.
 *
 * The core only exposes the current session's totals
 * (queryTrafficTotal), so history is built by observing those
 * totals: every observation converts the session counter into a
 * lifetime counter (finished sessions are folded into a stored
 * base when a counter reset is detected) and attributes the delta
 * since the previous observation to today. Traffic that flows
 * between the last observation of a session and an unobserved
 * restart cannot be accounted for; nothing is estimated or
 * invented to cover that gap.
 */
object TrafficHistory {
    private const val PREFS = "traffic_history"
    private const val KEEP_DAYS = 35

    data class DayTraffic(val label: String, val upload: Long, val download: Long) {
        val total: Long get() = upload + download
    }

    @Synchronized
    fun record(context: Context, sessionUpload: Long, sessionDownload: Long) {
        val p = prefs(context)
        var baseUp = p.getLong("base_up", 0L)
        var baseDown = p.getLong("base_down", 0L)
        val lifeUp = p.getLong("life_up", 0L)
        val lifeDown = p.getLong("life_down", 0L)

        if (!p.contains("last_up")) {
            // First observation ever: adopt the running session as
            // the baseline; only later deltas are attributed.
            p.edit()
                .putLong("last_up", sessionUpload)
                .putLong("last_down", sessionDownload)
                .putLong("life_up", baseUp + sessionUpload)
                .putLong("life_down", baseDown + sessionDownload)
                .apply()
            return
        }

        var lastUp = p.getLong("last_up", 0L)
        var lastDown = p.getLong("last_down", 0L)

        if (sessionUpload < lastUp || sessionDownload < lastDown) {
            // The core restarted since the last observation: fold
            // the previous session's observed totals into the base.
            baseUp += lastUp
            baseDown += lastDown
            lastUp = 0L
            lastDown = 0L
        }

        val newLifeUp = baseUp + sessionUpload
        val newLifeDown = baseDown + sessionDownload
        val dUp = newLifeUp - lifeUp
        val dDown = newLifeDown - lifeDown

        val e = p.edit()
            .putLong("base_up", baseUp)
            .putLong("base_down", baseDown)
            .putLong("last_up", sessionUpload)
            .putLong("last_down", sessionDownload)
            .putLong("life_up", newLifeUp)
            .putLong("life_down", newLifeDown)

        if (dUp > 0L || dDown > 0L) {
            val key = dayKey(System.currentTimeMillis())
            e.putLong("day_${key}_up", p.getLong("day_${key}_up", 0L) + maxOf(dUp, 0L))
            e.putLong("day_${key}_down", p.getLong("day_${key}_down", 0L) + maxOf(dDown, 0L))
        }
        e.apply()
        prune(p)
    }

    fun today(context: Context): DayTraffic = day(context, Calendar.getInstance())

    /** Oldest first, today last. */
    fun lastDays(context: Context, count: Int): List<DayTraffic> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(count - 1))
        return (0 until count).map {
            val d = day(context, cal)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            d
        }
    }

    private fun day(context: Context, cal: Calendar): DayTraffic {
        val p = prefs(context)
        val key = dayKey(cal.timeInMillis)
        val label = SimpleDateFormat("E", Locale.getDefault()).format(cal.time)
        return DayTraffic(
            label,
            p.getLong("day_${key}_up", 0L),
            p.getLong("day_${key}_down", 0L),
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayKey(timeMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timeMs))

    private fun prune(p: SharedPreferences) {
        val todayKey = dayKey(System.currentTimeMillis())
        if (p.getString("last_prune", null) == todayKey) return

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -KEEP_DAYS)
        val cutoff = dayKey(cal.timeInMillis)

        val stale = p.all.keys.filter { key ->
            key.startsWith("day_") && key.length >= 12 &&
                key.substring(4, 12) < cutoff
        }
        val e = p.edit()
        stale.forEach { e.remove(it) }
        e.putString("last_prune", todayKey)
        e.apply()
    }
}
