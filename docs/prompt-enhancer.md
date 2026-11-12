# Prompt enhancer

A plugin (`enhance`, `Enhance.kt`; a Market add-on with a page of its own in Settings; Android only). Matt,
2026-11-12, adding it himself to a list of plugins he liked: "… agent keys and "prompt enhancer"". The words are his;
what it does is my reading of them: what was typed for an AI, made into a prompt the AI will do well by.

## What it does

An **Enhance** chip in the row above the keys. Type what you want from an AI assistant or a coding agent, however
roughly, and tap it. The selection, or the whole field when nothing is selected, is replaced by a clearer prompt, and
undo puts the first one back.

"Clearer" is all it is meant to be. The AI is told (`Enhance.system`, held by `EnhanceTest`) to:

- say what is wanted first, then what the AI needs to know to do it;
- keep every particular exactly as written: names, file paths, commands, numbers, code, links, quoted text;
- set out what the prompt gives of context, limits and what done looks like, and add nothing that was not said or
  plainly meant: no requirement, fact, file, tool or step of its own, and no question back;
- not answer the prompt or carry it out, whatever it says;
- write plainly, in the prompt's language, and leave a short clear ask short.

It is also told where the prompt is going, from the app being typed in: a coding agent in a terminal (plain text, no
markdown), an AI assistant's chat, or an app the keyboard does not know.

## Settings

- **How much it writes.** Tight (the default): about as long as the first one, only clearer. Thorough: the goal, the
  context, the limits and what done looks like, each as far as the prompt gives it.
- **About you and your work.** Optional, up to 600 characters: a stack, how changes should be made, who is written
  for. It is sent with each prompt that is enhanced, and the AI is told to use a part of it only where it bears on
  the prompt.

## Where the chip is

Among the row's tools wherever the field has text. It leads the row, before any suggested words, where what is typed
is a prompt (an AI chat app, or an app the agent keys show in) and whenever text is selected.

## What leaves the phone

Tapping Enhance sends, to the AI provider chosen in Settings › AI and nowhere else: the key and model, the selection
or the whole field (up to 6,000 characters), which of three kinds of app is being typed in, and "About you and your
work". It is a ledger path of its own (`NetPath.ENHANCE`), so Settings › Offline lists it and the Offline switch
stops it. Never in a password field or while incognito. Nothing is kept of the prompt or the answer beyond the undo
step in the field.

## Checked

`EnhanceTest` (7 tests): the rules the AI is given, for every kind of app and both lengths; where it is told the
prompt is going; what goes with it and its limits; the ledger path and the guard's rule for it.
`test/prompt_enhancer_settings_test.dart`: the page, its two settings and the Market's card. `tool/net_check.sh`
passes with the new path (18 ledger paths).

Not checked: what an AI actually writes back. No prompt was enhanced through a real provider when this was written,
so how good the rewrites are is unmeasured. A debug build answers the key `debug-echo` without any AI
(`Ghostwriter.enhance`), which is for trying the chip, the row and the undo on an emulator.
