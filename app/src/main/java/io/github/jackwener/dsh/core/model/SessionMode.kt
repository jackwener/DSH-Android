package io.github.jackwener.dsh.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Built-in engine tool categories a mode can switch (ENGINE-CONTRACT §2.5; plugin-mode maps each
 * id to dsh tool names). Tools outside every category (ask_user_question, todo, goals, …) stay.
 */
enum class EngineTool(val id: String) {
    Shell("shell"), FilesRead("files_read"), FilesWrite("files_write"), Web("web"), Subagent("subagent");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/** The fixed icon set a mode can use (ids are stored; the drawable mapping lives in the UI). */
enum class ModeIcon(val id: String) {
    Default("default"), Phone("phone"), Code("code"), Chat("chat"), Globe("globe"), Pen("pen"), Goal("goal"), Skill("skill");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Default }
}

/**
 * A session mode (issue #3): which tools a conversation gets, extra system-prompt text, and the
 * permission preset picked with it. Chosen per conversation before its first message, then fixed.
 * The app owns the definitions (built-ins below + the user's own, in settings) and sends the
 * resolved mode to the engine with `android/session/mode` before every prompt.
 */
@Serializable
data class SessionMode(
    val id: String,
    /** Display name; built-ins leave it empty and are named from resources ([SessionModes.nameRes]). */
    val name: String = "",
    val icon: String = ModeIcon.Default.id,
    /** Extra system-prompt text (model-facing; English reads best, any language works). */
    val prompt: String = "",
    /** Device tool groups ([ToolGroup] ids) the mode offers; null = every group the user enabled. */
    val deviceGroups: Set<String>? = null,
    /** Built-in engine tool categories ([EngineTool] ids); null = all of them. */
    val engineTools: Set<String>? = null,
    /** [PermissionPreset] id applied when the mode is picked; null = the settings default. */
    val preset: String? = null,
    val skills: Boolean = true,
    val builtIn: Boolean = false,
) {
    val isDefault: Boolean get() = id == SessionModes.DEFAULT_ID

    /** Whether the mode lets the conversation use device tools of [group] (the user's global switch still applies). */
    fun allowsGroup(group: ToolGroup): Boolean = deviceGroups == null || group.id in deviceGroups

    fun allowsTool(tool: EngineTool): Boolean = engineTools == null || tool.id in engineTools

    val presetOrNull: PermissionPreset? get() = preset?.let { id -> PermissionPreset.entries.firstOrNull { it.id == id } }

    /** The `android/session/mode` payload's `mode` object, named [displayName]. */
    fun payload(displayName: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", displayName)
        if (prompt.isNotBlank()) put("prompt", prompt.trim())
        deviceGroups?.let { g -> putJsonArray("deviceGroups") { g.sorted().forEach { add(JsonPrimitive(it)) } } }
        engineTools?.let { t -> putJsonArray("tools") { t.sorted().forEach { add(JsonPrimitive(it)) } } }
        put("skills", skills)
    }

    /** An editable copy (a user mode), e.g. 复制 on a built-in. */
    fun duplicate(newId: String, newName: String) = copy(id = newId, name = newName, builtIn = false)
}

object SessionModes {
    const val DEFAULT_ID = "default"
    const val PHONE_ID = "phone"
    const val CODE_ID = "code"
    const val CHAT_ID = "chat"
    /** User modes get ids with this prefix, so they never collide with a future built-in. */
    const val CUSTOM_PREFIX = "u_"

    /** 默认: today's behaviour — every tool, the settings' permission default, no extra prompt. */
    val Default = SessionMode(DEFAULT_ID, icon = ModeIcon.Default.id, builtIn = true)

    /** 手机操作: device and screen tools, phone-oriented prompt; asks before acting. */
    val Phone = SessionMode(
        PHONE_ID, icon = ModeIcon.Phone.id, builtIn = true,
        prompt = "Focus on getting things done on this phone with the device tools: settings, apps, notifications, messages, files, " +
            "media and operating other apps on screen. Prefer doing the task over explaining how to do it, and keep the user informed in one line per step.",
        deviceGroups = ToolGroup.entries.map { it.id }.toSet(),
        engineTools = setOf(EngineTool.FilesRead.id, EngineTool.Web.id, EngineTool.Subagent.id),
        preset = PermissionPreset.Ask.id, skills = true,
    )

    /** 编程: shell, files, skills, web; no screen or device-control groups. */
    val Code = SessionMode(
        CODE_ID, icon = ModeIcon.Code.id, builtIn = true,
        prompt = "You are a programming assistant. Work in your private home folder with the shell and file tools: write, run and debug code " +
            "and scripts, and explain briefly. The shell is Android's /system/bin/sh with toybox; there is no root and often no compilers or " +
            "interpreters, so check what exists (e.g. `command -v python3`) before relying on it. Show code in fenced blocks.",
        deviceGroups = setOf(ToolGroup.Files.id, ToolGroup.Clipboard.id),
        engineTools = EngineTool.entries.map { it.id }.toSet(),
        preset = PermissionPreset.Ask.id, skills = true,
    )

    /** 只聊天: conversation plus web search / fetch; read-only. */
    val Chat = SessionMode(
        CHAT_ID, icon = ModeIcon.Chat.id, builtIn = true,
        prompt = "This is a chat conversation: answer from your own knowledge, and use web search or web fetch when current or external " +
            "information would help. You cannot act on the phone, read its data or run commands here.",
        deviceGroups = emptySet(), engineTools = setOf(EngineTool.Web.id),
        preset = PermissionPreset.ReadOnly.id, skills = false,
    )

    val builtIns: List<SessionMode> = listOf(Default, Phone, Code, Chat)

    /** Built-ins first, then the user's modes (ids clashing with a built-in are ignored). */
    fun all(custom: List<SessionMode>): List<SessionMode> =
        builtIns + custom.filter { c -> builtIns.none { it.id == c.id } }.map { it.copy(builtIn = false) }

    /**
     * The mode of a conversation whose row says [id]. null (sessions from before modes existed) and
     * ids that no longer exist (a deleted user mode) are 默认, i.e. unchanged behaviour.
     */
    fun resolve(id: String?, custom: List<SessionMode>): SessionMode =
        id?.let { want -> all(custom).firstOrNull { it.id == want } } ?: Default

    fun newCustomId(existing: Collection<String>, random: () -> String = { java.util.UUID.randomUUID().toString().take(8) }): String {
        while (true) { val id = CUSTOM_PREFIX + random(); if (id !in existing) return id }
    }
}

/**
 * Whether a conversation's mode can still be changed: only before its first message. After that
 * the engine already ran with its tool set and prompt (changing them mid-history would confuse the
 * model and re-bill the cached prompt), so the composer shows the mode but locks it.
 */
fun modeChangeable(exists: Boolean, running: Boolean, hasEngineEvents: Boolean): Boolean = !exists && !running && !hasEngineEvents
