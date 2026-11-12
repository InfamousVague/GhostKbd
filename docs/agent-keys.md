# Agent keys

A plugin (`agent`, `AgentKeys.kt`; Settings › Row tools › Agent keys, off until switched on; Android only). It puts
the keys a coding agent in a terminal is driven with in the row above the keys. Matt, 2026-11-12, choosing from a
list of plugins to add: "I like … agent keys".

Claude Code, Gemini CLI, Codex and the like are run from a phone over SSH or in Termux. The terminal plugin already
gives Esc, Tab, sticky Ctrl and Alt and the arrows; an agent's prompt wants a few more, and a phone keyboard has none
of them.

## In a terminal

| Chip | What it sends | What it is for |
| --- | --- | --- |
| `⇧Tab` | Shift+Tab | The agent's next mode. Claude Code: "Cycle permission modes". Gemini CLI cycles its approval modes. |
| `Esc Esc` | Escape, twice | Clears a prompt being written; on an empty one, goes back to an earlier message (Claude Code and Gemini CLI rewind, Codex edits the previous message). |
| `^C` | Ctrl+C | Stops what is running. At an empty prompt a second one leaves the tool. |
| `New line` | Ctrl+J | A new line in the prompt without sending it. Claude Code: "Works in any terminal without configuration". Gemini CLI lists it too. Enter itself sends. |
| `/` | the character | The tool's commands. |
| `@` | the character | Names a file. |
| `!` | the character | A shell command. |
| `^R` | Ctrl+R | Searches what was sent before. |

Each chip sends what means the same in more than one of the three tools, as their own pages had it on 2026-11-12:
[Claude Code's interactive mode](https://code.claude.com/docs/en/interactive-mode),
[Gemini CLI's keyboard shortcuts](https://geminicli.com/docs/reference/keyboard-shortcuts/) and the Codex CLI's
command reference (which states fewer keys than the other two: its `!`, `@`, `/`, Ctrl+R and the double Escape).
A key that means different things in two of them has no chip: Ctrl+Y pastes in Claude Code and approves everything in
Gemini CLI, and Ctrl+O and Ctrl+B differ too. Neither has Ctrl+D, which ends the session: the terminal plugin's
sticky Ctrl still reaches all of them (tap Ctrl, then the letter).

The presses are key presses with their modifiers, as the terminal row's Esc and Ctrl send theirs
(`DevKeyboardService.agentTap`), so what they do is the terminal app's: a terminal app is expected to turn
Shift+Tab into the back-tab sequence and Ctrl+J into a line feed, as it does for a hardware keyboard (not tried on a
device, see below). With the terminal plugin on as well, the agent keys come first and the terminal's own `/` steps
aside for theirs.

## In an AI chat app

There is no agent in a terminal to drive, and the chips are what a prompt typed there wants instead:

- `New line`: a line break, typed, only where Enter would send the message (a field whose Enter starts a new line
  needs no chip for it).
- `` ``` ``: a code block round the selection, or an empty one with the cursor inside.
- `/` and `@`.

## Where they show

- Terminal apps and AI chat apps, as `AppContext.kt` knows them by package. The AI chat apps' packages were written
  from memory on 2026-11-12 and not checked against a store.
- Any app whose per-app profile turns Agent keys on (Settings › Per-app profiles), which is the way to add a
  terminal app the list lacks. Such an app is taken for a terminal unless it is a known AI chat app. A profile can
  also turn them off in one app.

Nothing is sent anywhere and nothing is kept: the plugin has no setting but its switch, and no server.

## Checked

`AgentKeysTest` (10 tests): which chips each kind of app gets, the exact press of each, that no chip presses
Ctrl+D or a key that means two things, and that the plugin is off until added. `test/agent_keys_settings_test.dart`:
the switch in Row tools and the Market's card.

Not checked: the chips against a real Claude Code, Gemini CLI or Codex session. What each tool does with the keys
is taken from its documentation.
