package io.github.jackwener.dsh

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jackwener.dsh.core.capability.AgentScreen
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.platform.Notifications
import io.github.jackwener.dsh.core.trigger.TriggerEvents
import io.github.jackwener.dsh.core.trigger.TriggerHub
import io.github.jackwener.dsh.service.FloatingBallService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DshApp : Application() {
    @Inject lateinit var triggers: dagger.Lazy<TriggerHub>
    @Inject lateinit var settings: dagger.Lazy<SettingsRepository>
    @Inject lateinit var secrets: dagger.Lazy<io.github.jackwener.dsh.core.data.SecretStore>
    @Inject lateinit var agentScreen: dagger.Lazy<AgentScreen>

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return
        Notifications.createChannels(this)
        importDebugKey()
        TriggerEvents.init(this)
        // Startup work must never crash the process (a bad stored rule used to kill every app start).
        val guard = CoroutineExceptionHandler { _, e -> android.util.Log.e("DshApp", "startup task failed", e) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + guard).launch {
            // Import :engine crash records (ApplicationExitInfo / tombstones) from earlier runs.
            runCatching { io.github.jackwener.dsh.core.engine.EngineDiagnostics.collectExitInfo(this@DshApp) }
            runCatching { triggers.get().sync() }.onFailure { android.util.Log.e("DshApp", "automation sync failed", it) }
            if (settings.get().current().floatingBall) runCatching { FloatingBallService.setEnabled(this@DshApp, true) }
            runCatching { agentScreen.get().restoreLeftoverMutes() }
        }
    }

    /** Debug builds only: device tests provision the key with `run-as <pkg> sh -c 'cat > files/debug.key'`. */
    private fun importDebugKey() {
        if (!BuildConfig.DEBUG) return
        val f = java.io.File(filesDir, "debug.key")
        if (!f.exists()) return
        f.readText().trim().takeIf { it.isNotEmpty() }?.let { secrets.get().write(it) }
        f.delete()
    }

    private fun isMainProcess() = getProcessName() == packageName
}
