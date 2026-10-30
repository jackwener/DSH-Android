package io.github.jackwener.dsh.core.capability

import kotlin.math.hypot

/** One element of a screen read, detached from AccessibilityNodeInfo so ids, diffs and effects stay pure. */
data class UiElement(
    val cls: String,
    val text: String?,
    val desc: String?,
    val resId: String?,
    val x: Int,
    val y: Int,
    val depth: Int = 0,
    val flags: List<String> = emptyList(),
    val editable: Boolean = false,
    /** Status bar / notification shade (listed under its own header, ignored by diffs and fingerprints). */
    val system: Boolean = false,
) {
    /** What makes two reads' elements "the same": an edit field keeps its identity while its text changes. */
    val identity: String get() = if (editable) "$cls\u0000\u0000$desc\u0000$resId" else "$cls\u0000$text\u0000$desc\u0000$resId"
    val label: String? get() = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }
    val readable: Boolean get() = !text.isNullOrBlank() || !desc.isNullOrBlank()

    /** `[e3] Button "OK" desc="…" #res {flags} @x,y`, without indentation. */
    fun line(id: String): String = buildString {
        append('[').append(id).append("] ").append(cls)
        if (!text.isNullOrBlank()) append(" \"").append(text.replace('\n', ' ')).append('"')
        if (!desc.isNullOrBlank() && desc != text) append(" desc=\"").append(desc.replace('\n', ' ')).append('"')
        resId?.let { append(" #").append(it) }
        if (flags.isNotEmpty()) append(" {").append(flags.joinToString(",")).append('}')
        append(" @").append(x).append(',').append(y)
    }

    /** The line without the position: an element that only drifted a few pixels is not "changed". */
    fun content(): String = line("").substringBeforeLast(" @")
}

/** A screen read with stable ids ([ids] parallel to [elements]). */
class Frame(val seq: Int, val packageName: String?, val elements: List<UiElement>, val ids: List<String>) {
    private val index by lazy { ids.withIndex().associate { it.value to it.index } }
    fun indexOf(id: String): Int? = index[id]

    /** Hash of what the app shows (texts and flags, system UI excluded): equal before and after = nothing happened. */
    val fingerprint: Int by lazy { elements.filterNot { it.system }.map { it.content() }.hashCode() * 31 + (packageName?.hashCode() ?: 0) }

    /** Elements that carry readable text (few of them = custom-drawn UI, try text recognition). */
    val readableCount: Int get() = elements.count { !it.system && it.readable }

    fun render(): String = buildString {
        var systemHeader = false
        elements.forEachIndexed { i, e ->
            if (e.system && !systemHeader) { append("-- system UI (status bar / notification shade) --\n"); systemHeader = true }
            append("  ".repeat(minOf(e.depth, 12))).append(e.line(ids[i])).append('\n')
        }
    }
}

/**
 * Ids that survive re-reads: an element that is still there in the next read (same class, text,
 * description and resource id, centre moved at most [maxShift] px, same foreground app) keeps its
 * `e` number, so the ids the model holds are not all invalidated by one refresh. Numbers only go
 * up within one tracker; retired ids remember their element for [resolve].
 * (Idea from wangzan101/mika RefStabilizer, Apache-2.0; simplified.)
 */
class StableIds(private val maxShift: Int = 48, private val prefix: String = "e") {
    private var next = 1
    private var seq = 0
    var latest: Frame? = null
        private set
    private val retired = object : LinkedHashMap<String, UiElement>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UiElement>) = size > 600
    }

    @Synchronized
    fun assign(packageName: String?, elements: List<UiElement>): Frame {
        val prev = latest?.takeIf { it.packageName == packageName }
        val free = prev?.elements?.indices?.groupBy { prev.elements[it].identity }?.mapValues { it.value.toMutableList() }.orEmpty()
        val ids = elements.map { e ->
            val candidates = free[e.identity]
            val best = candidates?.minByOrNull { j -> dist(prev!!.elements[j], e) }?.takeIf { j -> dist(prev!!.elements[j], e) <= maxShift }
            if (best != null) { candidates.remove(best); prev!!.ids[best] } else "$prefix${next++}"
        }
        latest?.let { old ->
            val kept = ids.toSet()
            old.ids.forEachIndexed { i, id -> if (id !in kept) retired[id] = old.elements[i] }
        }
        ids.forEach { retired.remove(it) }
        return Frame(++seq, packageName, elements, ids).also { latest = it }
    }

    sealed interface Lookup {
        data class Found(val index: Int) : Lookup
        /** [id] is gone from [frame], but an element with its features is there now (at [index]). */
        data class Relocated(val index: Int, val newId: String) : Lookup
        data object Gone : Lookup
        data object Unknown : Lookup
    }

    /** Finds [id] in [frame] (normally [latest]); a retired id is looked up again by its features. */
    @Synchronized
    fun resolve(id: String, frame: Frame? = latest): Lookup {
        frame ?: return Lookup.Unknown
        frame.indexOf(id)?.let { return Lookup.Found(it) }
        val was = retired[id] ?: return if (issued(id)) Lookup.Gone else Lookup.Unknown
        val i = frame.elements.indices.filter { frame.elements[it].identity == was.identity }.minByOrNull { dist(frame.elements[it], was) }
            ?: return Lookup.Gone
        return Lookup.Relocated(i, frame.ids[i])
    }

    private fun issued(id: String) = id.startsWith(prefix) && (id.removePrefix(prefix).toIntOrNull()?.let { it in 1 until next } == true)

    private fun dist(a: UiElement, b: UiElement) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    companion object {
        /** "e12", "12" or 12 → "e12"; OCR ids ("t3") stay as they are. */
        fun normalize(raw: String): String = raw.trim().removePrefix("[").removeSuffix("]").let { if (it.toIntOrNull() != null) "e$it" else it.lowercase() }
    }
}

/** What changed between the read the model saw and the read after an action. */
class ScreenDiff(val added: List<Int>, val removed: List<Int>, val changed: List<Int>, val pageChanged: Boolean) {
    val empty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()

    /** `+ [e31] …` / `- [e12] …` / `~ [e7] …` lines ([removed] index into [old], the rest into [new]). */
    fun render(old: Frame, new: Frame): String = buildString {
        added.forEach { append("+ ").append(new.elements[it].line(new.ids[it])).append('\n') }
        changed.forEach { append("~ ").append(new.elements[it].line(new.ids[it])).append('\n') }
        removed.forEach { append("- ").append(old.elements[it].line(old.ids[it])).append('\n') }
    }

    companion object {
        /** More than this share of the elements came or went: a new page, sent in full. */
        const val PAGE_SHARE = 0.5

        fun of(old: Frame, new: Frame): ScreenDiff {
            val oldApp = old.elements.indices.filterNot { old.elements[it].system }
            val newApp = new.elements.indices.filterNot { new.elements[it].system }
            val oldIds = oldApp.associateBy { old.ids[it] }
            val newIds = newApp.map { new.ids[it] }.toSet()
            val added = newApp.filter { new.ids[it] !in oldIds }
            val removed = oldApp.filter { old.ids[it] !in newIds }
            val changed = newApp.filter { i -> oldIds[new.ids[i]]?.let { old.elements[it].content() != new.elements[i].content() } == true }
            val total = maxOf(oldApp.size, newApp.size, 1)
            val page = old.packageName != new.packageName || (added.size + removed.size).toDouble() / (2 * total) > PAGE_SHARE ||
                (oldApp.isNotEmpty() && removed.size.toDouble() / oldApp.size > PAGE_SHARE)
            return ScreenDiff(added, removed, changed, page)
        }
    }
}

/**
 * "Too frequent / limit reached" messages that appeared after an action (toasts, dialogs). Only
 * short texts that were not on screen before count: help pages that mention limits do not.
 * (Word list adapted from wangzan101/mika LimitNotice, Apache-2.0.)
 */
object LimitNotice {
    private val WORDS = listOf(
        "操作过于频繁", "操作太频繁", "操作频繁", "请求过于频繁", "访问过于频繁", "过于频繁", "太频繁", "频繁操作",
        "已达上限", "已达到上限", "达到上限", "超过上限", "超出上限", "次数已用完", "次数用完", "额度已用完",
        "稍后再试", "稍后重试", "明天再来", "明日再来", "明天再试",
        "toomany", "limitreached", "reachedthelimit", "reachedyourlimit", "dailylimit", "tryagainlater", "tryagaintomorrow", "doingthattoooften",
    )
    private val REACHED = Regex("(已达|达到|超过|超出)[^，,。.!！?？]{0,6}上限")
    private const val MAX_CHARS = 120

    fun find(before: Frame?, after: Frame): String? {
        val earlier = before?.elements.orEmpty().mapNotNull { it.label }.map(::compact).toSet()
        return after.elements.filter { !it.editable && !it.system }.mapNotNull { it.label }.firstOrNull { t ->
            val c = compact(t)
            c.length <= MAX_CHARS && c !in earlier && (WORDS.any { it in c } || REACHED.containsMatchIn(c))
        }?.trim()
    }

    private fun compact(t: String) = t.filterNot(Char::isWhitespace).lowercase()
}

/** Taps that reach other people (send, post, follow, apply…): these are paced, see [OutwardPacing]. */
object OutwardAction {
    private val WORDS = listOf("发送", "提交", "发布", "发表", "评论", "打招呼", "投递", "关注", "回复", "立即沟通", "send", "submit", "post", "reply", "comment", "follow")
    private const val MAX_CHARS = 10

    fun matches(label: String?): Boolean {
        val l = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS } ?: return false
        return WORDS.any { w -> if (w[0].code < 0x80) l == w || l.startsWith("$w ") || l.endsWith(" $w") else w in l }
    }
}
