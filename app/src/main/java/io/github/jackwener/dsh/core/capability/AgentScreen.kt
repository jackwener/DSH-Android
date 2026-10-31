package io.github.jackwener.dsh.core.capability

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.service.DshAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** One task as the shell service reports it (IShellService.tasks). */
data class TaskRow(val taskId: Int, val displayId: Int, val packageName: String, val topActivity: String, val standard: Boolean, val visible: Boolean?) {
    companion object {
        fun parse(raw: String?): List<TaskRow> = raw.orEmpty().lineSequence().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 6) return@mapNotNull null
            TaskRow(p[0].toIntOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: return@mapNotNull null, p[2], p[3], p[4] == "1",
                when (p[5]) { "1" -> true; "0" -> false; else -> null })
        }.toList()

        /** [pkg] is what the user is looking at on the main screen (a visible task, or the top one when visibility is unknown). */
        fun inUseOnMain(rows: List<TaskRow>, pkg: String): Boolean =
            rows.filter { it.displayId == Display.DEFAULT_DISPLAY }.withIndex().any { (i, t) -> t.packageName == pkg && (t.visible ?: (i == 0)) }

        /**
         * The task an agent action opened on the user's screen: the main screen's top task changed
         * since [topBefore], is an ordinary app (not home/recents, not [excluded]) and did not come
         * from the agent display by a takeover ([handedOver]). Null when nothing spilled over.
         */
        fun spilled(rows: List<TaskRow>, topBefore: Int?, excluded: Set<String>, handedOver: Int?): TaskRow? {
            val top = rows.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY } ?: return null
            if (top.taskId == topBefore || top.taskId == handedOver || !top.standard || top.packageName.isEmpty() || top.packageName in excluded) return null
            return top
        }

        /** "Open with…" / share pickers: left on the main screen for the user to choose. */
        fun isChooser(t: TaskRow) = t.packageName == "android" || "intentresolver" in t.packageName ||
            "Resolver" in t.topActivity || "Chooser" in t.topActivity
    }
}

/** Where a screen/app call acts: the session's agent display, or the user's screen ([note] says why). */
data class ScreenTarget(val displayId: Int, val agent: Boolean, val note: String? = null)

/**
 * The agent's own screen (docs/AGENT-SCREEN-V2.md §1): one virtual display per conversation,
 * created through Shizuku on the first app_launch / app_open_link, released after 10 idle minutes
 * (and with the shell service, i.e. when DSH exits). Apps opened there are muted until they leave
 * it or the user opens them on the main screen. Without Shizuku, on Android 10 or with the setting
 * off, everything stays on the main screen.
 */
@Singleton
class AgentScreen @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuShell,
    private val settings: SettingsRepository,
) {
    private class Slot(val displayId: Int) {
        @Volatile var lastUsed = SystemClock.elapsedRealtime()
        /** Waits that need the display kept (a takeover card): it is not released as idle meanwhile. */
        val holds = AtomicInteger()
        val muted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** The task given to the user (hand_to_user / 我来接管), while it is on the main screen. */
        @Volatile var handedOver: TaskRow? = null
        /** The user took the app over from the preview; agent-screen actions stop until they speak. */
        @Volatile var userTookOver = false
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _displays = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Conversation (root session id) → its agent display, for the live preview. */
    val displays: StateFlow<Map<String, Int>> = _displays.asStateFlow()
    /** Set while some app may be muted, so a DSH killed meanwhile gives the sound back on its next start. */
    private val prefs by lazy { context.getSharedPreferences("agent_screen", Context.MODE_PRIVATE) }

    init {
        scope.launch {
            while (true) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                slots.entries.filter { now - it.value.lastUsed > IDLE_MS && it.value.holds.get() == 0 }.forEach { release(it.key) }
                unmuteOnMain()
            }
        }
    }

    /** Why the latest attempt to create an agent screen failed ("SecurityException: …"); null once one was created. For Doctor and diagnostics. */
    @Volatile var lastCreateError: String? = null
        private set

    private fun key(sessionId: String) = sessionId.substringBefore('/')
    private fun alive(displayId: Int) = context.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null

    /** Why this call cannot use an agent screen (model-facing), or null when it can. */
    suspend fun unavailableReason(): String? = when {
        Build.VERSION.SDK_INT < 30 -> "the agent screen needs Android 11 or newer"
        !settings.current().agentScreen -> "the user turned off \"operate apps on the agent screen\" in DSH settings"
        !shizuku.available -> shizuku.access.state.value.reason
        else -> null
    }

    /** The session's live agent display, or null (a display lost with a Shizuku restart is dropped here). */
    fun current(sessionId: String): Int? {
        val k = key(sessionId)
        val s = slots[k] ?: return null
        if (alive(s.displayId)) return s.displayId
        slots.remove(k, s); publish()
        return null
    }

    /**
     * Resolves a tool's `screen` argument. [create]: app_launch / app_open_link may open the agent
     * screen; reads and gestures only use one that exists.
     */
    suspend fun target(sessionId: String, screen: String?, create: Boolean = false): ScreenTarget {
        if (screen == "main") return ScreenTarget(Display.DEFAULT_DISPLAY, false)
        // Turning the setting off also ends the agent screens that are open.
        if (current(sessionId) != null && !settings.current().agentScreen) release(sessionId)
        current(sessionId)?.let { touch(sessionId); return ScreenTarget(it, true) }
        if (!create) return ScreenTarget(Display.DEFAULT_DISPLAY, false,
            if (screen == "agent") "There is no agent screen in this conversation yet (app_launch / app_open_link open one); this is the user's screen." else null)
        val why = unavailableReason()
            ?: return ensure(sessionId)?.let { ScreenTarget(it, true) }
                ?: ScreenTarget(Display.DEFAULT_DISPLAY, false, failureNote(lastCreateError))
        return ScreenTarget(Display.DEFAULT_DISPLAY, false, "Working on the user's own screen ($why).")
    }

    private suspend fun ensure(sessionId: String): Int? = mutex.withLock {
        current(sessionId)?.let { return it }
        val m = DisplayMetrics().also {
            @Suppress("DEPRECATION") context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val created = runCatching {
            withContext(Dispatchers.IO) {
                val svc = shizuku.service()
                val id = svc.createDisplay(m.widthPixels, m.heightPixels, m.densityDpi)
                // An older service process (before lastDisplayError) answers without a reason.
                id to if (id < 0) runCatching { svc.lastDisplayError() }.getOrNull() else null
            }
        }.onFailure { Log.w(TAG, "createDisplay failed", it) }
        val id = created.getOrNull()?.first?.takeIf { it >= 0 }
        if (id == null) {
            lastCreateError = createFailure(created.getOrNull()?.second, created.exceptionOrNull())
            Log.w(TAG, "agent screen not created: $lastCreateError")
            return null
        }
        lastCreateError = null
        slots[key(sessionId)] = Slot(id)
        publish()
        id
    }

    fun touch(sessionId: String) { slots[key(sessionId)]?.lastUsed = SystemClock.elapsedRealtime() }

    /** Runs [block] (a wait for the user) with the session's agent screen kept from idle release. */
    suspend fun <T> holding(sessionId: String, block: suspend () -> T): T {
        val s = slots[key(sessionId)] ?: return block()
        s.holds.incrementAndGet()
        try {
            return block()
        } finally {
            s.holds.decrementAndGet()
            s.lastUsed = SystemClock.elapsedRealtime()
        }
    }

    suspend fun release(sessionId: String) {
        val s = slots.remove(key(sessionId)) ?: return
        publish()
        s.muted.toList().forEach { unmute(s, it) }
        runCatching { withContext(Dispatchers.IO) { shizuku.service().releaseDisplay(s.displayId) } }.onFailure { Log.w(TAG, "release failed", it) }
    }

    private fun publish() = _displays.update { slots.mapValues { it.value.displayId } }

    // ---- sound ---------------------------------------------------------------------------------

    /**
     * Mutes [pkg] while it is on the session's agent screen (no-op when already muted). PLAY_AUDIO is
     * per app, so an app the user has open on the main screen is left alone.
     */
    suspend fun mute(sessionId: String, pkg: String?) {
        val s = slots[key(sessionId)] ?: return
        if (pkg.isNullOrEmpty() || pkg == context.packageName || pkg in s.muted || inUseOnMain(pkg)) return
        prefs.edit().putBoolean(MUTE_PENDING, true).apply()
        if (runCatching { withContext(Dispatchers.IO) { shizuku.service().setAudioMuted(pkg, true) } }.getOrDefault(false)) s.muted += pkg
    }

    /** The sound comes back once no conversation's agent screen holds [pkg] muted. */
    private suspend fun unmute(s: Slot, pkg: String) {
        if (!s.muted.remove(pkg) || slots.values.any { pkg in it.muted }) return
        val ok = runCatching { withContext(Dispatchers.IO) { shizuku.service().setAudioMuted(pkg, false) } }.getOrDefault(false)
        if (ok && slots.values.all { it.muted.isEmpty() }) prefs.edit().remove(MUTE_PENDING).apply()
    }

    /** Muted apps the user opened on the main screen get their sound back. */
    private suspend fun unmuteOnMain() {
        if (slots.values.all { it.muted.isEmpty() }) return
        val rows = tasks()
        slots.values.forEach { s -> s.muted.filter { TaskRow.inUseOnMain(rows, it) }.forEach { unmute(s, it) } }
    }

    /** A previous DSH process ended with apps muted (killed before it could restore them): restore them now. */
    suspend fun restoreLeftoverMutes() {
        if (!prefs.getBoolean(MUTE_PENDING, false) || slots.isNotEmpty() || !shizuku.available) return
        runCatching { withContext(Dispatchers.IO) { shizuku.service().restoreAudio() } }
            .onSuccess { prefs.edit().remove(MUTE_PENDING).apply() }.onFailure { Log.w(TAG, "restoring muted apps failed", it) }
    }

    // ---- tasks ---------------------------------------------------------------------------------

    suspend fun tasks(): List<TaskRow> = runCatching { withContext(Dispatchers.IO) { TaskRow.parse(shizuku.service().tasks()) } }.getOrDefault(emptyList())

    suspend fun inUseOnMain(pkg: String): Boolean = TaskRow.inUseOnMain(tasks(), pkg)

    private suspend fun move(taskId: Int, displayId: Int) = runCatching { withContext(Dispatchers.IO) { shizuku.service().moveTask(taskId, displayId) } }.getOrDefault(false)

    /** Moves the agent screen's top task (login and payment pages often open their own task) to the user's screen, with sound. */
    suspend fun handToUser(sessionId: String, byUser: Boolean = false): TaskRow? {
        val s = slots[key(sessionId)] ?: return null
        val top = tasks().firstOrNull { it.displayId == s.displayId } ?: return null
        // Set first, so the pull-back watcher does not take the arriving task for a spill.
        s.handedOver = top
        if (!move(top.taskId, Display.DEFAULT_DISPLAY)) { s.handedOver = null; return null }
        if (byUser) s.userTookOver = true
        unmute(s, top.packageName)
        return top
    }

    /** Puts the handed-over task back on the agent screen (muted again). False when it is gone or could not move. */
    suspend fun takeBack(sessionId: String): Boolean {
        val s = slots[key(sessionId)] ?: return false
        val t = s.handedOver ?: return false
        s.handedOver = null
        s.lastUsed = SystemClock.elapsedRealtime()
        if (tasks().none { it.taskId == t.taskId } || !move(t.taskId, s.displayId)) return false
        mute(sessionId, t.packageName)
        return true
    }

    /** The task stays with the user (我自己做完了): forget it without moving. */
    fun leaveWithUser(sessionId: String) { slots[key(sessionId)]?.handedOver = null }

    fun userTookOver(sessionId: String) = slots[key(sessionId)]?.userTookOver == true

    /** The user spoke again: agent-screen actions may resume, and an app they took over stays theirs. */
    fun clearUserTakeover(sessionId: String) {
        val s = slots[key(sessionId)] ?: return
        if (s.userTookOver) s.handedOver = null
        s.userTookOver = false
    }

    /** Main-screen state before an agent-screen action, for [pullBack]. */
    class Watch internal constructor(internal val topBefore: Int?, internal val since: Long)

    suspend fun watch(sessionId: String): Watch? {
        if (current(sessionId) == null) return null
        return Watch(tasks().firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.taskId, SystemClock.elapsedRealtime())
    }

    /**
     * An app the action opened on the user's screen (a payment app opened from a button) is moved back
     * to the agent screen; only moved, never reopened, so the app that opened it stays its caller.
     * A system chooser stays with the user. Checked now and, in the background, until 3 s after the action.
     * Returns a model-facing note, or null.
     */
    suspend fun pullBack(sessionId: String, w: Watch?): String? {
        w ?: return null
        check(sessionId, w)?.let { return it }
        scope.launch {
            while (SystemClock.elapsedRealtime() - w.since < SPILL_WINDOW_MS) {
                delay(500)
                if (check(sessionId, w) != null) break
            }
        }
        return null
    }

    private suspend fun check(sessionId: String, w: Watch): String? {
        val s = slots[key(sessionId)] ?: return null
        // The user clicked something on their own screen meanwhile: what opened there is theirs.
        if (DshAccessibilityService.lastMainClickAt > w.since) return null
        val t = TaskRow.spilled(tasks(), w.topBefore, setOf(context.packageName, SYSTEM_UI), s.handedOver?.taskId) ?: return null
        val label = label(t.packageName)
        if (TaskRow.isChooser(t)) return "A system chooser (\"open with…\") appeared on the user's screen; ask the user to pick an app there."
        if (!move(t.taskId, s.displayId)) return null
        mute(sessionId, t.packageName)
        return "$label (${t.packageName}) opened on the user's screen after this action and was moved back to the agent screen."
    }

    fun label(pkg: String): String = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    // ---- frames --------------------------------------------------------------------------------

    /** Latest frame as JPEG ([maxWidth] 0 = full size), or null. */
    suspend fun frame(displayId: Int, maxWidth: Int = 0): ByteArray? =
        runCatching { withContext(Dispatchers.IO) { shizuku.service().captureDisplay(displayId, maxWidth) } }.getOrNull()

    /** An app window is there but two frames 300 ms apart are all black: a protected (FLAG_SECURE) page. */
    suspend fun showsProtected(displayId: Int): Boolean {
        if (runCatching { DshAccessibilityService.instance?.snapshot(displayId)?.packageName }.getOrNull() == null) return false
        suspend fun blank() = runCatching { withContext(Dispatchers.IO) { shizuku.service().frameBlank(displayId) } }.getOrDefault(-1) == 1
        if (!blank()) return false
        delay(300)
        return blank()
    }

    internal companion object {
        const val TAG = "AgentScreen"
        const val IDLE_MS = 10 * 60_000L
        const val TICK_MS = 5_000L
        const val MUTE_PENDING = "mute_pending"
        const val SPILL_WINDOW_MS = 3_000L
        const val SYSTEM_UI = "com.android.systemui"

        /** The reason for the app (Doctor, diagnostics, logs): the service's own, else the call's exception. */
        fun createFailure(serviceReason: String?, callError: Throwable?): String = when {
            !serviceReason.isNullOrBlank() -> serviceReason
            callError != null -> io.github.jackwener.dsh.core.platform.ShellIdentity.describe(callError)
            else -> "the Shizuku service returned no display and no reason"
        }

        /** Model-facing note when the call falls back to the user's screen. */
        fun failureNote(reason: String?): String = "Working on the user's screen: the agent screen could not be created on this device" +
            (reason?.let { " (后台屏创建失败：$it). Mention this reason to the user once if they wonder why their own screen is used" } ?: "") + "."
    }
}
