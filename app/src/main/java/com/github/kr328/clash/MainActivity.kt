package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.store.TrafficHistory
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.NodeGeo
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    private var lastTrafficTotal: Long = -1L
    private var lastTrafficAt: Long = 0L
    override suspend fun main() {
        val design = MainDesign(this)

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
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings -> {
                            startActivity(SettingsActivity::class.intent)
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                        MainDesign.Request.OpenStats -> {
                            startActivity(TrafficStatsActivity::class.intent)
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                        MainDesign.Request.OpenHub -> {
                            startActivity(HubActivity::class.intent)
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                        MainDesign.Request.HealthCheck -> {
                            if (clashRunning) {
                                withClash {
                                    val names = queryProxyGroupNames(excludeNotSelectable = false)
                                    var checked = 0
                                    for (name in names) {
                                        if (checked >= 6) break
                                        val group = queryProxyGroup(name, ProxySort.Default)
                                        if (group.type.equals("Selector", ignoreCase = true)) {
                                            healthCheck(name)
                                            checked++
                                        }
                                    }
                                }
                                kotlinx.coroutines.delay(1500)
                                design.fetchNode()
                            }
                        }
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        if (clashRunning && uiStore.clashStartedAt == 0L) {
            // The start event was missed (e.g. the app process restarted
            // while the service kept running): count from first sight.
            uiStore.clashStartedAt = System.currentTimeMillis()
        }

        val state = withClash {
            queryTunnelState()
        }

        setMode(state.mode)
        fetchNode()
        if (clashRunning) fetchTraffic()
    }

    private suspend fun MainDesign.fetchNode() {
        if (!clashRunning) {
            setNode(null, null, 0, running = false)
            return
        }

        val node: Proxy? = withClash {
            var found: Proxy? = null
            val names = queryProxyGroupNames(excludeNotSelectable = false)
            var queried = 0
            for (name in names) {
                if (found != null || queried >= 6) break
                val group = queryProxyGroup(name, ProxySort.Default)
                queried++
                if (!group.type.equals("Selector", ignoreCase = true)) continue
                val now = group.proxies.firstOrNull { it.name == group.now } ?: continue
                found = if (now.isGroup) {
                    val sub = queryProxyGroup(now.name, ProxySort.Default)
                    sub.proxies.firstOrNull { it.name == sub.now }
                } else {
                    now
                }
            }
            found
        }

        setNode(NodeGeo.resolve(node?.name), node?.name, node?.delay ?: 0, running = true)
    }

    private suspend fun MainDesign.fetchTraffic() {
        // The remote manager exposes no instantaneous-rate call, so the
        // live rates are derived from the session totals sampled 1s apart.
        val total = withClash { queryTrafficTotal() }
        val nowMs = System.currentTimeMillis()

        val upBytes = unpackTraffic(total ushr 32)
        val downBytes = unpackTraffic(total and 0xFFFFFFFFL)

        var upRate = 0L
        var downRate = 0L
        if (lastTrafficTotal >= 0L && nowMs > lastTrafficAt) {
            val dtSec = (nowMs - lastTrafficAt) / 1000.0
            val lastUp = unpackTraffic(lastTrafficTotal ushr 32)
            val lastDown = unpackTraffic(lastTrafficTotal and 0xFFFFFFFFL)
            if (upBytes >= lastUp) upRate = ((upBytes - lastUp) / dtSec).toLong()
            if (downBytes >= lastDown) downRate = ((downBytes - lastDown) / dtSec).toLong()
        }
        lastTrafficTotal = total
        lastTrafficAt = nowMs

        TrafficHistory.record(this@MainActivity, upBytes, downBytes)

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

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }
}
