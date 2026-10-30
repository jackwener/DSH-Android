package io.github.jackwener.dsh.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.MainActivity
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.platform.Notifications
import io.github.jackwener.dsh.core.session.RowStatus
import io.github.jackwener.dsh.core.session.SessionStore
import io.github.jackwener.dsh.feature.quick.QuickActivity
import io.github.jackwener.dsh.feature.quick.ScreenContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * Edge-docked floating button that opens the quick panel from any app. Hidden while our own UI is in front.
 * Pulses while a turn runs and turns orange when a conversation waits for the user (tap opens it);
 * a double tap sends the current screen to the quick panel.
 */
@AndroidEntryPoint
class FloatingBallService : Service() {
    @Inject lateinit var store: SessionStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: ImageView? = null
    private var pulse: ObjectAnimator? = null
    /** The conversation waiting for the user (approval, question, permission or takeover card), if any. */
    private var needsYou: String? = null
    private val appInFront = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { view?.visibility = android.view.View.GONE }
        override fun onStop(owner: LifecycleOwner) { view?.visibility = android.view.View.VISIBLE }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        startForeground(Notifications.ID_BALL, NotificationCompat.Builder(this, Notifications.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle(getString(R.string.ball_running)).setOngoing(true).setSilent(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val wm = getSystemService(WindowManager::class.java)
        val size = (48 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        val screenH = screenSize(wm).second
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenSize(wm).first - size - margin; y = screenH / 3
        }
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_ball)
            alpha = 0.85f
            contentDescription = getString(R.string.app_name)
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var lastTap = 0L
        val singleTap = Runnable { open() }
        val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> { lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt(); wm.updateViewLayout(v, lp); true }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < 12 && abs(e.rawY - downY) < 12) {
                        // A single tap waits out the double-tap window; a second tap inside it sends the screen instead.
                        if (e.eventTime - lastTap < doubleTapMs) { v.removeCallbacks(singleTap); lastTap = 0; sendScreen() }
                        else { lastTap = e.eventTime; v.postDelayed(singleTap, doubleTapMs) }
                    }
                    else {
                        // Re-read the size on every drop: rotation and fold changes move the far edge.
                        val screenW = screenSize(wm).first
                        lp.x = if (lp.x + size / 2 < screenW / 2) margin else screenW - size - margin; wm.updateViewLayout(v, lp)
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(v, lp)
        view = v
        ProcessLifecycleOwner.get().lifecycle.addObserver(appInFront)
        scope.launch {
            store.status.map { st -> st.entries.firstOrNull { it.value == RowStatus.Approval || it.value == RowStatus.Question }?.key to st.values.any { it == RowStatus.Running } }
                .distinctUntilChanged().collect { (waiting, running) -> show(waiting, running) }
        }
    }

    private fun show(waiting: String?, running: Boolean) {
        val v = view ?: return
        needsYou = waiting
        v.setImageResource(when { waiting != null -> R.drawable.ic_ball_attention; running -> R.drawable.ic_ball_working; else -> R.drawable.ic_ball })
        v.contentDescription = getString(when { waiting != null -> R.string.ball_needs_you; running -> R.string.ball_working; else -> R.string.app_name })
        if (running && waiting == null) {
            if (pulse == null) pulse = ObjectAnimator.ofFloat(v, View.ALPHA, 0.85f, 0.45f).apply {
                duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        } else {
            pulse?.cancel(); pulse = null
            v.alpha = 0.85f
        }
    }

    /** Captures the main screen (screenshot without the ball, plus its text) and opens the quick panel with it attached. */
    private fun sendScreen() {
        val v = view ?: return
        val a11y = DshAccessibilityService.instance
        if (a11y == null) {
            Toast.makeText(this, R.string.ball_screen_needs_a11y, Toast.LENGTH_SHORT).show()
            return open()
        }
        scope.launch {
            v.visibility = View.INVISIBLE
            try {
                delay(SHOT_HIDE_MS)
                ScreenContext.captureFromAccessibility()
                // Android 10 has no accessibility screenshot, and secure pages refuse one: the screen text still goes along.
                ScreenContext.attachShot(runCatching { a11y.capture().bitmap }.getOrNull())
            } finally {
                v.visibility = View.VISIBLE
            }
            startActivity(Intent(this@FloatingBallService, QuickActivity::class.java).putExtra(QuickActivity.EXTRA_SCREEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Full window size (displayMetrics excludes insets on some devices, which pushed the ball half off-screen). */
    private fun screenSize(wm: WindowManager): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = view ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val wm = getSystemService(WindowManager::class.java)
        val (w, h) = screenSize(wm)
        val margin = (8 * resources.displayMetrics.density).toInt()
        lp.x = if (lp.x + lp.width / 2 < w / 2) margin else w - lp.width - margin
        lp.y = lp.y.coerceIn(0, (h - lp.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun open() {
        val waiting = needsYou
        if (waiting != null) startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, waiting).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else startActivity(Intent(this, QuickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDestroy() {
        scope.cancel()
        pulse?.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appInFront)
        view?.let { getSystemService(WindowManager::class.java).removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val SHOT_HIDE_MS = 120L

        fun setEnabled(context: Context, on: Boolean) {
            val i = Intent(context, FloatingBallService::class.java)
            if (on && Settings.canDrawOverlays(context)) context.startForegroundService(i) else context.stopService(i)
        }
    }
}
