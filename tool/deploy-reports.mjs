#!/usr/bin/env node
/**
 * Puts the report collector (server/reports/) on the box, in one ssh session (Matt, 2026-11-10: "allow anonymous
 * reporting of swipe and text metrics and mistakes", then "Yes please release it all").
 *
 *   node tool/deploy-reports.mjs --look     what the box has, changing nothing: Caddy's version, attack.fm's block,
 *                                           python, the port, the service
 *   node tool/deploy-reports.mjs            collector.py into /opt/ghostkbd-reports and its systemd unit, started,
 *                                           and asked on the box itself that it answers (no web server change)
 *   node tool/deploy-reports.mjs --caddy    that, and the route POST /devkeyboard/report written into attack.fm's
 *                                           block in /etc/caddy/Caddyfile. Needed once, and again if the route changes.
 *   node tool/deploy-reports.mjs --print    the script the box would run, and nothing sent
 *   --keep-connection                       leave the shared connection open for a step that follows within two minutes
 *
 * It uses Ghost.md's box account and its own deploy code, as tool/deploy-site.mjs does (--glyph <path> or GLYPH_ROOT).
 *
 * WHICH ADDRESS: since 0.12.1 the keyboard posts to https://ghostkbd.com/report, a route in ghostkbd.com's own block
 * (tool/deploy-site.mjs --caddy writes it, to this same collector). The route --caddy writes here is the address
 * 0.12.0 was released with, https://attack.fm/devkeyboard/report: it stays while a 0.12.0 may still be sending.
 *
 * THE CADDYFILE IS SHARED, AND SO IS attack.fm'S BLOCK (AttackFM's site, Ghost.md's files, this keyboard's releases).
 * --caddy touches only the lines between its own two markers, put straight after the block's opening line: a
 * timestamped backup; every site asked for its page before, and attack.fm for the files that must go on answering;
 * the lines written; `caddy validate`; a reload; every site and file asked again, and the new route asked for a 400
 * (the collector's answer to an empty report). A failed validate, a site or file answering differently, or a route
 * that does not reach the collector puts the backup back. Running it again replaces the marked lines; it never adds
 * a second set.
 *
 * Nothing of a request to that path is logged by the web server (`log_skip`, or `skip_log` before Caddy 2.8), and
 * the collector logs nothing of who asked. The box counts logins: this spends one.
 */
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdtempSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SOURCE = join(ROOT, 'server', 'reports');
const HOME = '/opt/ghostkbd-reports';
const UNIT = 'ghostkbd-reports';
const PORT = 8377;
const DOMAIN = 'attack.fm';
const ROUTE = '/devkeyboard/report';
const args = process.argv.slice(2);
const look = args.includes('--look');
const withCaddy = args.includes('--caddy');
const printOnly = args.includes('--print');
const fail = (message) => { console.error(`x ${message}`); process.exit(1); };

// What goes to the box: the collector, its unit, and the script that adds the reports up. Nothing else of the folder.
const SHIPPED = ['collector.py', `${UNIT}.service`, 'summarize.py'];
for (const name of SHIPPED) if (!existsSync(join(SOURCE, name))) fail(`No server/reports/${name} to ship.`);

const at = args.indexOf('--glyph');
const glyph = [at >= 0 ? args[at + 1] : null, process.env.GLYPH_ROOT, join(ROOT, '..', 'glyph'), join(homedir(), 'Development/Apps/glyph')]
  .filter(Boolean)
  .find((dir) => existsSync(join(dir, 'tauri/scripts/lib/box.mjs')));
if (!glyph && !printOnly) fail('No Ghost.md checkout found for its box code (--glyph <path> or GLYPH_ROOT).');

const dom = DOMAIN.replace('.', '[.]');
const BEGIN = '# ghostkbd-reports: begin (tool/deploy-reports.mjs writes these lines; server/reports/README.md)';
const END = '# ghostkbd-reports: end';

// Read-only: what a first look needs to know before anything is written.
const LOOK = `SUDO=; [ "$(id -u)" -eq 0 ] || SUDO=sudo
CF=/etc/caddy/Caddyfile
echo "== caddy: $(caddy version 2>&1 | head -1), $(systemctl is-active caddy)"
echo "== python: $(python3 --version 2>&1), systemd $(systemctl --version | head -1 | awk '{print $2}')"
echo "== port ${PORT}: $(ss -ltnH 'sport = :${PORT}' 2>/dev/null | awk '{print $4}' | tr '\\n' ' ')"
echo "== service: $(systemctl is-active ${UNIT} 2>&1); reports kept: $($SUDO sh -c 'cat /var/lib/${UNIT}/*.jsonl 2>/dev/null | wc -l')"
echo "== the sites on the Caddyfile:"; $SUDO grep -n -E '^[a-z0-9][a-z0-9.:/, -]*[{]' "$CF" | sed 's/^/  /'
echo "== logging and our own lines, by line number:"; $SUDO grep -n -E '^[[:space:]]*log([[:space:]]|$)|log_skip|skip_log|ghostkbd-reports' "$CF" | sed 's/^/  /' || true
echo "== ${DOMAIN}'s block, as it stands (addresses and directives only: values after a token or a hash are not shown):"
$SUDO awk -v start='^${dom}[ ,{]' '$0 ~ start { on = 1 } on { line = $0; if (line ~ /(basicauth|basic_auth|bcrypt|[Aa]uthorization|[Tt]oken|[Ss]ecret|[Pp]assword|\\$2[aby]\\$)/) line = "  (a line with a credential in it: not shown)"; print "  " line } on && /^}/ { exit }' "$CF"
`;

// Run on the box. Reads the collector and its unit as a tarball on stdin.
const INSTALL = `set -e
SUDO=; [ "$(id -u)" -eq 0 ] || SUDO=sudo
STAGE="$HOME/.ghostkbd-reports-stage"
rm -rf "$STAGE"; mkdir -p "$STAGE"; tar xzf - -C "$STAGE"
[ -f "$STAGE/collector.py" ] && [ -f "$STAGE/${UNIT}.service" ] && [ -f "$STAGE/summarize.py" ] || { echo "x the collector arrived without its files"; exit 1; }
[ -x /usr/bin/python3 ] || { echo "x no /usr/bin/python3 on the box (the unit starts the collector with it)"; exit 1; }
/usr/bin/python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' || { echo "x the box's python3 is older than 3.8"; exit 1; }
/usr/bin/python3 -m py_compile "$STAGE/collector.py" "$STAGE/summarize.py" || { echo "x the collector does not compile on the box's python"; exit 1; }
rm -rf "$STAGE/__pycache__"
# The port is ours already, or nobody's.
if ss -ltnH 'sport = :${PORT}' 2>/dev/null | grep -q .; then
  systemctl is-active --quiet ${UNIT} || { echo "x port ${PORT} is taken by something that is not ${UNIT}"; ss -ltnpH 'sport = :${PORT}'; exit 1; }
fi
$SUDO install -D -m 0644 "$STAGE/collector.py" ${HOME}/collector.py
$SUDO install -m 0644 "$STAGE/summarize.py" ${HOME}/summarize.py
$SUDO install -m 0644 "$STAGE/${UNIT}.service" /etc/systemd/system/${UNIT}.service
rm -rf "$STAGE"
$SUDO systemctl daemon-reload
$SUDO systemctl enable ${UNIT} >/dev/null 2>&1
$SUDO systemctl restart ${UNIT}
sleep 2
# A collector that will not run is not left trying every five seconds: it is stopped, and says why.
down() { echo "x $1:"; $SUDO journalctl -u ${UNIT} -n 25 --no-pager || true; $SUDO systemctl disable --now ${UNIT} >/dev/null 2>&1 || true; exit 1; }
systemctl is-active --quiet ${UNIT} || down "${UNIT} did not stay up (it is switched off again)"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:${PORT}${ROUTE} || true)
[ "$code" = 400 ] || down "the collector answered $code to an empty report, not 400 (it is switched off again)"
echo "ok ${UNIT} is up on 127.0.0.1:${PORT} (an empty report is refused with 400); reports are kept in /var/lib/${UNIT}"
[ "${withCaddy ? 1 : 0}" = 1 ] || exit 0
CF=/etc/caddy/Caddyfile
$SUDO grep -q -E '^${dom}[ ,{]' "$CF" || { echo "x no ${DOMAIN} block on the Caddyfile"; exit 1; }
# No access log line for this path: the directive's name changed at Caddy 2.8.
SKIP=log_skip
caddy version | sed 's/^v//' | awk -F'[. ]' '{ exit !($1 < 2 || ($1 == 2 && $2 < 8)) }' && SKIP=skip_log

# Every site on the Caddyfile asked for its page, and ${DOMAIN} for the files that must go on answering, on the box itself.
probe() {
  for h in $($SUDO grep -E '^[a-z0-9][a-z0-9.:/, -]*[{]' "$CF" | sed 's/{.*//; s/,/ /g'); do
    h=\${h#http://}; h=\${h#https://}; h=\${h%%:*}
    [ -n "$h" ] || continue
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --resolve "$h:443:127.0.0.1" "https://$h/" || true)
    echo "$h $code"
  done | sort
  for p in /devkeyboard/apk.json /devkeyboard/ /devkeyboard/devkeyboard.apk /glyph/apk.json /glyph/ /devkeyboard/nothing-here; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -I --max-time 10 --resolve "${DOMAIN}:443:127.0.0.1" "https://${DOMAIN}$p" || true)
    echo "${DOMAIN}$p $code"
  done
}
BACKUP="$CF.bak-$(date +%Y%m%d-%H%M%S)"
$SUDO cp -a "$CF" "$BACKUP"
echo "ok backup $BACKUP"
BEFORE=$(probe)
echo "before:"; echo "$BEFORE" | sed 's/^/  /'
restore() { echo "x $1: putting $BACKUP back"; $SUDO cp -a "$BACKUP" "$CF"; $SUDO systemctl reload caddy || true; exit 1; }
# Our own lines out, if they are there, and in again straight after the block's opening line: nothing else is touched.
$SUDO awk -v start='^${dom}[ ,{]' -v skip="$SKIP" '
  /ghostkbd-reports: begin/ { ours = 1; next }
  /ghostkbd-reports: end/ { ours = 0; next }
  ours { next }
  { print }
  $0 ~ start && !done {
    print "\\t${BEGIN}"
    print "\\thandle ${ROUTE} {"
    print "\\t\\trequest_body {"
    print "\\t\\t\\tmax_size 100KB"
    print "\\t\\t}"
    print "\\t\\t" skip
    print "\\t\\treverse_proxy 127.0.0.1:${PORT}"
    print "\\t}"
    print "\\t${END}"
    done = 1
  }' "$CF" | $SUDO tee "$CF.next" >/dev/null
[ "$($SUDO grep -c 'ghostkbd-reports: begin' "$CF.next")" = 1 ] || { $SUDO rm -f "$CF.next"; echo "x the lines did not go in once (nothing was changed)"; exit 1; }
# Copied onto the Caddyfile rather than moved over it, so the file keeps its owner and its mode.
$SUDO cp "$CF.next" "$CF"; $SUDO rm -f "$CF.next"
echo "what changed:"; $SUDO diff "$BACKUP" "$CF" | sed 's/^/  /' || true
$SUDO caddy validate --config "$CF" --adapter caddyfile >/dev/null 2>&1 || { $SUDO caddy validate --config "$CF" --adapter caddyfile 2>&1 | tail -5; restore "caddy validate refused it"; }
$SUDO systemctl reload caddy || restore "caddy would not reload"
sleep 4
AFTER=$(probe)
echo "after:"; echo "$AFTER" | sed 's/^/  /'
[ "$BEFORE" = "$AFTER" ] || restore "a site or a file answers differently after the reload"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --resolve "${DOMAIN}:443:127.0.0.1" -X POST -H 'Content-Type: application/json' -d '{}' "https://${DOMAIN}${ROUTE}" || true)
[ "$code" = 400 ] || restore "the route answered $code to an empty report, not the collector's 400"
echo "ok https://${DOMAIN}${ROUTE} reaches the collector"
`;

const REMOTE = look ? LOOK : INSTALL;
console.log(look ? '> Looking at the box (one ssh session, nothing changed)' : `> Shipping the report collector${withCaddy ? ' and its route' : ''} (one ssh session)`);
if (printOnly) {
  process.stdout.write(REMOTE);
  process.exit(0);
}

const { BOX_ENV_KEYS, boxSshOptions, openBox, tarball } = await import(pathToFileURL(join(glyph, 'tauri/scripts/lib/box.mjs')).href);
const { loadEnv } = await import(pathToFileURL(join(glyph, 'scripts/lib/env.mjs')).href);
const env = loadEnv(BOX_ENV_KEYS);
const sshOptions = boxSshOptions({ connectTimeout: 20, knownHosts: join(homedir(), '.ssh', 'known_hosts') });
const box = openBox(env, sshOptions);
// A connection the step before left open (--keep-connection) is a login already spent. The box counts logins, so
// this says which it was: the first run against the box printed "logged in" for the install that only rode the look's.
const riding = spawnSync('ssh', [...sshOptions, '-O', 'check', box.target], { stdio: 'ignore' }).status === 0;
box.logIn();
console.log(riding ? '  on the connection already open (no new login)' : `  logged in at ${new Date().toISOString().slice(11, 19)} UTC`);
const ship = () => {
  const dir = mkdtempSync(join(tmpdir(), 'ghostkbd-reports-'));
  try {
    for (const name of SHIPPED) copyFileSync(join(SOURCE, name), join(dir, name));
    return tarball(dir, 1024 * 1024);
  } finally { rmSync(dir, { recursive: true, force: true }); }
};
const result = look
  ? box.exec(REMOTE, { stdio: ['ignore', 'inherit', 'inherit'] })
  : box.exec(REMOTE, { input: ship(), stdio: ['pipe', 'inherit', 'inherit'] });
if (!args.includes('--keep-connection')) box.close();
if (result.status !== 0) fail(result.status === 5 ? 'ssh refused the password: if .env is right, wait before trying again.' : 'The remote step failed (output above).');
if (look || !withCaddy) process.exit(0);

// From here, as a phone would: an empty report is refused by the collector, and the release's files still answer.
const curl = (a) => spawnSync('curl', ['-s', '--max-time', '15', ...a], { encoding: 'utf8' });
const empty = curl(['-o', '/dev/null', '-w', '%{http_code}', '-X', 'POST', '-H', 'Content-Type: application/json', '-d', '{}', `https://${DOMAIN}${ROUTE}`]).stdout;
const feed = curl(['-o', '/dev/null', '-w', '%{http_code}', `https://${DOMAIN}/devkeyboard/apk.json`]).stdout;
console.log(`  from here: an empty report → ${empty} (400 is the collector saying no), apk.json → ${feed}`);
if (empty !== '400' || feed !== '200') fail('The route is written but does not answer from outside as it should: look at it before anyone sends a report.');
console.log(`ok reports are taken at https://${DOMAIN}${ROUTE}`);
