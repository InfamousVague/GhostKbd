#!/usr/bin/env node
/**
 * Builds, checks and publishes a Dev Keyboard release to the box (attack.fm's server, the one Ghost.md deploys to),
 * where installed copies find it and update themselves (android/.../Updater.kt).
 *
 *   node tool/release.mjs [--glyph ~/path/to/glyph] [--version 0.2.0] [--no-deploy] [--keep-connection]
 *
 * Served at https://attack.fm/devkeyboard/ from /opt/attackfm-site/devkeyboard:
 *
 *   devkeyboard.apk   the release, signed with the release key
 *   apk.json          { version, versionCode, sha256, bytes, url, build }: what an installed app reads
 *   index.html        a download page, for the first install
 *
 * THE CREDENTIALS ARE GHOST.MD'S. Nothing is copied here: the box's address, account and password are read from
 * Ghost.md's own .env (tauri/.env: AFM_DEPLOY_HOST, AFM_DEPLOY_USER, AFM_DEPLOY_PASS) by Ghost.md's own loader, and the
 * box is reached through Ghost.md's box.mjs, so this spends ONE login on the shared, multiplexed connection exactly as
 * Ghost.md's deploys do (the box locks an account out after a few logins in a short window). The Ghost.md checkout is
 * --glyph, or $GLYPH_ROOT, or found beside this repository or in the usual places.
 *
 * THE RELEASE KEY is ~/.config/devkeyboard/ (signing.properties and release.keystore), made here the first time.
 * Android installs an update only over the same certificate, so losing it means everyone reinstalls: back it up. The
 * certificate's SHA-256 is pinned in ~/.config/devkeyboard/cert.sha256 by the first release, and a build signed by
 * anything else (the debug key included) is refused.
 *
 * The version code is one above the live release's, so it always rises; the version name is pubspec.yaml's unless
 * --version says otherwise.
 */
import { spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const HOST_URL = 'https://attack.fm/devkeyboard';
const RELEASE = '/opt/attackfm-site/devkeyboard';
const BACKUPS = '/opt/devkeyboard-releases';
const PACKAGE = 'com.infamousvague.devkeyboard';
const CONFIG = join(homedir(), '.config', 'devkeyboard');

const args = process.argv.slice(2);
const flag = (name) => {
  const at = args.indexOf(`--${name}`);
  return at < 0 ? undefined : args[at + 1];
};
const deploy = !args.includes('--no-deploy');
const keepConnection = args.includes('--keep-connection');

const say = (line) => console.log(line);
const fail = (why) => {
  console.error(`x ${why}`);
  process.exit(1);
};
const run = (cmd, cmdArgs, options = {}) => {
  const result = spawnSync(cmd, cmdArgs, { stdio: 'inherit', ...options });
  if (result.error) fail(`${cmd} could not run: ${result.error.message}`);
  if (result.status !== 0) fail(`${cmd} ${cmdArgs.join(' ')} failed (${result.status}).`);
  return result;
};
const capture = (cmd, cmdArgs) => {
  const result = spawnSync(cmd, cmdArgs, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (result.error) fail(`${cmd} could not run: ${result.error.message}`);
  if (result.status !== 0) fail(`${cmd} failed: ${result.stderr || result.stdout}`);
  return result.stdout;
};

// --- Ghost.md, for the box and its credentials ----------------------------------------------------------------------

function findGlyph() {
  const candidates = [
    flag('glyph'),
    process.env.GLYPH_ROOT,
    join(ROOT, '..', 'glyph'),
    join(ROOT, '..', 'Glyph'),
    ...['Projects', 'Developer', 'dev', 'code', 'src', 'GitHub', 'Documents/GitHub', 'repos', ''].map((d) => join(homedir(), d, 'glyph')),
  ].filter(Boolean);
  for (const dir of candidates) {
    if (existsSync(join(dir, 'tauri', 'scripts', 'lib', 'box.mjs')) && existsSync(join(dir, 'scripts', 'lib', 'env.mjs'))) return resolve(dir);
  }
  fail(`Could not find the Ghost.md checkout (looked in ${candidates.join(', ')}). Pass --glyph <path> or set GLYPH_ROOT.`);
}

// --- the Android SDK's tools --------------------------------------------------------------------------------------

const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || join(homedir(), 'Library/Android/sdk');
function buildTool(name) {
  const tools = join(sdk, 'build-tools');
  const newest = existsSync(tools) ? readdirSync(tools).sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).pop() : null;
  if (!newest) fail(`No Android build-tools under ${tools}: set ANDROID_HOME.`);
  return join(tools, newest, name);
}

function keytool() {
  const candidates = [
    process.env.JAVA_HOME && join(process.env.JAVA_HOME, 'bin', 'keytool'),
    '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool',
    '/Applications/Android Studio.app/Contents/jre/Contents/Home/bin/keytool',
  ].filter(Boolean);
  for (const path of candidates) if (existsSync(path)) return path;
  const javaHome = spawnSync('/usr/libexec/java_home', [], { encoding: 'utf8' });
  if (javaHome.status === 0 && existsSync(join(javaHome.stdout.trim(), 'bin', 'keytool'))) return join(javaHome.stdout.trim(), 'bin', 'keytool');
  return 'keytool';
}

// --- the release key ----------------------------------------------------------------------------------------------

function ensureSigningKey() {
  const props = join(CONFIG, 'signing.properties');
  if (existsSync(props)) return;
  say('> No release key yet: making one in ~/.config/devkeyboard (back this folder up)');
  mkdirSync(CONFIG, { recursive: true, mode: 0o700 });
  const store = join(CONFIG, 'release.keystore');
  const password = randomBytes(24).toString('hex');
  run(keytool(), [
    '-genkeypair', '-noprompt',
    '-keystore', store, '-storetype', 'PKCS12',
    '-alias', 'devkeyboard', '-keyalg', 'RSA', '-keysize', '4096', '-validity', '36500',
    '-dname', 'CN=Dev Keyboard, O=InfamousVague',
    '-storepass', password, '-keypass', password,
  ]);
  writeFileSync(props, `storeFile=${store}\nstorePassword=${password}\nkeyAlias=devkeyboard\nkeyPassword=${password}\n`, { mode: 0o600 });
  chmodSync(props, 0o600);
  chmodSync(store, 0o600);
}

// --- versions -----------------------------------------------------------------------------------------------------

async function liveRelease() {
  try {
    const res = await fetch(`${HOST_URL}/apk.json?t=${Date.now()}`, { cache: 'no-store' });
    if (res.status === 404) return null;
    if (!res.ok) fail(`${HOST_URL}/apk.json answered ${res.status}.`);
    return await res.json();
  } catch (e) {
    fail(`Could not read the live release: ${e.message}`);
  }
}

function pubspecVersion() {
  const match = /^version:\s*([0-9]+\.[0-9]+\.[0-9]+)(?:\+([0-9]+))?/m.exec(readFileSync(join(ROOT, 'pubspec.yaml'), 'utf8'));
  if (!match) fail('pubspec.yaml has no version.');
  return { name: match[1], build: Number(match[2] ?? 1) };
}

// --- the download page --------------------------------------------------------------------------------------------

const page = (info) => `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Dev Keyboard</title>
<style>
  :root { color-scheme: light dark; --bg: #f7f8fa; --card: #fff; --text: #16181d; --muted: #5d6470; --accent: #2f6feb; }
  @media (prefers-color-scheme: dark) { :root { --bg: #0f1114; --card: #191c21; --text: #eceef2; --muted: #9aa1ad; --accent: #6b9bff; } }
  body { margin: 0; font: 16px/1.5 Inter, ui-sans-serif, system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); }
  main { max-width: 32rem; margin: 0 auto; padding: 3rem 1.25rem; }
  .card { background: var(--card); border-radius: 1.25rem; padding: 1.5rem; box-shadow: 0 1px 2px rgb(0 0 0 / .06), 0 8px 24px rgb(0 0 0 / .06); }
  h1 { font-size: 1.75rem; margin: 0 0 .25rem; letter-spacing: -.02em; }
  p { color: var(--muted); margin: 0 0 1.25rem; }
  a.button { display: inline-block; background: var(--accent); color: #fff; text-decoration: none; font-weight: 600; padding: .7rem 1.4rem; border-radius: 999px; }
  ol { color: var(--muted); padding-left: 1.2rem; margin: 1.5rem 0 0; }
  small { display: block; margin-top: 1.25rem; color: var(--muted); }
</style>
</head>
<body>
<main>
  <div class="card">
    <h1>Dev Keyboard</h1>
    <p>A keyboard for people who write code. Version ${info.version}.</p>
    <a class="button" href="${info.url}">Download for Android</a>
    <ol>
      <li>Open the download and tap Install (allow installs from your browser if Android asks).</li>
      <li>Open Dev Keyboard and follow its two setup steps.</li>
      <li>It keeps itself up to date from here on.</li>
    </ol>
    <small>${(info.bytes / 1e6).toFixed(1)} MB · SHA-256 ${info.sha256.slice(0, 16)}…</small>
  </div>
</main>
</body>
</html>
`;

// --- the release --------------------------------------------------------------------------------------------------

const glyph = deploy ? findGlyph() : null;
if (glyph) say(`ok Ghost.md at ${glyph} (the box's credentials)`);

ensureSigningKey();

const live = await liveRelease();
const pub = pubspecVersion();
const versionName = flag('version') ?? pub.name;
const versionCode = Math.max((live?.versionCode ?? 0) + 1, pub.build);
say(`> Building Dev Keyboard ${versionName} (code ${versionCode}; live is ${live ? `${live.version} (${live.versionCode})` : 'nothing yet'})`);

run('flutter', ['build', 'apk', '--release', '--target-platform', 'android-arm64', `--build-name=${versionName}`, `--build-number=${versionCode}`], { cwd: ROOT });
const built = join(ROOT, 'build/app/outputs/flutter-apk/app-release.apk');
if (!existsSync(built)) fail(`No APK at ${built}.`);

// What the APK says it is.
const badging = capture(buildTool('aapt2'), ['dump', 'badging', built]);
const pkg = /^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m.exec(badging);
if (!pkg) fail('The APK does not say what it is.');
if (pkg[1] !== PACKAGE) fail(`The APK is ${pkg[1]}, not ${PACKAGE}.`);
if (Number(pkg[2]) !== versionCode || pkg[3] !== versionName) fail(`The APK is ${pkg[3]} (${pkg[2]}), not ${versionName} (${versionCode}).`);
if (/^application-debuggable/m.test(badging)) fail('The APK is debuggable.');

// Who signed it.
const certs = capture(buildTool('apksigner'), ['verify', '--print-certs', built]);
const digest = /certificate SHA-256 digest: ([0-9a-f]{64})/i.exec(certs)?.[1]?.toLowerCase();
if (!digest) fail('apksigner did not name the signing certificate.');
if (/CN=Android Debug/.test(certs)) fail('The APK is signed with the debug key: ~/.config/devkeyboard/signing.properties was not used.');
const pinFile = join(CONFIG, 'cert.sha256');
if (existsSync(pinFile)) {
  const pin = readFileSync(pinFile, 'utf8').trim();
  if (pin !== digest) fail(`The APK is signed by ${digest}, not the release key ${pin}. Installed copies could not update to it.`);
} else {
  writeFileSync(pinFile, `${digest}\n`, { mode: 0o600 });
  say(`ok release certificate pinned: ${digest}`);
}

// The release's files.
const out = join(ROOT, 'build/release');
mkdirSync(out, { recursive: true });
copyFileSync(built, join(out, 'devkeyboard.apk'));
const bytes = readFileSync(join(out, 'devkeyboard.apk'));
const info = {
  version: versionName,
  versionCode,
  sha256: createHash('sha256').update(bytes).digest('hex'),
  bytes: bytes.length,
  url: 'devkeyboard.apk',
  build: new Date().toISOString().replace(/\D/g, '').slice(0, 14),
};
writeFileSync(join(out, 'apk.json'), `${JSON.stringify(info, null, 2)}\n`);
writeFileSync(join(out, 'index.html'), page(info));
say(`ok Dev Keyboard ${info.version} (code ${info.versionCode}), ${(info.bytes / 1e6).toFixed(1)} MB, in build/release`);
if (!deploy) process.exit(0);

// --- publishing, with Ghost.md's box ------------------------------------------------------------------------------

const { BOX_ENV_KEYS, boxSshOptions, openBox } = await import(pathToFileURL(join(glyph, 'tauri/scripts/lib/box.mjs')).href);
const { loadEnv } = await import(pathToFileURL(join(glyph, 'scripts/lib/env.mjs')).href);
const env = loadEnv(BOX_ENV_KEYS);
const box = openBox(env, boxSshOptions({ connectTimeout: 20 }));
say('> Logging in to the box');
box.logIn();
const STAGE = '/tmp/devkeyboard-stage';
box.ssh(`rm -rf ${STAGE} && mkdir -p ${STAGE}`);
say(`> Uploading (${(info.bytes / 1e6).toFixed(1)} MB)`);
box.rsync(['--progress'], join(out, 'devkeyboard.apk'), `${STAGE}/devkeyboard.apk`);
box.rsync([], join(out, 'apk.json'), `${STAGE}/apk.json`);
box.rsync([], join(out, 'index.html'), `${STAGE}/index.html`);
say('> Publishing');
// The APK first, whole, by rename; apk.json last, so no installed copy is told of an APK still arriving. The live
// files are kept outside the web root first.
box.ssh(`set -e
SUDO=; [ "$(id -u)" -eq 0 ] || SUDO=sudo
[ "$(sha256sum ${STAGE}/devkeyboard.apk | cut -d' ' -f1)" = "${info.sha256}" ] || { echo "x the APK arrived changed"; exit 1; }
stamp=$(date -u +%Y%m%d%H%M%S)
$SUDO mkdir -p ${RELEASE} ${BACKUPS}
if [ -f ${RELEASE}/apk.json ]; then $SUDO mkdir -p ${BACKUPS}/before-$stamp && $SUDO cp -p ${RELEASE}/* ${BACKUPS}/before-$stamp/; fi
$SUDO install -m 644 -o root -g root ${STAGE}/devkeyboard.apk ${RELEASE}/.devkeyboard.apk.new
$SUDO mv -f ${RELEASE}/.devkeyboard.apk.new ${RELEASE}/devkeyboard.apk
$SUDO install -m 644 -o root -g root ${STAGE}/index.html ${RELEASE}/index.html
$SUDO install -m 644 -o root -g root ${STAGE}/apk.json ${RELEASE}/.apk.json.new
$SUDO mv -f ${RELEASE}/.apk.json.new ${RELEASE}/apk.json
$SUDO chmod 755 ${RELEASE}
rm -rf ${STAGE}
echo "ok placed in ${RELEASE}"`);

// Is it served? attack.fm's site block serves /opt/attackfm-site; if it answers otherwise, show what the block is.
const res = await fetch(`${HOST_URL}/apk.json?t=${Date.now()}`, { cache: 'no-store' }).catch((e) => ({ ok: false, status: e.message }));
const said = res.ok ? await res.json().catch(() => null) : null;
if (said?.sha256 !== info.sha256) {
  say(`x ${HOST_URL}/apk.json answered ${res.status}, not this release. attack.fm's Caddy block, for a look:`);
  box.ssh(`grep -n -A25 '^attack.fm' /etc/caddy/Caddyfile || true`);
  if (!keepConnection) box.close();
  process.exit(1);
}
if (!keepConnection) box.close();
const head = await fetch(`${HOST_URL}/devkeyboard.apk`, { method: 'HEAD' });
say(`ok live: ${HOST_URL}/ — Dev Keyboard ${info.version}, APK served at ${head.headers.get('content-length')} bytes (${info.bytes} expected)`);
