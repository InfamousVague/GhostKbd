package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Agent keys plugin (AgentKeys.kt): which chips an app gets, and what each one sends. What a terminal does with
 * a key press is the terminal's; what is held here is the press itself, so a chip can never quietly come to mean
 * another key.
 */
class AgentKeysTest {
    private fun press(chip: AgentKeys.Chip) = chip.does as AgentKeys.Does.Press
    private fun terminal(id: String) = AgentKeys.TERMINAL.first { it.id == id }

    @Test fun `a terminal gets the agent's keys, in the order a thumb reaches for them`() {
        val chips = AgentKeys.chips(AppKind.TERMINAL, enterSends = true)
        assertEquals(listOf("⇧Tab", "Esc Esc", "^C", "New line", "/", "@", "!", "^R"), chips.map { it.label })
        // Whether enter sends is not asked of a terminal: there it always does, and the new line is always offered.
        assertEquals(chips.map { it.id }, AgentKeys.chips(AppKind.TERMINAL, enterSends = false).map { it.id })
    }

    @Test fun `each press is the key the tools' own pages name`() {
        assertEquals(AgentKeys.Does.Press(AgentKeys.Code.TAB, shift = true), terminal("mode").does)
        assertEquals(AgentKeys.Does.Press(AgentKeys.Code.ESCAPE, times = 2), terminal("back").does)
        assertEquals(AgentKeys.Does.Press(AgentKeys.Code.C, ctrl = true), terminal("stop").does)
        // Ctrl+J, not enter: enter sends the prompt.
        assertEquals(AgentKeys.Does.Press(AgentKeys.Code.J, ctrl = true), terminal("line").does)
        assertEquals(AgentKeys.Does.Press(AgentKeys.Code.R, ctrl = true), terminal("history").does)
        // One press each but for Esc Esc, and never Ctrl and Shift together.
        for (chip in AgentKeys.TERMINAL) (chip.does as? AgentKeys.Does.Press)?.let { p ->
            assertEquals(chip.id, if (chip.id == "back") 2 else 1, p.times)
            assertFalse(chip.id, p.ctrl && p.shift)
        }
    }

    @Test fun `the marks are typed as they are`() {
        assertEquals(AgentKeys.Does.Type("/"), terminal("slash").does)
        assertEquals(AgentKeys.Does.Type("@"), terminal("at").does)
        assertEquals(AgentKeys.Does.Type("!"), terminal("bang").does)
    }

    @Test fun `no chip ends the session or means two things`() {
        // Ctrl+D leaves the tool; Ctrl+Y pastes in one tool and approves everything in another; Ctrl+O and Ctrl+B differ too.
        val pressed = AgentKeys.TERMINAL.mapNotNull { it.does as? AgentKeys.Does.Press }.map { it.code }.toSet()
        assertEquals(setOf(AgentKeys.Code.TAB, AgentKeys.Code.ESCAPE, AgentKeys.Code.C, AgentKeys.Code.J, AgentKeys.Code.R), pressed)
        assertEquals(pressed, AgentKeys.Code.values().toSet())
    }

    @Test fun `an AI chat app gets what a prompt wants, and a new line only where enter would send`() {
        val sends = AgentKeys.chips(AppKind.ASSISTANT, enterSends = true)
        assertEquals(listOf("New line", "```", "/", "@"), sends.map { it.label })
        // There the new line is typed, not Ctrl+J: an app's text field is no terminal.
        assertEquals(AgentKeys.Does.Type("\n"), sends[0].does)
        assertEquals(AgentKeys.Does.Wrap("```\n", "\n```"), sends[1].does)
        assertEquals(listOf("```", "/", "@"), AgentKeys.chips(AppKind.ASSISTANT, enterSends = false).map { it.label })
        assertTrue(sends.none { it.does is AgentKeys.Does.Press })
    }

    @Test fun `any other app the profile turns them on in is taken for a terminal`() {
        for (kind in listOf(AppKind.OTHER, AppKind.CODE, AppKind.MARKDOWN, AppKind.CHAT)) {
            assertEquals(kind.name, AgentKeys.TERMINAL.map { it.id }, AgentKeys.chips(kind, enterSends = true).map { it.id })
        }
    }

    @Test fun `by themselves they show in terminals and AI chat apps only`() {
        assertTrue(AgentKeys.suits(AppKind.TERMINAL))
        assertTrue(AgentKeys.suits(AppKind.ASSISTANT))
        for (kind in listOf(AppKind.OTHER, AppKind.CODE, AppKind.MARKDOWN, AppKind.CHAT)) assertFalse(kind.name, AgentKeys.suits(kind))
    }

    @Test fun `ids are their own, and every chip says what it is`() {
        for (kind in AppKind.values()) for (sends in listOf(true, false)) {
            val chips = AgentKeys.chips(kind, sends)
            assertEquals(chips.map { it.id }.distinct(), chips.map { it.id })
            for (chip in chips) assertTrue(chip.id, chip.says.length > chip.label.length)
        }
    }

    @Test fun `the apps are known by their packages`() {
        assertEquals(AppKind.TERMINAL, AppContext.kind("com.termux"))
        assertEquals(AppKind.ASSISTANT, AppContext.kind("com.anthropic.claude"))
        assertEquals(AppKind.ASSISTANT, AppContext.kind("com.openai.chatgpt"))
        // A chat between people is not an assistant, and an unknown app is neither.
        assertEquals(AppKind.CHAT, AppContext.kind("com.whatsapp"))
        assertEquals(AppKind.OTHER, AppContext.kind("com.example.notes"))
        assertEquals(AppKind.OTHER, AppContext.kind(null))
    }

    @Test fun `the plugin is one of the keyboard's, off until added, and a profile can set it`() {
        assertTrue(Plugins.AGENT in Plugins.ALL)
        assertFalse(Plugins.AGENT in (KeyboardPrefs.DEFAULTS[KeyboardPrefs.PLUGINS] as String).split(','))
        assertTrue("agent" in AppProfiles.FIELDS)
    }
}
