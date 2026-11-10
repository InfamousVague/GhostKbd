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

Not set up by any script: it touches the shared web server, so it is done by hand, once, with Matt's word.

1. `sudo install -D -m 0644 collector.py /opt/ghostkbd-reports/collector.py`
2. `sudo install -m 0644 ghostkbd-reports.service /etc/systemd/system/` then
   `sudo systemctl daemon-reload && sudo systemctl enable --now ghostkbd-reports`
3. `curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8377/devkeyboard/report`
   answers `400` (it is up, and `{}` is no report).
4. Put `Caddyfile.snippet` inside the attack.fm site block, above whatever serves `/devkeyboard/`. Back the Caddyfile
   up first, `caddy validate` it, reload, and check the other sites still answer. `log_skip` needs Caddy 2.8 or
   newer (it was `skip_log` before that): no access log line may be written for this path.
5. From anywhere: the same `curl` against `https://attack.fm/devkeyboard/report` answers `400`; `GET` answers `405`
   or the site's own 404, never a file.

The reports are in `/var/lib/ghostkbd-reports/`. Until this is up the keyboard's reports are refused by the site
(404), and it keeps what is waiting and tries again later; nothing is lost but the days past the fortnight it waits.

## Keeping them

Nothing in a line says whose it is, and lines cannot be linked to one another. They are still people's typing habits:
keep the directory readable by root and this service only, share numbers added up over many reports and never a
line, and delete days that have been added up and are no longer needed.
