package io.github.jackwener.dsh.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** #66: the user-supplied Node option channel. */
class EngineNodeOptionsTest {
    @Test fun `a user option reaches the merged NODE_OPTIONS and the argv copy`() {
        val merged = EngineRuntimeEnv.mergeNodeOptions(
            "--max-old-space-size=768",
            EngineRuntimeEnv.RUNTIME_NODE_OPTIONS + EngineRuntimeEnv.parseExtraNodeOptions("  --jitless  "),
        )
        assertEquals("--max-old-space-size=768 --network-family-autoselection-attempt-timeout=1500 --jitless", merged)
    }

    @Test fun `no input adds nothing and keeps the app's own options`() {
        assertEquals(emptyList<String>(), EngineRuntimeEnv.parseExtraNodeOptions(null))
        assertEquals(emptyList<String>(), EngineRuntimeEnv.parseExtraNodeOptions("   \n\t "))
        assertEquals(
            "--max-old-space-size=768 --network-family-autoselection-attempt-timeout=1500",
            EngineRuntimeEnv.mergeNodeOptions("--max-old-space-size=768", EngineRuntimeEnv.RUNTIME_NODE_OPTIONS + EngineRuntimeEnv.parseExtraNodeOptions("")),
        )
    }

    @Test fun `code-loading flags and stray values are refused`() {
        assertEquals(
            listOf("--jitless", "--stack-trace-limit=64"),
            EngineRuntimeEnv.parseExtraNodeOptions(
                "--require=/tmp/x.cjs -r /tmp/y.cjs --eval=1 -e 1 --print=1 -p 1 --import=data:1 --expose-internals --jitless --stack-trace-limit=64",
            ),
        )
    }

    @Test fun `a user value for an app flag replaces it, duplicates collapse`() {
        assertEquals(
            "--max-old-space-size=1536",
            EngineRuntimeEnv.mergeNodeOptions("--max-old-space-size=768", EngineRuntimeEnv.parseExtraNodeOptions("--max-old-space-size=1536")),
        )
        assertEquals(listOf("--jitless"), EngineRuntimeEnv.parseExtraNodeOptions("--jitless --jitless"))
    }
}
