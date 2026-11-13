#!/usr/bin/env python3
"""Sentences that are not Tatoeba's, for asking what a change does away from home: tool/versus/elsewhere.py [--out FILE]

The starter tables were counted from Tatoeba's sentences and every other measure here types Tatoeba's held-out
ones, so anything that leans on the tables looks better on them than it will in a stranger's writing. This writes a
file of sentences from somewhere else that needs no network: the repository's own documents (docs/*.md and
README.md), a sentence a line, without what the thumb cannot type or a table never saw as prose (code, tables,
headings, anything in backticks, links). tool/versus/whatif.py types them:

    tool/versus/elsewhere.py                                    # writes build/versus/elsewhere.txt
    tool/versus/whatif.py --file build/versus/elsewhere.txt --first 0 --sentences 1000
    tool/versus/whatif.py --file build/versus/elsewhere.txt --first 0 --sentences 1000 --set Autocorrect.HERE=false

Numbers taken from files made at different times are not comparable: the documents change, and a section added
to docs/ adds its sentences (docs/versus.md's 2026-11-13 section added two, and changed what careful scored by half a
point). --at REV reads the documents as they were at that commit, so a measure can name the file it was made on:

    tool/versus/elsewhere.py --at 9d36d4c --out build/versus/elsewhere-9d36d4c.txt

They are no person's messages either: plain sentences about a keyboard, in one voice, with its own words in them
(Gboard, autocorrect), which no list has. What they are is not Tatoeba's, and that is all they are used for.
"""
import argparse
import glob
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import thumb as th  # noqa: E402


def paragraphs(text):
    """A document's paragraphs of prose: no fenced or indented code, no table, heading or picture; a list item is
    a paragraph of its own, without its marker."""
    out, cur, fenced = [], [], False

    def end():
        if cur:
            out.append(" ".join(cur))
            cur.clear()
    for line in text.split("\n"):
        if line.strip().startswith("```"):
            fenced = not fenced
            end()
            continue
        if fenced or line.startswith("    ") or line.startswith("\t") or line.lstrip().startswith(("|", "#", "!", ">", "<")):
            end()
            continue
        item = re.match(r"^\s*(?:[-*]|\d+\.)\s+(.*)$", line)
        if item:
            end()
            cur.append(item.group(1).strip())
        elif not line.strip():
            end()
        else:
            cur.append(line.strip())
    end()
    return out


def sentences(paragraph):
    """A paragraph's sentences that are plain prose from end to end: nothing in backticks, no link, no emphasis
    marks left in. What the thumb cannot type is left to thumb.sentences to pass over."""
    text = re.sub(r"\*\*([^*]+)\*\*", r"\1", paragraph)
    text = text.replace("’", "'").replace("“", '"').replace("”", '"')
    for s in re.split(r"(?<=[.?!])\s+(?=[A-Z])", text):
        s = s.strip()
        if s and not re.search(r"[`\[\]()*_\"]", s):
            yield s


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "versus", "elsewhere.txt"))
    ap.add_argument("--at", metavar="REV", help="the documents as they were at this commit (git show), not as they are")
    args = ap.parse_args()
    if args.at:
        listed = subprocess.run(["git", "-C", ROOT, "ls-tree", "--name-only", args.at, "docs/"], capture_output=True, text=True, check=True).stdout.split()
        files = sorted(f for f in listed if f.endswith(".md")) + ["README.md"]
    else:
        files = sorted(glob.glob(os.path.join(ROOT, "docs", "*.md"))) + [os.path.join(ROOT, "README.md")]

    def read(path):
        if args.at:
            return subprocess.run(["git", "-C", ROOT, "show", "%s:%s" % (args.at, path)], capture_output=True, text=True, check=True).stdout
        with open(path, encoding="utf-8") as f:
            return f.read()
    seen, lines = set(), []
    for path in files:
        for p in paragraphs(read(path)):
            for s in sentences(p):
                if s not in seen:
                    seen.add(s)
                    lines.append(s)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("".join(s + "\n" for s in lines))
    typed, skipped = th.sentences(len(lines), path=args.out, first=0)
    print("%d sentences from %d documents: %s" % (len(lines), len(files), os.path.relpath(args.out, ROOT)))
    print("  the thumb can type %d of them (%d words); passed over: %s" % (
        len(typed), sum(len(s.words) for s in typed), ", ".join("%s %d" % kv for kv in sorted(skipped.items(), key=lambda kv: -kv[1]))))


if __name__ == "__main__":
    main()
