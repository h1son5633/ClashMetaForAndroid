package com.github.kr328.clash

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home-screen widget (v8): white card mirroring the iOS Clash widget —
 * group/node line, session traffic + live rates, Rule/Global/Direct
 * segmented switch and a power button.
 *
 * Data and actions deliberately reuse the exact paths the app itself uses:
 *  - running state: [StatusClient.currentProfile] (same as TileService)
 *  - start/stop: [startClashService] / [stopClashService] (same as TileService)
 *  - node/mode/traffic: [withClash] queries (same as MainActivity)
 *  - mode switch: session-override patch (same as ProxyActivity PatchMode)
 *  - refresh button: health check of Selector groups (same as MainActivity)
 *
 * Live rates are derived from queryTrafficTotal() samples 1s apart, exactly
 * like MainActivity.fetchTraffic; the sampling loop lives in this provider's
 * companion while the tunnel is up and the app process is alive.
 */
class ClashWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                appWidgetIds.forEach { updateWidget(context, it) }
            } finally {
                pending.finish()
            }
        }
        syncLoop(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE, ACTION_REFRESH, ACTION_SET_MODE -> {
                val widgetId = intent.getIntExtra(
                    EXTRA_WIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )

                val pending = goAsync()
                scope.launch {
                    try {
                        when (intent.action) {
                            ACTION_TOGGLE -> toggle(context)
                            ACTION_REFRESH -> healthCheckAll(context)
                            ACTION_SET_MODE -> setMode(
                                context,
                                intent.getStringExtra(EXTRA_MODE)
                            )
                        }
                    } finally {
                        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
                            updateWidget(context, widgetId)
                        else
                            updateAllWidgets(context)
                        syncLoop(context)
                        pending.finish()
                    }
                }
            }
            Intents.ACTION_CLASH_STARTED -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        updateAllWidgets(context)
                    } finally {
                        pending.finish()
                    }
                }
                syncLoop(context)
            }
            Intents.ACTION_CLASH_STOPPED -> {
                stopLoop()

                val pending = goAsync()
                scope.launch {
                    try {
                        updateAllWidgets(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDisabled(context: Context) {
        stopLoop()
    }

    private fun toggle(context: Context) {
        if (StatusClient(context).currentProfile() != null) {
            context.stopClashService()
        } else {
            val vpnRequest = context.startClashService()

            if (vpnRequest != null) {
                // VPN consent not granted yet: a broadcast receiver cannot
                // show the consent dialog, so hand over to the main UI,
                // whose own start flow (MainActivity.startClash) requests
                // it properly.
                context.startActivity(
                    Intent().setComponent(Components.MAIN_ACTIVITY)
                        .setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                )
            }
        }
    }

    private suspend fun setMode(context: Context, modeName: String?) {
        val mode = when (modeName) {
            EXTRA_MODE_RULE -> TunnelState.Mode.Rule
            EXTRA_MODE_GLOBAL -> TunnelState.Mode.Global
            EXTRA_MODE_DIRECT -> TunnelState.Mode.Direct
            else -> return
        }

        // Identical to ProxyActivity's ProxyDesign.Request.PatchMode handler.
        withClashBounded {
            val o = queryOverride(Clash.OverrideSlot.Session)

            o.mode = mode

            patchOverride(Clash.OverrideSlot.Session, o)
        }
    }

    private suspend fun healthCheckAll(context: Context) {
        if (StatusClient(context).currentProfile() == null) return

        // Identical selection logic to MainActivity's HealthCheck request.
        withClashBounded {
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

        delay(1500)
    }

    companion object {
        private const val ACTION_TOGGLE = "com.github.kr328.clash.widget.TOGGLE"
        private const val ACTION_REFRESH = "com.github.kr328.clash.widget.REFRESH"
        private const val ACTION_SET_MODE = "com.github.kr328.clash.widget.SET_MODE"

        private const val EXTRA_WIDGET_ID = "widget_id"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_MODE_RULE = "rule"
        private const val EXTRA_MODE_GLOBAL = "global"
        private const val EXTRA_MODE_DIRECT = "direct"

        private const val PREFS = "clash_widget_state"
        private const val KEY_GROUP = "group"
        private const val KEY_NODE = "node"
        private const val KEY_MODE = "mode"
        private const val KEY_LAST_TOTAL = "last_total"
        private const val KEY_LAST_AT = "last_at"

        private const val FETCH_TIMEOUT_MS = 5000L

        private val COLOR_SEGMENT_IDLE_TEXT = Color.rgb(0x3A, 0x3A, 0x3C)
        private val COLOR_POWER_OFF_ICON = Color.rgb(0x8A, 0x8A, 0x8E)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var loopJob: Job? = null

        /**
         * [withClash] waits for the remote service to bind; in a process
         * started only to deliver a widget broadcast nothing has bound it
         * yet (binding is normally driven by app visibility via
         * Remote.launch), so bind explicitly first and bound the whole call
         * with a timeout, falling back to no data on failure.
         */
        private suspend fun <T> withClashBounded(
            block: suspend IClashManager.() -> T
        ): T? {
            Remote.service.bind()

            return withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                withClash(block = block)
            }
        }

        private suspend fun updateWidget(context: Context, appWidgetId: Int) {
            val snapshot = fetchSnapshot(context)

            AppWidgetManager.getInstance(context)
                .updateAppWidget(appWidgetId, buildViews(context, appWidgetId, snapshot))
        }

        private suspend fun updateAllWidgets(context: Context) {
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, ClashWidgetProvider::class.java)
            )

            ids.forEach { updateWidget(context, it) }
        }

        private suspend fun fetchSnapshot(context: Context): Snapshot {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val profile = StatusClient(context).currentProfile()

            if (profile == null) {
                return Snapshot(
                    running = false,
                    profile = null,
                    group = null,
                    node = null,
                    mode = null,
                    totalText = null,
                    upRate = 0,
                    downRate = 0
                )
            }

            // Last known good values, shown if the remote query times out.
            var group: String? = prefs.getString(KEY_GROUP, null)
            var node: String? = prefs.getString(KEY_NODE, null)
            var mode: TunnelState.Mode? = prefs.getString(KEY_MODE, null)
                ?.let { runCatching { TunnelState.Mode.valueOf(it) }.getOrNull() }
            var totalText: String? = null
            var upRate = 0L
            var downRate = 0L

            val fetched = withClashBounded {
                val state = queryTunnelState()
                val total = queryTrafficTotal()

                // Same first-Selector-group walk as MainActivity.fetchNode,
                // additionally keeping the group name for the first line.
                var foundGroup: String? = null
                var foundNode: String? = null
                val names = queryProxyGroupNames(excludeNotSelectable = false)
                var queried = 0
                for (name in names) {
                    if (foundNode != null || queried >= 6) break
                    val g = queryProxyGroup(name, ProxySort.Default)
                    queried++
                    if (!g.type.equals("Selector", ignoreCase = true)) continue
                    val now = g.proxies.firstOrNull { it.name == g.now } ?: continue
                    val leaf = if (now.isGroup) {
                        val sub = queryProxyGroup(now.name, ProxySort.Default)
                        sub.proxies.firstOrNull { it.name == sub.now }
                    } else {
                        now
                    } ?: continue
                    foundGroup = name
                    foundNode = leaf.name
                }

                Fetched(state.mode, total, foundGroup, foundNode)
            }

            if (fetched != null) {
                mode = fetched.mode
                group = fetched.group
                node = fetched.node
                totalText =
                    "↓ ${fetched.total.trafficDownload()}   ↑ ${fetched.total.trafficUpload()}"

                // Rate derivation identical to MainActivity.fetchTraffic;
                // the previous sample survives in prefs so a rate can be
                // computed even across process restarts.
                val nowMs = System.currentTimeMillis()
                val lastTotal = prefs.getLong(KEY_LAST_TOTAL, -1L)
                val lastAt = prefs.getLong(KEY_LAST_AT, 0L)
                if (lastTotal >= 0L && nowMs > lastAt) {
                    val dtSec = (nowMs - lastAt) / 1000.0
                    val up = unpackTraffic(fetched.total ushr 32)
                    val lastUp = unpackTraffic(lastTotal ushr 32)
                    val down = unpackTraffic(fetched.total and 0xFFFFFFFFL)
                    val lastDown = unpackTraffic(lastTotal and 0xFFFFFFFFL)
                    if (up >= lastUp) upRate = ((up - lastUp) / dtSec).toLong()
                    if (down >= lastDown) downRate = ((down - lastDown) / dtSec).toLong()
                }

                prefs.edit()
                    .putString(KEY_GROUP, group)
                    .putString(KEY_NODE, node)
                    .putString(KEY_MODE, mode?.name)
                    .putLong(KEY_LAST_TOTAL, fetched.total)
                    .putLong(KEY_LAST_AT, nowMs)
                    .apply()
            }

            return Snapshot(true, profile, group, node, mode, totalText, upRate, downRate)
        }

        private fun buildViews(
            context: Context,
            appWidgetId: Int,
            snapshot: Snapshot
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_clash)

            if (snapshot.running) {
                views.setTextViewText(
                    R.id.widget_title,
                    listOfNotNull(snapshot.group, snapshot.node)
                        .joinToString(" · ")
                        .ifEmpty { snapshot.profile ?: "" }
                )
                views.setTextViewText(
                    R.id.widget_status,
                    context.getString(R.string.widget_connected, snapshot.profile ?: "")
                )
                views.setTextViewText(
                    R.id.widget_rates,
                    "↓ ${formatBytesPerSec(snapshot.downRate)}   " +
                        "↑ ${formatBytesPerSec(snapshot.upRate)}"
                )
                views.setTextViewText(R.id.widget_totals, snapshot.totalText ?: "")
            } else {
                views.setTextViewText(
                    R.id.widget_title,
                    context.getString(R.string.widget_name)
                )
                views.setTextViewText(
                    R.id.widget_status,
                    context.getString(R.string.widget_not_connected)
                )
                views.setTextViewText(R.id.widget_rates, "↓ —   ↑ —")
                views.setTextViewText(R.id.widget_totals, "")
            }

            setSegment(views, R.id.widget_mode_rule, snapshot.mode == TunnelState.Mode.Rule)
            setSegment(views, R.id.widget_mode_global, snapshot.mode == TunnelState.Mode.Global)
            setSegment(views, R.id.widget_mode_direct, snapshot.mode == TunnelState.Mode.Direct)

            views.setInt(
                R.id.widget_power,
                "setBackgroundResource",
                if (snapshot.running) R.drawable.widget_bg_power_on
                else R.drawable.widget_bg_power_off
            )
            views.setInt(
                R.id.widget_power,
                "setColorFilter",
                if (snapshot.running) Color.WHITE else COLOR_POWER_OFF_ICON
            )

            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    appWidgetId * 16,
                    Intent().setComponent(Components.MAIN_ACTIVITY)
                        .setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        ),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                actionIntent(context, appWidgetId, ACTION_REFRESH, 1)
            )
            views.setOnClickPendingIntent(
                R.id.widget_power,
                actionIntent(context, appWidgetId, ACTION_TOGGLE, 2)
            )
            views.setOnClickPendingIntent(
                R.id.widget_mode_rule,
                actionIntent(context, appWidgetId, ACTION_SET_MODE, 3, EXTRA_MODE_RULE)
            )
            views.setOnClickPendingIntent(
                R.id.widget_mode_global,
                actionIntent(context, appWidgetId, ACTION_SET_MODE, 4, EXTRA_MODE_GLOBAL)
            )
            views.setOnClickPendingIntent(
                R.id.widget_mode_direct,
                actionIntent(context, appWidgetId, ACTION_SET_MODE, 5, EXTRA_MODE_DIRECT)
            )

            return views
        }

        private fun setSegment(views: RemoteViews, viewId: Int, selected: Boolean) {
            views.setInt(
                viewId,
                "setBackgroundResource",
                if (selected) R.drawable.widget_bg_segment_selected
                else R.drawable.widget_bg_segment_idle
            )
            views.setTextColor(
                viewId,
                if (selected) Color.WHITE else COLOR_SEGMENT_IDLE_TEXT
            )
        }

        private fun actionIntent(
            context: Context,
            appWidgetId: Int,
            action: String,
            requestOffset: Int,
            mode: String? = null
        ): PendingIntent {
            val intent = Intent(context, ClashWidgetProvider::class.java)
                .setAction(action)
                .putExtra(EXTRA_WIDGET_ID, appWidgetId)

            if (mode != null)
                intent.putExtra(EXTRA_MODE, mode)

            return PendingIntent.getBroadcast(
                context,
                appWidgetId * 16 + requestOffset,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }

        // Copied from MainActivity.fetchTraffic: the remote manager exposes
        // the session total as a packed Long (unit tag in the top bits of
        // each 32-bit half).
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

        // Copied from MainActivity.formatBytesPerSec so widget rates read
        // exactly like the home page rates.
        private fun formatBytesPerSec(bytesPerSec: Long): String {
            return when {
                bytesPerSec >= 1024L * 1024L ->
                    String.format("%.1f MiB/s", bytesPerSec / 1024.0 / 1024.0)
                bytesPerSec >= 1024L ->
                    String.format("%.1f KiB/s", bytesPerSec / 1024.0)
                else -> "$bytesPerSec B/s"
            }
        }

        /**
         * 1s sampling loop for live rates, alive only while the tunnel is
         * up (mirrors the 1s tickers in MainActivity and in
         * DynamicNotificationModule). It lives in the app process: if the
         * process is killed the loop dies with it, and the next onUpdate,
         * tap or CLASH_STARTED broadcast restarts it via [syncLoop].
         */
        private fun syncLoop(context: Context) {
            val appContext = context.applicationContext

            if (StatusClient(appContext).currentProfile() == null) {
                stopLoop()
                return
            }

            if (loopJob?.isActive == true) return

            loopJob = scope.launch {
                while (isActive) {
                    delay(1000)

                    if (StatusClient(appContext).currentProfile() == null) {
                        // Tunnel went away without us seeing the broadcast.
                        updateAllWidgets(appContext)
                        break
                    }

                    updateAllWidgets(appContext)
                }
            }
        }

        private fun stopLoop() {
            loopJob?.cancel()
            loopJob = null
        }

        private data class Fetched(
            val mode: TunnelState.Mode,
            val total: Long,
            val group: String?,
            val node: String?
        )

        private data class Snapshot(
            val running: Boolean,
            val profile: String?,
            val group: String?,
            val node: String?,
            val mode: TunnelState.Mode?,
            val totalText: String?,
            val upRate: Long,
            val downRate: Long
        )
    }
}
