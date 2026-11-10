package io.github.jackwener.dsh.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** #66: the no-tombstone fallback that keeps a crashed engine's logcat lines. */
class EngineDiagnosticsLogcatTest {
    // logcat -v threadtime: MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: message
    private val dump = listOf(
        "11-10 00:52:32.100  1234  1300 I ActivityManager: Start proc 31566:engine/u0a123",
        "11-10 00:52:32.900 31566 31566 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0",
        "11-10 00:52:32.901 31566 31566 F DEBUG   : pid: 31566, tid: 31566, name: dsh  >>> io.github.jackwener.dsh <<<",
        "11-10 00:52:33.000   489   489 I chatty  : uid 10123 expire 3 lines",
        "11-10 00:52:33.500 31566 31570 F libc    : backtrace:",
    ).joinToString("\n")

    @Test fun `keeps only the dead pid's lines in order`() {
        assertEquals(
            listOf(
                "11-10 00:52:32.900 31566 31566 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0",
                "11-10 00:52:32.901 31566 31566 F DEBUG   : pid: 31566, tid: 31566, name: dsh  >>> io.github.jackwener.dsh <<<",
                "11-10 00:52:33.500 31566 31570 F libc    : backtrace:",
            ),
            EngineDiagnostics.filterLogcatForPid(dump, 31566).lines(),
        )
    }

    @Test fun `a pid with no lines yields an empty slice`() {
        assertEquals("", EngineDiagnostics.filterLogcatForPid(dump, 999))
        assertEquals("", EngineDiagnostics.filterLogcatForPid("", 31566))
    }

    @Test fun `the slice is capped to the last lines`() {
        val many = (1..50).joinToString("\n") { "11-10 00:52:32.100 31566 31566 I t: line $it" }
        val slice = EngineDiagnostics.filterLogcatForPid(many, 31566, maxLines = 5).lines()
        assertEquals(5, slice.size)
        assertEquals("11-10 00:52:32.100 31566 31566 I t: line 50", slice.last())
    }

    @Test fun `padded columns from threadtime still match`() {
        assertEquals(
            "11-10 00:52:32.100     7     7 I t: tiny",
            EngineDiagnostics.filterLogcatForPid("11-10 00:52:32.100     7     7 I t: tiny", 7),
        )
    }
}
