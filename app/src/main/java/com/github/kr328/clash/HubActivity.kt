package com.github.kr328.clash

import android.view.View
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.HubDesign
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

/**
 * Hosts the proxy and profiles pages in one screen behind a
 * segmented switch (bottom-nav hub tab). The request handling below
 * is the ProxyActivity / ProfilesActivity logic carried over
 * unchanged; only the hosting and the tab switching are new.
 */
class HubActivity : BaseActivity<HubDesign>() {
    private var profilesDesign: ProfilesDesign? = null

    override suspend fun main() {
        val hub = HubDesign(this)
        setContentDesign(hub)

        val profiles = ProfilesDesign(this)
        profilesDesign = profiles
        hub.attachDesign(profiles.root)

        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)

        val proxy = ProxyDesign(this, mode, names, uiStore)
        hub.attachDesign(proxy.root)

        var showingProxy = true
        hub.showContent(proxy.root, profiles.root, proxy = true)

        proxy.requests.send(ProxyDesign.Request.ReloadAll)
        profiles.fetch()

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded -> {
                            val newNames = withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            }
                            if (newNames != names) {
                                startActivity(HubActivity::class.intent)
                                finish()
                            }
                            profiles.fetch()
                        }
                        Event.ActivityStart, Event.ProfileChanged -> profiles.fetch()
                        else -> Unit
                    }
                }
                hub.requests.onReceive {
                    when (it) {
                        HubDesign.Request.ShowProxy -> {
                            showingProxy = true
                            hub.showContent(proxy.root, profiles.root, proxy = true)
                        }
                        HubDesign.Request.ShowProfiles -> {
                            showingProxy = false
                            hub.showContent(proxy.root, profiles.root, proxy = false)
                        }
                        HubDesign.Request.OpenHome -> {
                            startActivity(MainActivity::class.intent)
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                        HubDesign.Request.OpenStats -> {
                            startActivity(TrafficStatsActivity::class.intent)
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                        HubDesign.Request.OpenSettings -> {
                            startActivity(SettingsActivity::class.intent)
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                    }
                }
                proxy.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(HubActivity::class.intent)
                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                proxy.requests.trySend(ProxyDesign.Request.Reload(idx))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }
                                val state = states[it.index]
                                state.now = group.now
                                proxy.updateGroup(
                                    it.index,
                                    group.proxies,
                                    group.type == "Selector",
                                    state,
                                    unorderedStates
                                )
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            withClash {
                                patchSelector(names[it.index], it.name)
                                states[it.index].now = it.name
                            }
                            proxy.requestRedrawVisible()
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                withClash {
                                    healthCheck(names[it.index])
                                }
                                proxy.requests.send(ProxyDesign.Request.Reload(it.index))
                            }
                        }
                        is ProxyDesign.Request.PatchMode -> {
                            proxy.showModeSwitchTips()
                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)
                                o.mode = it.mode
                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                    }
                }
                profiles.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create ->
                            startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.UpdateAll ->
                            withProfile {
                                try {
                                    queryAll().forEach { p ->
                                        if (p.imported && p.type != Profile.Type.File)
                                            update(p.uuid)
                                    }
                                } finally {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        profiles.finishUpdateAll()
                                    }
                                }
                            }
                        is ProfilesDesign.Request.Update ->
                            withProfile { update(it.profile.uuid) }
                        is ProfilesDesign.Request.Delete ->
                            withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit ->
                            startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> {
                            withProfile {
                                if (it.profile.imported)
                                    setActive(it.profile)
                                else
                                    profiles.requestSave(it.profile)
                            }
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }
                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                        is ProfilesDesign.Request.ShareQrCode ->
                            profiles.showProfileQrCode(it.profile)
                    }
                }
                if (activityStarted) {
                    ticker.onReceive {
                        profiles.updateElapsed()
                    }
                }
            }
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            patchProfiles(queryAll())
        }
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if (uuid == null) return
        launch {
            var name: String? = null
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            profilesDesign?.showToast(
                getString(DesignR.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
        }
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if (uuid == null) return
        launch {
            var name: String? = null
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            profilesDesign?.showToast(
                getString(DesignR.string.toast_profile_updated_failed, name, reason),
                ToastDuration.Long
            ) {
                setAction(DesignR.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
        }
    }
}
