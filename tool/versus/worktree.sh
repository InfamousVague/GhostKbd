#!/bin/sh
# A worktree of one's own for a change to the decoder: tool/versus/worktree.sh <name>
#
# Makes ~/Development/Apps/ghost-kbd-versus-<name> on a new branch versus-<name>, cut from the branch `versus` as it
# stands, and gives it the one file the tools need that git does not hold (the held-out sentences, 4 MB). Says
# where it is. Several people (or agents) can then change autocorrect at once without sharing a folder; each
# measures with tool/versus/scoreboard.py in their own. Nothing is pushed.
set -e
name="$1"
[ -n "$name" ] || { echo "usage: tool/versus/worktree.sh <name>" >&2; exit 2; }
here="$(cd "$(dirname "$0")/../.." && pwd)"
to="$(dirname "$here")/ghost-kbd-versus-$name"
[ -e "$to" ] && { echo "$to is there already" >&2; exit 1; }
git -C "$here" worktree add -q "$to" -b "versus-$name" versus
mkdir -p "$to/build/typing-bench"
cp "$here/build/typing-bench/en-heldout.txt" "$to/build/typing-bench/en-heldout.txt"
echo "$to"
