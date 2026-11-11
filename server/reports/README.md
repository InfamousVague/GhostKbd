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

**Two addresses reach the one collector.** Since 0.12.1 the keyboard posts to `https://ghostkbd.com/report`: that
route is part of ghostkbd.com's own block, which `node tool/deploy-site.mjs --caddy` writes (the same lines, under
`handle /report`): run it before a release that posts there goes out, or its reports are refused with a 404 and wait. The route `deploy-reports.mjs --caddy` writes, `https://attack.fm/devkeyboard/report`, is the
address 0.12.0 was released with: it stays while a 0.12.0 may still be sending, and can go once none is.

It has been there since 2026-11-11, 02:21 UTC (Matt: "Yes login to the box and run the collector"; Caddy 2.11.4,
python 3.12.3, one login for the look, the install and the checks). What was checked that day, from outside: a report
was kept (204) and its line on the box was byte for byte what `clean` leaves of it; junk was refused (400), a GET
(405), 200 KB (413); the keyboard's own "Send it now" on the emulator said it was sent and emptied what was waiting.
Both test lines were deleted again, so the first line there is a person's.

**What the web server writes when the collector is down** was looked at by stopping it for a few seconds: the route
answers 502, and Caddy's journal got no line. `log_skip` cannot take the credit, since it covers the access log
only. Caddy's journal on the box held no `http.log.error` line from any site in the seven days before, so this box
does not write handler errors at all as it is set up; the likely reason (not proved here) is the `handle_errors` in
libre.academy's block, since with an error route on a server Caddy writes handler errors at debug level. Were that
to change, a report that arrives while the collector is down would leave an error line with the sender's address
and the time in Caddy's journal, though never the report. The collector restarts itself after five seconds; if it
is ever down for longer, look for such lines (`journalctl -u caddy | grep devkeyboard/report`) and say so.

The reports are in `/var/lib/ghostkbd-reports/` (under `/var/lib/private`, which only root and the service can
enter). Where the collector is not up the keyboard is answered 502, and where the route is not written 404: either
way it keeps what is waiting and tries again later, and nothing is lost but the days past the fortnight it waits.

To add them up, on the box: `python3 /opt/ghostkbd-reports/summarize.py /var/lib/ghostkbd-reports`.

## Keeping them

Nothing in a line says whose it is, and lines cannot be linked to one another. They are still people's typing habits:
keep the directory readable by root and this service only, share numbers added up over many reports and never a
line, and delete days that have been added up and are no longer needed.
