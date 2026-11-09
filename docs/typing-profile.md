# Ghost typing profile (`ghost-typing-profile`, version 1)

An open JSON file that says how one person types: where they aim at each key, the keys they hit by mistake, the
words they use that a dictionary lacks, and how they write. Ghost Keyboard **exports** one (Settings › Typing
profile › Export) and **imports** one (Import, or open/share any `.json` file with Ghost Keyboard). Anything else —
another keyboard, a script, an AI model reading someone's typing logs — can write one, and Ghost Keyboard tunes itself
to it.

The schema is [`typing-profile.schema.json`](typing-profile.schema.json)
(`https://raw.githubusercontent.com/InfamousVague/GhostKbd/main/docs/typing-profile.schema.json`).

Every section is optional; a file may hold only `slips`, or only `style`. Unknown fields are ignored, so a report can
carry more than Ghost Keyboard reads.

```json
{
  "format": "ghost-typing-profile",
  "version": 1,
  "created": "2026-11-09T18:00:00Z",
  "source": { "app": "Typing coach", "model": "claude-opus-5-5" },
  "report": "Thumbs land low and to the left on the top row; i/o and a/s are the commonest slips.",
  "layout": "qwerty",
  "keys": {
    "i": { "dx": 0.18, "dy": 0.10, "samples": 240 },
    "a": { "dx": -0.12, "dy": 0.05, "samples": 310 }
  },
  "slips": [
    { "pressed": "o", "meant": "i", "count": 14 },
    { "pressed": "s", "meant": "a", "count": 6, "x": 0.07, "y": 0.41 }
  ],
  "words": { "learned": ["kotlin", "posthog", "gradle"] },
  "style": {
    "profile": "They text in lower case, no full stops, short lines...",
    "samples": [ { "text": "on it, pushing now", "kind": "chat" } ]
  }
}
```

## Fields

| Field | Meaning |
| --- | --- |
| `format`, `version` | Always `"ghost-typing-profile"` and `1`. A file without them is refused. |
| `created` | When it was made, ISO 8601. Shown on import. |
| `source` | Who made it: `app`, and `model` where an AI wrote it. Shown on import. |
| `report` | A few sentences for the person, shown in Settings after the import. |
| `layout` | The layout the key data is for. Only `"qwerty"` is read in version 1. |
| `keys` | Per letter (`a`–`z`, lower case): where the person aims at it, as an offset from the key's middle in **fractions of the key** (`dx` right is positive, `dy` down is positive; `0.5` is the key's edge), and how many touches it is drawn from. |
| `slips` | Keys hit instead of the one meant: `pressed`, `meant`, `count`. `x` and `y` (fractions of the letters' area, 0–1 from the top left) place one on the heatmap; without them the slip still teaches the aim, toward `pressed`. |
| `words.learned` | Words autocorrect should leave alone. |
| `style.profile` | A description of how the person writes and texts, for Mockingbird and Ghostwriter (plain text or Markdown, under 6,000 characters). |
| `style.samples` | Things the person wrote: `text`, and `kind` `"chat"` (texting) or `"writing"`. |

## How Ghost Keyboard takes one in

- **keys**: blended with what it has learned, each side weighted by its touches (`samples`), so a big report moves a
  new keyboard's aims and nudges an experienced one's. An aim is used at most a third of a key from the middle, and
  only once it stands on 15 touches.
- **slips**: each counts three times for the letter meant, at the point given or, without one, part way toward the
  key pressed; those with `x`/`y` show on the heatmap.
- **words.learned**: added to autocorrect's learned words.
- **style.profile**: becomes Mockingbird's style profile (its source shown as the file's); **style.samples** join
  Mockingbird's samples.

It needs the plugins it feeds: keys and slips go to Adaptive keys, style to Mockingbird; a section whose plugin isn't
added is reported as skipped, and nothing of it is kept.

## Privacy

An export holds no message samples unless "Include my message samples" is on for it. Nothing is uploaded: the file
goes where the person saves or shares it.
