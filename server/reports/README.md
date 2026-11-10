# The report collector

The keyboard can send an anonymous typing report at most once a day, where the person has turned that on (it is off
by default): [docs/reports.md](../../docs/reports.md) says what one holds. This is what takes them in.

- `collector.py` (standard library only) listens on 127.0.0.1, takes `POST /devkeyboard/report`, reads each report
  against the rules the keyboard wrote it by (`clean`), and appends what passes as one line to
  `<dir>/YYYY-MM-DD.jsonl`. Anything it does not know is dropped; anything malformed refuses the whole report. It keeps
  no address, no header and no time of day, and logs nothing of who asked. A day takes at most 20,000 reports and the
  directory at most 2 GB.
- `summarize.py <dir> [YYYY-MM]` adds them up: how often fixes and first readings stood, how far taps land from the
  key that was meant (by how tall the keys are), the commonest mistakes (written to `mistakes.tsv`).
- `test_collector.py`: `python3 -m unittest test_collector.py`, here.

## On the box

`node tool/deploy-reports.mjs` does it, in one login (the box counts them):

- `--look` changes nothing: Caddy's version, attack.fm's block, python, the port, the service.
- with no flag it installs `collector.py` into `/opt/ghostkbd-reports` and `ghostkbd-reports.service`, starts it, and
  asks it on the box itself (an empty report must be refused with 400).
- `--caddy` also writes the route into attack.fm's block of the shared Caddyfile: only the lines between its own two
  markers, after a timestamped backup, with every site and the release's files asked before and after, `caddy
  validate`, a reload, and the backup put back if anything answers differently or the route does not reach the
  collector. `Caddyfile.snippet` is what those lines are.

No access log line is written for that path (`log_skip`; `skip_log` before Caddy 2.8).

The reports are in `/var/lib/ghostkbd-reports/`. Where the collector is not up, the keyboard's reports are refused by
the site (404), and it keeps what is waiting and tries again later; nothing is lost but the days past the fortnight
it waits.

## Keeping them

Nothing in a line says whose it is, and lines cannot be linked to one another. They are still people's typing habits:
keep the directory readable by root and this service only, share numbers added up over many reports and never a
line, and delete days that have been added up and are no longer needed.
