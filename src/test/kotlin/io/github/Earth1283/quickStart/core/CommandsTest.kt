package io.github.Earth1283.quickStart.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `destination and request menus accept positive pages and handle shrinking lists`() {
        for (command in listOf(FeatureCommand.HOMES, FeatureCommand.WARPS, FeatureCommand.TPREQUESTS)) {
            assertNull(command.parse(emptyArray()))
            assertEquals("2", command.parse(arrayOf("2")))
            assertFailsWith<CommandProblem> { command.parse(arrayOf("0")) }
            assertFailsWith<CommandProblem> { command.parse(arrayOf("2", "3")) }
            assertFailsWith<CommandProblem> { command.parse(arrayOf("9999999999999")) }
        }
        val entries = (1..11).toList()
        assertEquals(ChatPage((6..10).toList(), 2, 3), entries.chatPage(2))
        assertEquals(ChatPage(listOf(11), 3, 3), entries.chatPage(Int.MAX_VALUE))
        assertEquals(ChatPage(emptyList(), 1, 0), emptyList<Int>().chatPage(2))
    }

    @Test
    fun `completions filter prefixes, remove duplicates, and limit results`() {
        assertEquals(listOf("Base", "Beach"), matchingSuggestions(listOf("Docks", "Base", "Beach", "base"), "b"))
        assertEquals(50, matchingSuggestions((1..80).map { "home-$it" }, "HOME").size)
        assertEquals(listOf("1", "10", "11", "12"), pageSuggestions(60, "1"))
        assertTrue(pageSuggestions(0, "").isEmpty())
    }
}
