package io.github.jackwener.dsh.core.capability

import javax.inject.Inject
import javax.inject.Singleton

/**
 * App usage knowledge (docs/AGENT-SCREEN-V2.md §4). Stub: the knowledge pack replaces this; screen
 * and app tools already call [firstVisitNotice] on app_launch and when a read shows a new foreground app.
 */
@Singleton
class AppKnowledge @Inject constructor() {
    /** Knowledge text to append to a tool result the first time [sessionId] enters [packageName], else null. */
    fun firstVisitNotice(sessionId: String, packageName: String): String? = null
}
