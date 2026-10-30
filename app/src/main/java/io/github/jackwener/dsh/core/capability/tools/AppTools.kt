package io.github.jackwener.dsh.core.capability.tools

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.AgentScreen
import io.github.jackwener.dsh.core.capability.AppKnowledge
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.bool
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.capability.strs
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.platform.AppVisibility
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.service.DshAccessibilityService
import android.provider.Settings
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuShell,
    private val agent: AgentScreen,
    private val knowledge: AppKnowledge,
) : ToolSet {
    private val pm get() = context.packageManager

    private companion object {
        const val SCREEN = "screen"
        const val SCREEN_DESC = "Where to open it: \"agent\" = your own background screen (default when Shizuku is ready), \"main\" = the user's screen"
    }

    private fun label(ai: ApplicationInfo) = pm.getApplicationLabel(ai).toString()

    /** Android drops (without an error) activity starts from a background app unless one of these exempts DSH. */
    private fun mayStartFromBackground(): Boolean = AppVisibility.isForeground || Settings.canDrawOverlays(context) ||
        DshAccessibilityService.instance != null ||
        runCatching { context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)

    private class LaunchBlocked(message: String) : Exception(message)

    /**
     * Starts an activity from the app process, or through Shizuku `am start -W` when Android would
     * block it (background start) or for a virtual display. With accessibility on, waits briefly for
     * [expect] to reach the front so "ok" means it actually opened.
     */
    private suspend fun start(intent: Intent, displayId: Int? = null, expect: String? = null): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (displayId == null && (mayStartFromBackground() || !shizuku.available)) {
            if (!mayStartFromBackground()) throw LaunchBlocked("Android does not let DSH open other apps while it is in the background " +
                "(no overlay permission, accessibility service or Shizuku). Ask the user to open it, or to enable DSH's accessibility service.")
            try { context.startActivity(intent) }
            catch (e: ActivityNotFoundException) { throw InvalidArgsException("No app can handle this: ${e.message}") }
            catch (e: SecurityException) { if (!shizuku.available) throw e; return viaShell(intent, null, expect) }
            return "ok" + confirm(expect)
        }
        return viaShell(intent, displayId, expect)
    }

    private suspend fun viaShell(intent: Intent, displayId: Int?, expect: String?): String {
        val uri = intent.toUri(Intent.URI_INTENT_SCHEME)
        val r = shizuku.exec(buildString {
            append("am start -W")
            if (displayId != null) append(" --display $displayId")
            append(" '${uri.replace("'", "'\\''")}'")
        })
        // `am start` exits 0 on several Android versions even after "Error: Activity not started".
        val out = r.output
        if (r.code != 0 || out.contains("Error") || (out.contains("Status:") && !out.contains("Status: ok")))
            throw IllegalStateException(out.lines().firstOrNull { it.contains("Error") || it.contains("Status") }?.trim() ?: out.take(300))
        return "ok (via Shizuku)" + if (displayId == null) confirm(expect) else confirmOn(displayId, expect)
    }

    private suspend fun confirm(expect: String?): String {
        if (expect == null || DshAccessibilityService.instance == null) return ""
        repeat(10) { if (DshAccessibilityService.foregroundPackage.value == expect) return ""; kotlinx.coroutines.delay(250) }
        return " — but $expect is not in front yet (it may still be loading, or Android blocked it); check with screen_read"
    }

    private suspend fun confirmOn(displayId: Int, expect: String?): String {
        val svc = DshAccessibilityService.instance ?: return ""
        if (expect == null) return ""
        repeat(10) { if (runCatching { svc.snapshot(displayId).packageName }.getOrNull() == expect) return ""; kotlinx.coroutines.delay(250) }
        return " — but $expect is not in front of the agent screen yet (it may still be loading); check with screen_read"
    }

    private suspend fun launchResult(prefix: String, intent: Intent, displayId: Int? = null, expect: String? = null): ToolResult =
        try { ToolResult.text(prefix + start(intent, displayId, expect)) } catch (e: LaunchBlocked) { ToolResult.error("needs_user", e.message ?: "blocked") }

    /**
     * Opens [intent] where this conversation works: its agent screen (created on first use when
     * [create]) or the user's screen. Never takes an app away from the user: one they are using on
     * their screen is not started on the agent screen. Adds app knowledge on the first visit.
     */
    private suspend fun open(call: CallContext, args: JsonObject, verb: String, intent: Intent, expect: String?, create: Boolean): ToolResult {
        val t = agent.target(call.sessionId, args.str("screen"), create)
        val name = expect?.let { agent.label(it) }
        val result = if (t.agent) {
            if (agent.userTookOver(call.sessionId)) return ToolResult.error("user_took_over",
                "The user took the app over from the agent screen and is using it on their own screen. Do not operate it; wait for their message or ask them.")
            if (expect != null && agent.inUseOnMain(expect)) return ToolResult.error("app_in_use",
                "The user is using $name ($expect) on their screen right now, so it was not opened on the agent screen (that would take it away from them). " +
                    "Wait and try again later, ask the user, or pass screen: \"main\" to work on their screen.")
            launchResult("$verb${name?.let { " $it" } ?: ""} on the agent screen (the user's screen is not affected): ", intent, t.displayId, expect)
                .also { if (it.ok) agent.mute(call.sessionId, expect) }
        } else launchResult("$verb${name?.let { " $it" } ?: ""} on the user's screen: ", intent, expect = expect)
        val known = expect?.takeIf { result.ok }?.let { knowledge.firstVisitNotice(call.sessionId, it) }
        return (t.note?.let { result.prependText(it) } ?: result).let { r -> known?.let { r.appendText(it) } ?: r }
    }

    private fun targetOf(intent: Intent): String? = intent.`package` ?: intent.component?.packageName ?: runCatching {
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" }
    }.getOrNull()

    override val tools = listOf(
        object : Tool("app_list", ToolGroup.Apps, Risk.read, false, "List installed user-launchable apps (label + package). Filter with query.", {
            string("query", "Substring of app name or package")
            bool("includeSystem", "Include system apps")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val q = args.str("query")?.lowercase()
                val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
                val list = launchable.filter { args.bool("includeSystem") == true || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || q != null }
                    .map { label(it) to it.packageName }
                    .filter { q == null || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
                    .sortedBy { it.first.lowercase() }
                return ToolResult.text(list.joinToString("\n") { "${it.first}  (${it.second})" }.ifEmpty { "No matching apps." })
            }
        },
        object : Tool("app_info", ToolGroup.Apps, Risk.read, false,
            "Details of an installed app: version, install/update time, size, and its exported activities (full names usable as app_launch activity).", {
            string("package", "Package name", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val p = args.reqStr("package")
                val info = try { pm.getPackageInfo(p, PackageManager.GET_ACTIVITIES) } catch (e: PackageManager.NameNotFoundException) {
                    throw InvalidArgsException("Not installed: $p") }
                val ai = info.applicationInfo!!
                return ToolResult.text(buildString {
                    appendLine("${label(ai)} ($p) ${info.versionName}")
                    appendLine("installed ${java.util.Date(info.firstInstallTime)}, updated ${java.util.Date(info.lastUpdateTime)}")
                    appendLine("apk ${File(ai.sourceDir).length() / 1024 / 1024} MB, system=${(ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0}")
                    append("exported activities: ${info.activities?.filter { it.exported }?.take(30)?.joinToString { it.name } ?: "-"}")
                })
            }
        },
        object : Tool("app_launch", ToolGroup.Apps, Risk.write, true,
            "Open an app by package name (optionally a specific exported activity). With Shizuku it opens on your own background screen, " +
                "so the user keeps using their phone; the screen_* tools then act there. The result says which screen was used.", {
            string("package", "Package name", required = true)
            string("activity", "Fully-qualified exported activity (optional)")
            string(SCREEN, SCREEN_DESC, enum = listOf("agent", "main"))
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val p = args.reqStr("package")
                val intent = args.str("activity")?.let { a -> Intent().setComponent(ComponentName(p, if (a.startsWith(".")) p + a else a)) }
                    ?: pm.getLaunchIntentForPackage(p) ?: throw InvalidArgsException("No launchable activity for $p")
                return open(call, args, "Launched", intent, p, create = true)
            }
        },
        object : Tool("app_open_link", ToolGroup.Apps, Risk.write, true,
            "Open a URI/deep link (https:, tel:, geo:, mailto:, app schemes like weixin://, alipays://, etc.). " +
                "Like app_launch it opens on your background screen when there is one.", {
                string("uri", "The URI", required = true)
                string("package", "Restrict to this app (optional)")
                string(SCREEN, SCREEN_DESC, enum = listOf("agent", "main"))
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(args.reqStr("uri")))
                args.str("package")?.let { intent.setPackage(it) }
                return open(call, args, "Opened", intent, targetOf(intent), create = true)
            }
        },
        object : Tool("app_send_intent", ToolGroup.Apps, Risk.write, true,
            "Start an activity with an explicit Android intent (action/data/type/package; extras values may be strings, numbers or booleans).", {
                string("action", "e.g. android.settings.BLUETOOTH_SETTINGS", required = true)
                string("data", "Data URI")
                string("type", "MIME type")
                string("package", "Target package")
                obj("extras", "String extras {key: value}")
                string(SCREEN, "Which screen: \"agent\" = your background screen if this conversation has one (default), \"main\" = the user's screen", enum = listOf("agent", "main"))
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val intent = Intent(args.reqStr("action"))
                val data = args.str("data")?.let(Uri::parse)
                val type = args.str("type")
                if (data != null && type != null) intent.setDataAndType(data, type) else { data?.let(intent::setData); type?.let(intent::setType) }
                args.str("package")?.let(intent::setPackage)
                (args["extras"] as? JsonObject)?.forEach { (k, v) ->
                    val p = v as? JsonPrimitive ?: return@forEach
                    val b = p.booleanOrNull; val n = p.longOrNull; val d = p.doubleOrNull
                    when {
                        p.isString -> intent.putExtra(k, p.content)
                        b != null -> intent.putExtra(k, b)
                        n != null -> if (n in Int.MIN_VALUE..Int.MAX_VALUE) intent.putExtra(k, n.toInt()) else intent.putExtra(k, n)
                        d != null -> intent.putExtra(k, d)
                        else -> p.contentOrNull?.let { intent.putExtra(k, it) }
                    }
                }
                return open(call, args, "Sent", intent, targetOf(intent), create = false)
            }
        },
        object : Tool("share_to_app", ToolGroup.Apps, Risk.write, true, "Share files and/or text to another app (opens the share target).", {
            strings("paths", "Files to share")
            string("text", "Text to share")
            string("package", "Target app package (optional: otherwise the system chooser opens)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val root = Environment.getExternalStorageDirectory()
                val files = args.strs("paths").map { p -> (if (p.startsWith("/")) File(p) else File(root, p)).canonicalFile }
                files.firstOrNull { !it.isFile }?.let { throw InvalidArgsException("Not a file: ${it.path}") }
                val uris = ArrayList(files.map { f ->
                    try { FileProvider.getUriForFile(context, "${context.packageName}.files", f) }
                    catch (e: IllegalArgumentException) { throw InvalidArgsException("Cannot share ${f.path}: only shared storage and SD cards can be shared") }
                })
                // The real MIME type: several targets (WeChat among them) mishandle */*.
                val types = files.map { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension.lowercase()) ?: "application/octet-stream" }.distinct()
                val mime = when {
                    types.isEmpty() -> "text/plain"
                    types.size == 1 -> types[0]
                    types.map { it.substringBefore('/') }.distinct().size == 1 -> types[0].substringBefore('/') + "/*"
                    else -> "*/*"
                }
                val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                    type = mime
                    args.str("text")?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val target = args.str("package")?.let { intent.setPackage(it) } ?: Intent.createChooser(intent, null)
                return launchResult("Share sheet opened: ", target, expect = args.str("package"))
            }
        },
    )
}
