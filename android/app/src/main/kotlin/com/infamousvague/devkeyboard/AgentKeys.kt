package com.infamousvague.devkeyboard

/**
 * The Agent keys plugin (Matt, 2026-11-12, choosing from a list of plugins to add: "I like … agent keys"): the keys a
 * coding agent in a terminal is driven with, a tap each in the row above the keys. Claude Code, Gemini CLI, Codex and
 * the like are run from a phone over SSH or in Termux, and a phone keyboard has none of these keys: the terminal
 * plugin gives Esc, Tab, Ctrl, Alt and the arrows, and these are what an agent's prompt wants on top of them.
 *
 * Each chip sends what means the same in more than one of those tools, as their own pages had it on 2026-11-12
 * (code.claude.com/docs/en/interactive-mode, geminicli.com/docs/reference/keyboard-shortcuts and the Codex CLI's
 * command reference); a key that means different things in two of them (Ctrl+Y pastes in one and approves
 * everything in another, Ctrl+O, Ctrl+B) has no chip, and neither has Ctrl+D, which ends the session:
 *
 * - `⇧Tab`: Shift+Tab, the agent's next mode (Claude Code: "Cycle permission modes"; Gemini CLI cycles its approval modes).
 * - `Esc Esc`: Escape twice, which clears a prompt being written and, on an empty one, goes back to an earlier
 *   message (Claude Code and Gemini CLI: rewind; Codex: edit the previous message).
 * - `^C`: Ctrl+C, stop what is running (twice at an empty prompt leaves the tool).
 * - `New line`: Ctrl+J, a new line in the prompt without sending it (Claude Code: "Works in any terminal without
 *   configuration"; Gemini CLI lists it too). Enter itself sends.
 * - `/`: the tool's commands. `@`: name a file. `!`: a shell command. Typed as they are.
 * - `^R`: Ctrl+R, search what was sent before (all three).
 *
 * In an AI chat app ([AppKind.ASSISTANT]) there is no agent in a terminal to drive, and the chips are what a prompt
 * typed there wants instead: a new line where enter would send the message, a code block round the selection, and
 * `@` and `/`.
 *
 * The chips show in terminal apps and AI chat apps (AppContext.kt) once the plugin is added, and in any app whose
 * profile turns them on (AppProfiles.kt). Nothing here touches Android, so what each chip sends can be read and
 * tested off the phone (AgentKeysTest); DevKeyboardService.agentTap does the pressing.
 */
object AgentKeys {
    /** A key a chip presses, as a hardware keyboard has it. The service knows each one's Android key code. */
    enum class Code { TAB, ESCAPE, C, J, R }

    /** What a chip does when tapped. */
    sealed class Does {
        /** Presses [code] [times] times, with Ctrl or Shift held: a key press, which a terminal reads as one. */
        data class Press(val code: Code, val ctrl: Boolean = false, val shift: Boolean = false, val times: Int = 1) : Does()
        /** Types [text] as the letters are typed. */
        data class Type(val text: String) : Does()
        /** Puts [open] and [close] round the selection, or leaves the cursor between them. */
        data class Wrap(val open: String, val close: String) : Does()
    }

    /** One chip: its [id] in the row, the [label] on it, what a screen reader [says] for it, and what it [does]. */
    class Chip(val id: String, val label: String, val says: String, val does: Does)

    private val MODE = Chip("mode", "⇧Tab", "Shift Tab: the agent’s next mode", Does.Press(Code.TAB, shift = true))
    private val BACK = Chip("back", "Esc Esc", "Escape twice: clear the prompt, or go back to an earlier message", Does.Press(Code.ESCAPE, times = 2))
    private val STOP = Chip("stop", "^C", "Control C: stop what is running", Does.Press(Code.C, ctrl = true))
    private val LINE = Chip("line", "New line", "A new line without sending: Control J", Does.Press(Code.J, ctrl = true))
    private val SLASH = Chip("slash", "/", "Slash: the agent’s commands", Does.Type("/"))
    private val AT = Chip("at", "@", "At: name a file", Does.Type("@"))
    private val BANG = Chip("bang", "!", "Exclamation mark: a shell command", Does.Type("!"))
    private val HISTORY = Chip("history", "^R", "Control R: search what you sent before", Does.Press(Code.R, ctrl = true))

    /** In a terminal, in the order a thumb reaches for them. */
    val TERMINAL: List<Chip> = listOf(MODE, BACK, STOP, LINE, SLASH, AT, BANG, HISTORY)

    private val CHAT_LINE = Chip("line", "New line", "A new line without sending the message", Does.Type("\n"))
    private val FENCE = Chip("code", "```", "A code block round the selection", Does.Wrap("```\n", "\n```"))

    /**
     * The chips for an app of [kind]. An AI chat app gets the prompt-writing ones, its new line only where enter
     * would send the message ([enterSends]: a field whose enter starts a new line needs no chip for it); every other
     * app the plugin shows in is taken for a terminal, which is where an agent runs.
     */
    fun chips(kind: AppKind, enterSends: Boolean): List<Chip> =
        if (kind == AppKind.ASSISTANT) listOfNotNull(CHAT_LINE.takeIf { enterSends }, FENCE, SLASH, AT) else TERMINAL

    /** Whether the plugin's chips belong in an app of [kind] by themselves, with no profile saying so. */
    fun suits(kind: AppKind): Boolean = kind == AppKind.TERMINAL || kind == AppKind.ASSISTANT
}
