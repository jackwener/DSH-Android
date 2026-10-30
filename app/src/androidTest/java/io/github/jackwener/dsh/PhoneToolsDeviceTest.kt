package io.github.jackwener.dsh

import android.content.Intent
import android.app.UiAutomation
import android.provider.Settings
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.jackwener.dsh.core.capability.*
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.data.RuleEntity
import io.github.jackwener.dsh.service.DshAccessibilityService
import io.github.jackwener.dsh.testing.PhoneFixtureActivity
import io.github.jackwener.dsh.testing.PhoneTestEntryPoint
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real hub, accessibility service, gestures and bundled ML Kit on a connected device. */
@RunWith(AndroidJUnit4::class)
class PhoneToolsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, PhoneTestEntryPoint::class.java)
    private val hub get() = entry.capabilities()
    private fun text(r: ToolResult) = r.content.filterIsInstance<Content.Text>().joinToString("\n") { it.text }
    private fun summary(r: ToolResult) = Json.parseToJsonElement((r.content.first() as Content.Text).text).jsonObject
    private var settingsBefore: io.github.jackwener.dsh.core.data.Settings? = null

    @Before fun setup() = runBlocking {
        check(!BuildConfig.VERSION_NAME.contains("-local.")) {
            "Configured local daily builds require their own backup and exact-APK acceptance opt-in."
        }
        check(context.packageName.endsWith(".phonecheck")) { "Run with -Pdsh.debugSuffix=.phonecheck; never change an installed user's app settings." }
        settingsBefore = entry.settings().current()
        entry.settings().setFloatingBall(false)
        entry.settings().setAgentScreen(false)
        for (g in listOf(ToolGroup.Screen, ToolGroup.Apps)) entry.settings().setGroupEnabled(g, true)
        // Instrumentation restarts the target process. Some OEMs leave its previously bound
        // service in the crashed set; rebind only our test service, retaining all other services.
        if (DshAccessibilityService.instance == null) {
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                val component = "${context.packageName}/${DshAccessibilityService::class.java.name}"
                val resolver = context.contentResolver
                val others = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty().split(':').filter { it.isNotBlank() && it != component }
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                delay(250)
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + component).joinToString(":"))
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } finally { automation.dropShellPermissionIdentity() }
        }
        val until = SystemClock.elapsedRealtime() + 15_000
        while (DshAccessibilityService.instance == null && SystemClock.elapsedRealtime() < until) delay(200)
        assertNotNull("Enable the test package's accessibility service before running connected tests", DshAccessibilityService.instance)
        // MIUI additionally blocks the fixture's background activity start. Bring the test app
        // forward through the instrumentation shell before opening its non-exported fixture.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("am start -W -n ${context.packageName}/${MainActivity::class.java.name}").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText()
            }
        hub.onUserPrompt("")
        fixture("form")
    }

    @After fun restoreSettings() = runBlocking {
        PhoneFixtureActivity.current?.let { activity ->
            instrumentation.runOnMainSync { activity.finish() }
            withTimeout(5_000) { while (!activity.isDestroyed) delay(50) }
        }
        val before = settingsBefore ?: return@runBlocking
        val settings = entry.settings()
        val after = settings.current()
        if (before.floatingBall != after.floatingBall) settings.setFloatingBall(before.floatingBall)
        if (before.agentScreen != after.agentScreen) settings.setAgentScreen(before.agentScreen)
        for (g in ToolGroup.entries) if ((g in before.enabledGroups) != (g in after.enabledGroups))
            settings.setGroupEnabled(g, g in before.enabledGroups)
        assertEquals("User settings changed during device regression", before, settings.current())
    }

    internal suspend fun fixture(scenario: String, delayMs: Long = 900) {
        val previous = PhoneFixtureActivity.current
        // Keep MainActivity as the task root. Clearing it creates an empty-task transition
        // on MIUI; snapshots can briefly describe the launcher instead of the fixture.
        context.startActivity(Intent(context, PhoneFixtureActivity::class.java).putExtra("scenario", scenario).putExtra("delayMs", delayMs)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun isFocused(): Boolean {
            var focused = false
            // hasWindowFocus() calls Window.getDecorView(). Reading it from the test
            // thread can race onCreate/setContentView and install the decor twice.
            instrumentation.runOnMainSync {
                val activity = PhoneFixtureActivity.current
                focused = activity != null && activity !== previous && !activity.isDestroyed && activity.hasWindowFocus()
            }
            return focused
        }
        withTimeout(10_000) {
            while (!isFocused()) delay(50)
        }
        if (previous != null && !previous.isDestroyed) {
            instrumentation.runOnMainSync { previous.finish() }
            withTimeout(5_000) { while (!previous.isDestroyed) delay(50) }
        }
        // Finishing the covered Activity can itself trigger a MIUI task animation. Only
        // establish the starting frame after that mutation, with the header on screen.
        var stableSince = 0L
        var previousFrame = 0
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels
        withTimeout(10_000) {
            while (true) {
                val snap = DshAccessibilityService.instance!!.snapshot()
                val labels = snap.elements.filterNot { it.system }
                val signature = labels.hashCode()
                val header = labels.singleOrNull { it.text == "DSH native phone test fixture" }
                val ready = isFocused() &&
                    snap.packageName == context.packageName && header != null && header.x in 0 until width && header.y in 0 until height &&
                    labels.any { it.text == "Ready for native tools" } && labels.count { it.readable } >= 4
                if (!ready || signature != previousFrame) stableSince = SystemClock.elapsedRealtime()
                if (ready && SystemClock.elapsedRealtime() - stableSince >= 400) break
                previousFrame = signature
                delay(100)
            }
        }
        assertEquals("The native fixture did not reach the screen", context.packageName, DshAccessibilityService.instance!!.snapshot().packageName)
    }
    private suspend fun call(name: String, args: String = "{}", progress: (ToolResult) -> Unit = {}): ToolResult = hub.invoke(buildJsonObject {
        put("tool", name); put("sessionId", ""); put("callId", "device-test-${System.nanoTime()}")
        put("timeoutMs", 120_000)
        put("args", JsonObject(Json.parseToJsonElement(args).jsonObject + ("screen" to JsonPrimitive("main"))))
    }, progress)
    private suspend fun runPlan(commands: String, progress: (ToolResult) -> Unit = {}) = call("phone_run", """{"commands":$commands}""", progress)
    private fun record(name: String, data: JsonObject) {
        val filename = InstrumentationRegistry.getArguments().getString("resultFile") ?: "phone-test-results.jsonl"
        check(filename.matches(Regex("[a-zA-Z0-9._-]+")))
        File(context.filesDir, filename).appendText(buildJsonObject { put("test", name); put("data", data) }.toString() + "\n")
    }

    @Test fun sequenceUsesFreshTargetsAndReturnsCompletedSteps() = runBlocking {
        val r = runPlan("""[
            {"op":"type","target":{"resourceId":"phone_fixture_input"},"text":"Redmi native test"},
            {"op":"tap","target":{"text":"Read input"}},
            {"op":"assert","target":{"text":"Input: Redmi native test"}},
            {"op":"tap","target":{"text":"Count once"}},
            {"op":"assert","target":{"text":"Count 1"}}
        ]""")
        assertTrue(text(r), r.ok)
        assertEquals(5, summary(r)["completed"]!!.jsonArray.size)
        assertEquals(1, PhoneFixtureActivity.taps.get())
        record("sequence", summary(r))
    }

    @Test fun malformedLaterStepDoesNotExecuteEarlierStep() = runBlocking {
        val r = runPlan("""[{"op":"tap","target":{"text":"Count once"}},{"op":"key","key":"not-a-key"}]""")
        assertFalse(r.ok); assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun ambiguousTargetStopsWithPartialProgress() = runBlocking {
        val r = runPlan("""[{"op":"tap","target":{"text":"Count once"}},{"op":"tap","target":{"text":"Duplicate"}},{"op":"tap","target":{"text":"Count once"}}]""")
        assertFalse(r.ok); assertEquals(1, PhoneFixtureActivity.taps.get())
        assertEquals(1, summary(r)["completed"]!!.jsonArray.size)
        record("ambiguous_target", summary(r))
    }

    @Test fun unmetWaitStopsBeforeTheNextAction() = runBlocking {
        val r = runPlan("""[{"op":"wait","text":"never appears","timeoutMs":500},{"op":"tap","target":{"text":"Count once"}}]""")
        assertFalse(r.ok); assertEquals("condition_timeout", r.errorCode)
        assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun childCapabilityCannotBeBypassedByBatch() = runBlocking {
        entry.settings().setGroupEnabled(ToolGroup.Apps, false)
        try {
            val r = runPlan("""[{"op":"launch","package":"com.android.settings"},{"op":"tap","target":{"text":"Count once"}}]""")
            assertFalse(r.ok); assertEquals("not_authorized", r.errorCode)
            assertEquals(0, PhoneFixtureActivity.taps.get())
        } finally { entry.settings().setGroupEnabled(ToolGroup.Apps, true) }
    }

    @Test fun unattendedWhitelistAppliesToEveryChild() = runBlocking {
        val rule = entry.rules().insert(RuleEntity(specJson = """{"name":"test-only disabled rule","trigger":{"type":"screen","event":"unlock"},"action":{"type":"steps","steps":[]},"allowedTools":["phone_run"]}""", enabled = false))
        try {
            val r = hub.invoke(buildJsonObject {
                put("tool", "phone_run"); put("sessionId", "auto_${rule}_test"); put("timeoutMs", 10_000)
                put("args", Json.parseToJsonElement("""{"screen":"main","commands":[{"op":"tap","target":{"text":"Count once"}}]}"""))
            })
            assertFalse(r.ok); assertEquals("not_authorized", r.errorCode)
            assertTrue(text(r), text(r).contains("screen_read"))
            assertEquals(0, PhoneFixtureActivity.taps.get())
        } finally { entry.rules().delete(rule) }
    }

    @Test fun unexpectedAppSwitchStopsRemainingSteps() = runBlocking {
        val r = runPlan("""[{"op":"key","key":"home"},{"op":"tap","target":{"text":"Count once"}}]""")
        assertFalse(r.ok); assertEquals("app_changed", r.errorCode)
        assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun totalDeadlineReportsUncertainStepWithoutReplaying() = runBlocking {
        val r = hub.invoke(buildJsonObject {
            put("tool", "phone_run"); put("sessionId", ""); put("timeoutMs", 1500)
            put("args", Json.parseToJsonElement("""{"screen":"main","commands":[{"op":"tap","target":{"text":"Count once"}},{"op":"wait","text":"never appears","timeoutMs":10000},{"op":"tap","target":{"text":"Count once"}}]}"""))
        })
        assertFalse(r.ok); assertEquals("timeout", r.errorCode)
        delay(1000); assertEquals(1, PhoneFixtureActivity.taps.get())
        assertEquals("timeout", summary(r)["stopReason"]!!.jsonPrimitive.content)
        record("deadline", summary(r))
    }

    @Test fun cancellationDoesNotExecuteFollowingCommandsAndRetainsProgress() = runBlocking {
        var progress: ToolResult? = null
        val job = launch {
            runPlan("""[{"op":"tap","target":{"text":"Count once"}},{"op":"wait","text":"never appears","timeoutMs":10000},{"op":"tap","target":{"text":"Count once"}}]""") { progress = it }
        }
        val until = SystemClock.elapsedRealtime() + 5_000
        while ((progress?.let(::summary)?.get("completed")?.jsonArray?.size ?: 0) < 1 && SystemClock.elapsedRealtime() < until) delay(100)
        job.cancelAndJoin(); delay(800)
        assertEquals(1, PhoneFixtureActivity.taps.get())
        assertEquals(1, summary(progress!!)["completed"]!!.jsonArray.size)
        record("cancelled_progress", summary(progress!!))
    }

    @Test fun bridgeCancellationReturnsPartialJsonAndDoesNotReplay() = runBlocking {
        val task = async { hub.onBridgeRequest("capability/invoke", buildJsonObject {
            put("tool", "phone_run"); put("sessionId", ""); put("callId", "test-bridge-cancel"); put("timeoutMs", 30_000)
            put("args", Json.parseToJsonElement("""{"screen":"main","commands":[{"op":"assert","target":{"text":"Count 0"}},{"op":"tap","target":{"text":"Count once"}},{"op":"wait","text":"never appears","timeoutMs":10000},{"op":"tap","target":{"text":"Count once"}}]}"""))
        }).jsonObject }
        withTimeout(5_000) { while (PhoneFixtureActivity.taps.get() == 0) delay(50) }
        delay(700)
        hub.cancel("test-bridge-cancel", "")
        val r = withTimeout(3_000) { task.await() }
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("cancelled", r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val s = Json.parseToJsonElement(r["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals("cancelled", s["stopReason"]!!.jsonPrimitive.content)
        val completed = s["completed"]!!.jsonArray
        assertTrue("Cancellation must retain the verified prefix", completed.size in 1..2)
        assertEquals("assert", completed.first().jsonObject["op"]!!.jsonPrimitive.content)
        if (completed.size == 2) assertEquals("tap", completed.last().jsonObject["op"]!!.jsonPrimitive.content)
        assertEquals("The dispatched or waiting step must remain uncertain", completed.size + 1, s["inFlight"]!!.jsonPrimitive.int)
        delay(800); assertEquals(1, PhoneFixtureActivity.taps.get())
        record("bridge_cancel", s)
    }

    @Test fun steeringStopsTheOldPlan() = runBlocking {
        val task = async { runPlan("""[{"op":"wait","text":"never appears","timeoutMs":10000},{"op":"tap","target":{"text":"Count once"}}]""") }
        delay(700); hub.onUserPrompt("", "stop the old plan")
        val r = withTimeout(5_000) { task.await() }
        assertFalse(r.ok); assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun delayedNativeUpdateIsObservedAndWaitConditionVerified() = runBlocking {
        val start = SystemClock.elapsedRealtime()
        val r = runPlan("""[{"op":"tap","target":{"text":"Delayed result"}},{"op":"wait","text":"Delayed ready","timeoutMs":5000},{"op":"assert","target":{"text":"Delayed ready"}}]""")
        assertTrue(text(r), r.ok)
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue("elapsed=$elapsed", elapsed in 900..8_000)
        record("delayed_update", buildJsonObject { put("elapsedMs", elapsed) })
    }

    @Test fun explicitWaitObservesLateResultWithoutReplayingTheTap() = runBlocking {
        fixture("form", delayMs = 2_400)
        val r = runPlan("""[{"op":"tap","target":{"text":"Delayed result"}},{"op":"wait","text":"Delayed ready","timeoutMs":5000},{"op":"tap","target":{"text":"Count once"}}]""")
        assertTrue(text(r), r.ok)
        assertEquals(1, PhoneFixtureActivity.delayedTaps.get()); assertEquals(1, PhoneFixtureActivity.taps.get())
        record("late_update", summary(r))
    }

    @Test fun noObservedEffectStopsBeforeAnotherWrite() = runBlocking {
        fixture("form", delayMs = 3_000)
        val r = runPlan("""[{"op":"tap","target":{"text":"Delayed result"}},{"op":"tap","target":{"text":"Count once"}}]""")
        assertFalse(r.ok); assertEquals("no_effect", r.errorCode)
        assertEquals(1, PhoneFixtureActivity.delayedTaps.get()); assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun adaptiveWaitReturnsTheDelayedStateWithoutExplicitWait() = runBlocking {
        fixture("form", delayMs = 900)
        val before = DshAccessibilityService.instance!!.snapshot()
        val start = SystemClock.elapsedRealtime()
        val r = call("screen_tap", """{"text":"Delayed result"}""")
        val elapsed = SystemClock.elapsedRealtime() - start
        record("adaptive_delayed_diagnostic", buildJsonObject {
            put("elapsedMs", elapsed); put("dispatches", PhoneFixtureActivity.delayedTaps.get())
            put("before", JsonArray(before.elements.filterNot { it.system }.map { JsonPrimitive(it.line("")) }))
            put("after", JsonArray(r.observation?.frame?.elements.orEmpty().filterNot { it.system }.map { JsonPrimitive(it.line("")) }))
            put("package", r.observation?.frame?.packageName); put("stopReason", r.observation?.stopReason)
        })
        assertTrue(text(r), r.ok)
        assertTrue("Returned before the 900ms update", r.observation!!.frame.elements.any { it.text == "Delayed ready" })
        assertTrue("elapsed=$elapsed", elapsed in 900..3_200)
        assertEquals(1, PhoneFixtureActivity.delayedTaps.get())
        record("adaptive_delayed_without_wait", buildJsonObject { put("elapsedMs", elapsed); put("updateDelayMs", 900); put("resultPresentAtReturn", true); put("dispatches", 1) })
    }

    @Test fun adaptiveWaitReturnsQuickChangesBeforeTheDeadline() = runBlocking {
        val start = SystemClock.elapsedRealtime()
        val r = call("screen_tap", """{"text":"Count once"}""")
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue(text(r), r.ok)
        assertTrue(r.observation!!.frame.elements.any { it.text == "Count 1" })
        assertTrue("quick change waited to deadline: $elapsed", elapsed in 240..1_700)
        assertEquals(1, PhoneFixtureActivity.taps.get())
        record("adaptive_quick_without_wait", buildJsonObject { put("elapsedMs", elapsed); put("resultPresentAtReturn", true) })
    }

    @Test fun adaptiveWaitBoundsUnchangedActionsWithoutExplicitWait() = runBlocking {
        fixture("form", delayMs = 3_500)
        val start = SystemClock.elapsedRealtime()
        val r = call("screen_tap", """{"text":"Delayed result"}""")
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue(text(r), r.ok)
        assertEquals(ActionOutcome.NoEffect, r.outcome)
        assertFalse(r.observation!!.frame.elements.any { it.text == "Delayed ready" })
        assertTrue("elapsed=$elapsed", elapsed in 1_800..3_200)
        assertEquals(1, PhoneFixtureActivity.delayedTaps.get())
        record("adaptive_no_effect_without_wait", buildJsonObject { put("elapsedMs", elapsed); put("noEffect", true); put("dispatches", 1) })
    }

    @Test fun adaptiveWaitBoundsContinuouslyChangingNativeContent() = runBlocking {
        fun labels(v: android.view.View): List<android.widget.TextView> = when (v) {
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { labels(v.getChildAt(it)) }
            is android.widget.TextView -> listOf(v)
            else -> emptyList()
        }
        lateinit var status: android.widget.TextView
        instrumentation.runOnMainSync { status = labels(PhoneFixtureActivity.current!!.window.decorView).single { it.text.toString() == "Count 0" } }
        val ticks = java.util.concurrent.atomic.AtomicInteger()
        val animation = launch {
            delay(200)
            repeat(60) { n -> instrumentation.runOnMainSync { status.text = "Changing native content $n"; ticks.incrementAndGet() }; delay(60) }
        }
        try {
            val start = SystemClock.elapsedRealtime()
            val r = call("screen_tap", """{"text":"Count once"}""")
            val elapsed = SystemClock.elapsedRealtime() - start
            record("adaptive_changing_diagnostic", buildJsonObject {
                put("elapsedMs", elapsed); put("updates", ticks.get()); put("package", r.observation?.frame?.packageName)
                put("lastStatus", status.text.toString()); put("stopReason", r.observation?.stopReason)
                put("observedChanging", r.observation?.frame?.elements?.any { it.text?.startsWith("Changing native content") == true } == true)
            })
            assertTrue(text(r), r.ok)
            assertEquals("screen_unstable", r.observation!!.stopReason)
            assertTrue("elapsed=$elapsed", elapsed in 1_800..3_200)
            assertEquals(1, PhoneFixtureActivity.taps.get())
            record("adaptive_changing_without_wait", buildJsonObject { put("elapsedMs", elapsed); put("stopReason", "screen_unstable"); put("dispatches", 1) })
        } finally { animation.cancelAndJoin() }
    }

    @Test fun collectionReadsTheWholeNativeListWithRepeatedValues() = runBlocking {
        fixture("list")
        val r = call("phone_collect", """{"target":{"resourceId":"phone_fixture_list"},"maxPages":30,"maxItems":200}""")
        assertTrue(text(r), r.ok)
        val s = summary(r); val actual = s["items"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals((1..60).map(PhoneFixtureActivity::row), actual)
        assertFalse(s["truncated"]!!.jsonPrimitive.boolean)
        record("collect_forward", s)
        hub.onUserPrompt("")
        val back = call("phone_collect", """{"target":{"resourceId":"phone_fixture_list"},"direction":"up","maxPages":30,"maxItems":200}""")
        assertTrue(text(back), back.ok)
        assertEquals(actual, summary(back)["items"]!!.jsonArray.map { it.jsonPrimitive.content })
        record("collect_backward", summary(back))
    }

    @Test fun collectionHonorsLimits() = runBlocking {
        fixture("list")
        val r = call("phone_collect", """{"target":{"resourceId":"phone_fixture_list"},"maxItems":3,"maxPages":30}""")
        assertTrue(text(r), r.ok)
        val s = summary(r)
        assertEquals(3, s["items"]!!.jsonArray.size)
        assertEquals("output_limit", s["stopReason"]!!.jsonPrimitive.content)
        assertTrue(s["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun invalidCollectionBoundsDoNotReadOrScroll() = runBlocking {
        fixture("list")
        for (args in listOf("""{"maxItems":"invalid"}""", """{"maxPages":1.5}""",
            """{"region":{"left":0,"top":0,"right":1.5,"bottom":200}}""")) {
            val r = call("phone_collect", args)
            assertFalse(text(r), r.ok); assertEquals("invalid_args", r.errorCode)
        }
        val r = call("screen_read")
        assertTrue(r.observation!!.frame.elements.any { it.text == "Row 01" })
    }

    @Test fun collectionPreservesLongNativeText() = runBlocking {
        fixture("longtext")
        val r = call("phone_collect", """{"maxPages":1,"maxChars":5000}""")
        assertTrue(text(r), r.ok)
        assertTrue(text(r), summary(r)["items"]!!.jsonArray.any { it.jsonPrimitive.content == PhoneFixtureActivity.LONG_TEXT })
    }

    @Test fun ocrCoordinatesAreValidatedAndIdsAreNeverReused() = runBlocking {
        fixture("canvas")
        val first = call("screen_read", """{"ocr":true}""")
        assertTrue(text(first), first.ok)
        val id = first.observation!!.textIds.entries.singleOrNull { "TARGET ALPHA" in it.value.text }?.key
        assertNotNull(text(first), id)
        val second = call("screen_read", """{"ocr":true}""")
        assertFalse(second.observation!!.textIds.containsKey(id))
        val current = second.observation!!.textIds.entries.single { "TARGET ALPHA" in it.value.text }.key
        val staleId = call("screen_tap", """{"node":"$id"}""")
        assertFalse(staleId.ok); assertEquals(0, PhoneFixtureActivity.taps.get())
        instrumentation.runOnMainSync { PhoneFixtureActivity.current!!.updateCanvas("TARGET BRAVO") }
        delay(200)
        val staleFrame = call("screen_tap", """{"node":"$current"}""")
        assertFalse(staleFrame.ok); assertEquals(0, PhoneFixtureActivity.taps.get())
        val fresh = call("screen_read", """{"ocr":true}""")
        val newId = fresh.observation!!.textIds.entries.single { "TARGET BRAVO" in it.value.text }.key
        val tap = call("screen_tap", """{"node":"$newId"}""")
        assertTrue(text(tap), tap.ok); assertEquals(1, PhoneFixtureActivity.taps.get())
        record("ocr_validation", buildJsonObject { put("oldIdRejected", true); put("changedFrameRejected", true); put("freshTargetTapped", true) })
    }

    @Test fun movingCanvasDoesNotIssueOcrCoordinates() = runBlocking {
        fixture("canvas")
        instrumentation.runOnMainSync { PhoneFixtureActivity.current!!.updateCanvas("", true) }
        delay(200)
        val start = SystemClock.elapsedRealtime()
        val r = call("screen_read", """{"ocr":true}""")
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue(text(r), r.observation!!.textIds.isEmpty())
        assertNotNull(r.observation!!.stopReason)
        assertTrue("OCR wait exceeded its bound: $elapsed", elapsed < 10_000)
        record("moving_canvas", buildJsonObject { put("elapsedMs", elapsed); put("coordinatesIssued", 0) })
    }

    @Test fun ocrRetriesWhenCanvasChangesDuringRecognition() = runBlocking {
        fixture("canvas")
        val task = async { call("screen_read", """{"ocr":true}""") }
        // Between the initial samples and post-recognition check, including screenshot throttling.
        delay(1_850)
        instrumentation.runOnMainSync { PhoneFixtureActivity.current!!.updateCanvas("TARGET BRAVO") }
        val r = task.await()
        assertTrue(text(r), r.ok)
        val lines = r.observation!!.textIds.values.map { it.text }
        assertTrue(text(r), lines.any { "TARGET BRAVO" in it })
        assertFalse(lines.any { "TARGET ALPHA" in it })
        record("ocr_changed_during_read", buildJsonObject { put("freshFrameReturned", true) })
    }

    @Test fun realSettingsAppCanBeReadAndCollected() = runBlocking {
        settingsAtTop()
        val r = call("phone_collect", """{"package":"com.android.settings","maxPages":3,"maxItems":200}""")
        assertTrue(text(r), r.ok)
        assertTrue("Must collect multiple actual pages", summary(r)["pages"]!!.jsonPrimitive.int >= 2)
        assertTrue(summary(r)["items"]!!.jsonArray.size > 13)
        // Record counts only: a real settings page can contain account/device names.
        record("native_settings", buildJsonObject { put("pages", summary(r)["pages"]!!); put("items", summary(r)["items"]!!.jsonArray.size) })
    }

    private suspend fun settingsAtTop() {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(800)
        val top = call("phone_collect", """{"package":"com.android.settings","direction":"up","maxPages":8,"maxItems":200}""")
        assertTrue("Settings preparation failed: ${top.errorCode}", top.ok)
        assertTrue("Settings must be at its root list", DshAccessibilityService.instance!!.snapshot().elements.any { it.resId == "scroll_headers" })
        assertTrue(DshAccessibilityService.instance!!.snapshot().elements.any { it.text == "更多连接" })
        hub.onUserPrompt("")
    }

    @Test fun realClockNavigationCompletesOneBatchWithoutExplicitWait() = runBlocking {
        val open = runPlan("""[{"op":"launch","package":"com.android.deskclock"},{"op":"assert","package":"com.android.deskclock"},{"op":"tap","target":{"text":"秒表","role":"LinearLayout"}},{"op":"assert","target":{"resourceId":"action_bar_title_expand","text":"秒表"}}]""")
        assertTrue("Clock preparation failed: ${open.errorCode}", open.ok)
        hub.onUserPrompt("")
        val r = runPlan("""[
            {"op":"tap","target":{"text":"世界时钟","role":"LinearLayout"}},
            {"op":"assert","target":{"resourceId":"action_bar_title_expand","text":"时钟","contains":true}},
            {"op":"tap","target":{"text":"秒表","role":"LinearLayout"}},
            {"op":"assert","target":{"resourceId":"action_bar_title_expand","text":"秒表"}},
            {"op":"tap","target":{"text":"世界时钟","role":"LinearLayout"}},
            {"op":"assert","target":{"resourceId":"action_bar_title_expand","text":"时钟","contains":true}},
            {"op":"tap","target":{"text":"秒表","role":"LinearLayout"}},
            {"op":"assert","target":{"resourceId":"action_bar_title_expand","text":"秒表"}}
        ]""")
        assertTrue("Native Clock navigation failed: ${r.errorCode}", r.ok)
        val result = summary(r)
        assertEquals(8, result["completed"]!!.jsonArray.size)
        assertEquals("com.android.deskclock", r.observation!!.frame.packageName)
        record("native_clock_sequence", buildJsonObject { put("completed", result["completed"]!!); put("elapsedMs", result["elapsedMs"]!!); put("explicitWaits", 0); put("timerOrAlarmActions", 0) })
    }

    @Test fun realSettingsUnavailableAccessibilityWindowStopsTheBatch() = runBlocking {
        settingsAtTop()
        val r = runPlan("""[{"op":"tap","target":{"text":"更多连接"}},{"op":"assert","target":{"text":"专用DNS"}},{"op":"key","key":"back"}]""")
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val nativeRoot = ui.rootInActiveWindow
        val serviceRoot = DshAccessibilityService.instance!!.rootInActiveWindow
        // This Redmi exposes this subpage only to the privileged test observer. Keep the
        // limitation visible rather than calling this a successful Settings navigation test.
        assertEquals("com.android.settings", nativeRoot?.packageName?.toString())
        assertNull("Re-evaluate this device limitation if the page becomes accessible", serviceRoot)
        assertFalse("Batch must stop when it loses its expected app", r.ok)
        assertEquals("app_changed", r.errorCode)
        assertEquals(0, summary(r)["completed"]!!.jsonArray.size)
        assertEquals(1, summary(r)["inFlight"]!!.jsonPrimitive.int)
        record("settings_subpage_limitation", buildJsonObject {
            put("testObserverPackage", nativeRoot?.packageName?.toString()); put("serviceRootAvailable", false)
            put("accessibilityDataSensitive", nativeRoot?.isAccessibilityDataSensitive)
            put("reportedPackage", r.observation?.frame?.packageName); put("stopReason", r.errorCode); put("remainingActionsExecuted", false)
        })
    }

    @Test fun realSettingsOcrRejectsStaleTargetsAndFreshTargetNavigates() = runBlocking {
        settingsAtTop()
        val first = call("screen_read", """{"ocr":true}""")
        val oldId = first.observation!!.textIds.entries.singleOrNull { it.value.text == "更多连接" }?.key
        assertNotNull("OCR did not identify the native Settings entry", oldId)
        val second = call("screen_read", """{"ocr":true}""")
        val validId = second.observation!!.textIds.entries.single { it.value.text == "更多连接" }.key
        assertNotEquals(oldId, validId)
        val before = DshAccessibilityService.instance!!.snapshot().elements.filterNot { it.system }.hashCode()
        val stale = call("screen_tap", """{"node":"$oldId"}""")
        assertFalse("Old OCR id was accepted", stale.ok)
        assertEquals(before, DshAccessibilityService.instance!!.snapshot().elements.filterNot { it.system }.hashCode())
        assertTrue(call("screen_scroll", """{"direction":"down"}""").ok)
        val scrolled = DshAccessibilityService.instance!!.snapshot().elements.filterNot { it.system }.hashCode()
        assertNotEquals(before, scrolled)
        val changed = call("screen_tap", """{"node":"$validId"}""")
        assertFalse("Coordinate from the previous page was accepted", changed.ok)
        assertEquals(scrolled, DshAccessibilityService.instance!!.snapshot().elements.filterNot { it.system }.hashCode())
        assertTrue(call("phone_collect", """{"direction":"up","package":"com.android.settings","maxPages":8}""").ok)
        val fresh = call("screen_read", """{"ocr":true}""")
        val freshId = fresh.observation!!.textIds.entries.single { it.value.text == "更多连接" }.key
        val tap = call("screen_tap", """{"node":"$freshId"}""")
        assertTrue("Fresh OCR coordinate failed: ${tap.errorCode}", tap.ok)
        // Independent destination proof: DSH cannot read this sensitive child window on this
        // Redmi; the separate limitation test explicitly records that inability.
        val nativeRoot = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow
        fun containsDns(node: android.view.accessibility.AccessibilityNodeInfo): Boolean = node.text?.toString() == "专用DNS" ||
            (0 until node.childCount).any { node.getChild(it)?.let(::containsDns) == true }
        assertEquals("com.android.settings", nativeRoot?.packageName?.toString())
        assertTrue("Fresh OCR tap did not navigate", nativeRoot?.let(::containsDns) == true)
        record("native_settings_ocr", buildJsonObject { put("oldIdRejected", true); put("scrolledFrameRejected", true); put("freshOcrNavigated", true); put("destinationVerifiedByIndependentTestObserver", true); put("rejectedActionsLeftPageUnchanged", true) })
    }

    @Test fun realXiaomiCalculatorExecutesAndVerifiesNativeArithmetic() = runBlocking {
        org.junit.Assume.assumeTrue("Xiaomi Calculator is needed for this OEM integration test",
            context.packageManager.getLaunchIntentForPackage("com.miui.calculator") != null)
        val open = runPlan("""[{"op":"launch","package":"com.miui.calculator"},{"op":"assert","package":"com.miui.calculator"}]""")
        assertTrue(text(open), open.ok)
        // Clear only the current scratch expression, not calculation history or app data.
        val clear = call("screen_tap", """{"text":"清除"}"""); assertTrue(text(clear), clear.ok)
        hub.onUserPrompt("")
        val r = runPlan("""[
            {"op":"tap","target":{"resourceId":"digit_7"}},
            {"op":"tap","target":{"resourceId":"op_add"}},
            {"op":"tap","target":{"resourceId":"digit_5"}},
            {"op":"tap","target":{"resourceId":"btn_equal_s"}},
            {"op":"assert","target":{"resourceId":"result","text":"= 12"}}
        ]""")
        assertTrue(text(r), r.ok)
        record("native_calculator", buildJsonObject { put("expression", "7+5"); put("result", "12"); put("completed", summary(r)["completed"]!!) })
    }

    @Test fun compareSingleAndCompoundNativeDispatch() = runBlocking {
        val single = mutableListOf<Long>(); val batch = mutableListOf<Long>()
        repeat(3) {
            fixture("form"); hub.onUserPrompt("")
            var start = SystemClock.elapsedRealtime()
            repeat(4) { val r = call("screen_tap", """{"text":"Count once"}"""); assertTrue(text(r), r.ok) }
            single += SystemClock.elapsedRealtime() - start
            fixture("form"); hub.onUserPrompt("")
            start = SystemClock.elapsedRealtime()
            val r = runPlan("[" + List(4) { """{"op":"tap","target":{"text":"Count once"}}""" }.joinToString(",") + "]")
            assertTrue(text(r), r.ok); assertEquals(4, PhoneFixtureActivity.taps.get())
            batch += SystemClock.elapsedRealtime() - start
        }
        record("native_dispatch_benchmark", buildJsonObject {
            put("singleMs", JsonArray(single.map(::JsonPrimitive))); put("batchMs", JsonArray(batch.map(::JsonPrimitive)))
            put("singleToolCalls", 4); put("batchToolCalls", 1); put("includesModelLatency", false)
        })
    }
}
