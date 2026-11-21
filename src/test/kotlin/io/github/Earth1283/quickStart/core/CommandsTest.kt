package io.github.Earth1283.quickStart.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CommandsTest {
    @Test
    fun `homes default to home and names normalize independently of case`() {
        assertEquals("home", FeatureCommand.HOME.parse(emptyArray()))
        assertEquals("the_base-2", FeatureCommand.SETHOME.parse(arrayOf("The_Base-2")))
        assertEquals("checkpoint", FeatureCommand.WARP.parse(arrayOf("Checkpoint")))
        assertFailsWith<CommandProblem> { FeatureCommand.SETHOME.parse(arrayOf("a.b")) }
        assertFailsWith<CommandProblem> { FeatureCommand.DELHOME.parse(arrayOf("a".repeat(33))) }
    }

    @Test
    fun `argument rules reject missing and surplus values`() {
        assertFailsWith<CommandProblem> { FeatureCommand.TPA.parse(emptyArray()) }
        assertFailsWith<CommandProblem> { FeatureCommand.WARP.parse(arrayOf("here", "there")) }
        assertFailsWith<CommandProblem> { FeatureCommand.WARPS.parse(arrayOf("here")) }
        assertEquals("Alex", FeatureCommand.TPAHERE.parse(arrayOf("Alex")))
        assertNull(FeatureCommand.TPACCEPT.parse(emptyArray()))
    }
}
