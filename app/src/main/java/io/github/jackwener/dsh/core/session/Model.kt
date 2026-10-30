package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.model.Risk
import kotlinx.serialization.json.JsonObject

/** One entry of a session's event log: a dsh `session.event`, or an app-local `app/...` record. */
data class LoggedEvent(val seq: Long, val type: String, val time: Long, val data: JsonObject)

data class PendingApproval(
    val requestKey: String,
    val sessionId: String,
    val toolName: String,
    val callId: String?,
    val reason: String?,
    val arguments: String?,
    val risk: Risk,
    /** dsh shows 始终允许 only when the host offers it; we offer a per-session grant except for dangerous tools. */
    val canAlwaysAllow: Boolean,
)

data class PendingQuestion(
    val requestKey: String,
    val sessionId: String,
    val questions: List<Question>,
) {
    data class Option(val label: String, val description: String?)
    data class Question(
        val id: String,
        val header: String?,
        val text: String,
        val detail: String?,
        val options: List<Option>,
        val multi: Boolean,
    )
}

/** A device tool hit a missing Android permission; the call is held until the user grants or declines. */
data class PendingPermission(
    val requestKey: String,
    val sessionId: String,
    val toolName: String,
    /** PermissionCatalog id, or null when only a description is known (e.g. Shizuku). */
    val permissionId: String?,
    val what: String,
)

/**
 * A step the agent hands to the user (login, captcha, payment, protected page…). [Asking]: 我来处理 / 跳过;
 * [HandedOver]: the app is on the user's screen ([moved]) — 处理好了 / 我自己做完了 / 不做了.
 */
data class PendingTakeover(
    val requestKey: String,
    val sessionId: String,
    val toolName: String,
    val reason: String,
    /** login / captcha / payment / secure_page / choose / other. */
    val kind: String = "other",
    val stage: Stage = Stage.Asking,
    val moved: Boolean = false,
) {
    enum class Stage { Asking, HandedOver }
}

/** The user's answers on the takeover card, in card order. */
enum class TakeoverAnswer { Start, Skip, Handled, DoneByUser, Abort }

data class QueuedMessage(val id: String, val text: String)

/** Live state of one session: the persisted log plus everything that only exists while the app runs. */
data class SessionLive(
    val sessionId: String,
    val title: String = "",
    val events: List<LoggedEvent> = emptyList(),
    val running: Boolean = false,
    /** Set when this session's run is waiting for the engine process to come up. */
    val startingEngine: Boolean = false,
    val runStartedAt: Long? = null,
    /**
     * The card shown in the composer slot: the oldest pending request of each kind. Requests can
     * overlap (parallel read tools, subagents), so every kind is a FIFO ([approvals], ...) and the
     * head is mirrored here; always change them through [withPending] / [withoutPending].
     */
    val approval: PendingApproval? = null,
    val question: PendingQuestion? = null,
    val permission: PendingPermission? = null,
    val takeover: PendingTakeover? = null,
    val approvals: List<PendingApproval> = emptyList(),
    val questions: List<PendingQuestion> = emptyList(),
    val permissions: List<PendingPermission> = emptyList(),
    val takeovers: List<PendingTakeover> = emptyList(),
    val waitingForUnlock: Boolean = false,
    /** The turn failed for lack of network; the session waits to be continued once the phone is back online (SessionStore.waitForNetwork). */
    val waitingForNetwork: Boolean = false,
    val queue: List<QueuedMessage> = emptyList(),
    /** Queued messages handed back to the composer after the run ended without sending them (stop / failure). */
    val restoredDraft: String? = null,
    val preset: PermissionPreset = PermissionPreset.Full,
    val exists: Boolean = false,
) {
    val hasPendingRequest: Boolean get() = approvals.isNotEmpty() || questions.isNotEmpty() || permissions.isNotEmpty() || takeovers.isNotEmpty()

    /** Appends a pending request of any kind to its FIFO. */
    fun withPending(request: Any): SessionLive = when (request) {
        is PendingApproval -> copy(approvals = approvals + request)
        is PendingQuestion -> copy(questions = questions + request)
        is PendingPermission -> copy(permissions = permissions + request)
        is PendingTakeover -> copy(takeovers = takeovers + request)
        else -> error("not a pending request: $request")
    }.synced()

    /** Removes the pending request [requestKey] (of whatever kind); the next one, if any, becomes the visible card. */
    fun withoutPending(requestKey: String): SessionLive = copy(
        approvals = approvals.filterNot { it.requestKey == requestKey },
        questions = questions.filterNot { it.requestKey == requestKey },
        permissions = permissions.filterNot { it.requestKey == requestKey },
        takeovers = takeovers.filterNot { it.requestKey == requestKey },
    ).synced()

    /** Replaces a pending takeover in place (its card moves to the next stage). */
    fun withTakeover(t: PendingTakeover): SessionLive = copy(takeovers = takeovers.map { if (it.requestKey == t.requestKey) t else it }).synced()

    fun withoutAnyPending(): SessionLive = copy(approvals = emptyList(), questions = emptyList(), permissions = emptyList(), takeovers = emptyList()).synced()

    /**
     * The run is over without having sent its queued messages (stop, failure, engine loss): the
     * session goes idle and the queue moves back into the composer instead of firing later, after
     * whatever the user sends next.
     */
    fun idle(): SessionLive = copy(
        running = false, startingEngine = false, runStartedAt = null, waitingForUnlock = false, waitingForNetwork = false, queue = emptyList(),
        restoredDraft = (listOfNotNull(restoredDraft) + queue.map { it.text }).joinToString("\n\n").ifEmpty { null },
    )

    private fun synced() = copy(approval = approvals.firstOrNull(), question = questions.firstOrNull(),
        permission = permissions.firstOrNull(), takeover = takeovers.firstOrNull())
}

/** What the sidebar status slot shows (dsh StateDot precedence). */
enum class RowStatus { Approval, Question, Running, Idle }
