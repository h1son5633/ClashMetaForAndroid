package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.TrafficStatsDesign
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

/**
 * Statistics page backed by the core's real counters: traffic used
 * this session (queryTrafficTotal), live rates derived from those
 * totals sampled once per second, and the connection duration
 * tracked from the clash start event.
 */
class TrafficStatsActivity : BaseActivity<TrafficStatsDesign>() {
    private var lastTotal: Pair<Long, Long>? = null
    override suspend fun main() {
        val design = TrafficStatsDesign(this)
        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart -> {
                            uiStore.clashStartedAt = System.currentTimeMillis()
                            design.fetch()
                        }
                        Event.ClashStop -> {
                            uiStore.clashStartedAt = 0L
                            design.fetch()
                        }
                        Event.ActivityStart, Event.ServiceRecreated,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        TrafficStatsDesign.Request.OpenHome ->
                            startActivity(MainActivity::class.intent)
                        TrafficStatsDesign.Request.OpenHub ->
                            startActivity(HubActivity::class.intent)
                        TrafficStatsDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetch()
                    }
                }
            }
        }
    }

    private suspend fun TrafficStatsDesign.fetch() {
        if (!clashRunning) {
            lastTotal = null
            setTotals("0 Bytes", "0 Bytes", "0 Bytes")
            clearLive()
            setDuration("00:00:00")
            return
        }

        if (uiStore.clashStartedAt == 0L) {
            uiStore.clashStartedAt = System.currentTimeMillis()
        }

        val total = withClash { queryTrafficTotal() }

        setTotals(total.trafficUpload(), total.trafficDownload(), total.trafficTotal())

        // The remote manager exposes no instantaneous-rate call, so the
        // live rates are derived from the session totals sampled 1s apart.
        val nowMs = System.currentTimeMillis()
        val upBytes = unpackTraffic(total ushr 32)
        val downBytes = unpackTraffic(total and 0xFFFFFFFFL)

        var upRate = 0L
        var downRate = 0L
        val last = lastTotal
        if (last != null && nowMs > last.second) {
            val dtSec = (nowMs - last.second) / 1000.0
            val lastUp = unpackTraffic(last.first ushr 32)
            val lastDown = unpackTraffic(last.first and 0xFFFFFFFFL)
            if (upBytes >= lastUp) upRate = ((upBytes - lastUp) / dtSec).toLong()
            if (downBytes >= lastDown) downRate = ((downBytes - lastDown) / dtSec).toLong()
        }
        lastTotal = total to nowMs

        setSpeeds(formatBytesPerSec(upRate), formatBytesPerSec(downRate))
        pushTrafficSample(upRate, downRate)

        val startedAt = uiStore.clashStartedAt
        if (startedAt > 0L) {
            setDuration(formatDuration(System.currentTimeMillis() - startedAt))
        }
    }

    private fun formatBytesPerSec(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024L * 1024L ->
                String.format("%.1f MiB/s", bytesPerSec / 1024.0 / 1024.0)
            bytesPerSec >= 1024L ->
                String.format("%.1f KiB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun unpackTraffic(packed: Long): Long {
        val type = (packed ushr 30) and 0x3L
        val data = packed and 0x3FFFFFFFL
        return when (type) {
            0L -> data
            1L -> data * 1024L
            2L -> data * 1024L * 1024L
            else -> data * 1024L * 1024L * 1024L
        }
    }

    private fun formatDuration(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}
