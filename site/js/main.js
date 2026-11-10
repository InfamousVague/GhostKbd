/*
 * Ghost Keyboard's page (index.html). The page is whole without this file; what it adds:
 *
 *   - each keycap's chunk (js/chunks/<name>.js), fetched and mounted as its key nears the viewport. A chunk that
 *     cannot be fetched or throws leaves its key as the page wrote it: legend, one still line, caption;
 *   - "Pause the keys" in the header: one button that stops every key's idle loop and light until it is pressed again;
 *   - the release's version, size and checksum, read from ./apk.json beside the page;
 *   - the header's hairline once the page has scrolled, and sections coming in as they are reached.
 *
 * Each of these is started on its own (see the foot of the file), so one that fails leaves the others, and the
 * page's words, as they were. Nothing is fetched from another host, and every address is relative to where the page
 * is served.
 */

const root = document.documentElement;

// ---- chunks ---------------------------------------------------------------------------------------------------

/** The chunks there are. A key naming anything else is left alone. */
const CHUNKS = new Set(['ghostwriter', 'voice', 'adaptive', 'swipe', 'terminal', 'row', 'privacy', 'dictation', 'look', 'search']);

/** What each mounted chunk returned (its GhostKbd), by the key's body: for the console, and for a test. */
const mounted = new Map();

function mountChunk(body) {
  const name = body.dataset.chunk;
  if (!CHUNKS.has(name) || body.dataset.chunkState) return;
  body.dataset.chunkState = 'loading';
  const cap = body.closest('.keycap');
  // What the page wrote on the key, put back if the chunk fails part-way.
  const still = { html: body.innerHTML, cls: body.className };
  const caption = cap?.querySelector('.keycap__caption');
  const captionWas = caption ? caption.innerHTML : null;

  import(`./chunks/${name}.js`)
    .then((mod) => {
      const mount = mod.mount || mod.default;
      if (typeof mount !== 'function') throw new Error(`chunks/${name}.js has no mount()`);
      mounted.set(body, mount(body));
      body.dataset.chunkState = 'live';
      cap?.classList.add('is-live');
    })
    .catch((err) => {
      body.className = still.cls;
      body.innerHTML = still.html;
      if (caption && captionWas !== null) caption.innerHTML = captionWas;
      body.dataset.chunkState = 'failed';
      cap?.classList.remove('is-live');
      console.warn(`Ghost Keyboard: the "${name}" key stays as written.`, err);
    });
}

function watchChunks() {
  const bodies = [...document.querySelectorAll('[data-chunk]')];
  if (!('IntersectionObserver' in window)) { bodies.forEach(mountChunk); return; }
  // A screen and a half ahead, so a key is working by the time it is reached.
  const io = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      io.unobserve(entry.target);
      mountChunk(entry.target);
    }
  }, { rootMargin: '150% 0px 150% 0px' });
  bodies.forEach((body) => io.observe(body));
}

// ---- pause the keys ------------------------------------------------------------------------------------------

/**
 * The keys play by themselves beside words people are reading, so the page has one control that stops them all and
 * keeps them stopped: the header's button. Pressed, every keyboard on the page behaves as it does under
 * prefers-reduced-motion (kbd.js setPaused) and the stylesheets hold their own animations (html.is-paused). The
 * choice is remembered for the next visit where the browser lets it be. Under prefers-reduced-motion nothing moves
 * to begin with, and the button stays hidden.
 */
function pauseButton() {
  const button = document.querySelector('[data-pause]');
  if (!button) return;
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  if (reduce?.matches) return;
  const label = button.querySelector('[data-pause-label]');
  const KEY = 'ghost-keys-paused';
  let paused = false;
  try { paused = localStorage.getItem(KEY) === '1'; } catch { /* no storage: the choice lasts for this visit */ }

  import('./kbd.js').then(({ setPaused }) => {
    const apply = () => {
      setPaused(paused);
      root.classList.toggle('is-paused', paused);
      button.setAttribute('aria-pressed', String(paused));
      if (label) label.textContent = paused ? 'Play the keys' : 'Pause the keys';
      button.title = paused ? 'The keys on this page are paused' : 'Stop the keys on this page playing by themselves';
    };
    button.addEventListener('click', () => {
      paused = !paused;
      try { if (paused) localStorage.setItem(KEY, '1'); else localStorage.removeItem(KEY); } catch { /* as above */ }
      apply();
    });
    apply();
    button.hidden = false;
  }).catch(() => {});
}

// ---- the release ----------------------------------------------------------------------------------------------

/** ./apk.json is { version, bytes, sha256 }; anything else and the page keeps what it says without it. */
async function release() {
  let info;
  try {
    const res = await fetch('./apk.json', { cache: 'no-cache' });
    if (!res.ok) return;
    info = await res.json();
  } catch { return; }
  if (!info || typeof info !== 'object') return;

  const version = typeof info.version === 'string' && /^[0-9A-Za-z][0-9A-Za-z.+-]{0,23}$/.test(info.version) ? info.version : null;
  const size = Number.isFinite(info.bytes) && info.bytes > 0 ? `${(info.bytes / 1e6).toFixed(1)} MB` : null;
  const sha256 = typeof info.sha256 === 'string' && /^[0-9a-f]{64}$/i.test(info.sha256) ? info.sha256.toLowerCase() : null;
  const facts = { version, size, sha256 };

  for (const el of document.querySelectorAll('[data-apk]')) {
    const value = facts[el.dataset.apk];
    if (value) el.textContent = value;
    else el.closest('[data-apk-show]')?.setAttribute('data-apk-missing', '');
  }
  // A group is shown only when everything in it was read.
  for (const el of document.querySelectorAll('[data-apk-show]')) {
    const needs = [el, ...el.querySelectorAll('[data-apk]')].filter((n) => n.dataset.apk);
    if (!el.hasAttribute('data-apk-missing') && needs.every((n) => facts[n.dataset.apk])) el.hidden = false;
  }
  if (version || size) {
    for (const el of document.querySelectorAll('[data-apk-meta]')) el.textContent = [version && `Version ${version}`, size].filter(Boolean).join(' · ');
  }
}

// ---- the page's own small things ------------------------------------------------------------------------------

function chrome() {
  const nav = document.querySelector('.nav');
  if (nav) {
    const stuck = () => nav.classList.toggle('is-stuck', window.scrollY > 8);
    window.addEventListener('scroll', stuck, { passive: true });
    stuck();
  }
  // Older iOS Safari gives :active to nothing without a touch listener somewhere: this is what lets a key go down.
  document.addEventListener('touchstart', () => {}, { passive: true });

  // A question opened by its address (the link in the privacy section): open it, then go to it.
  const openTarget = () => {
    let id = location.hash.slice(1);
    try { id = decodeURIComponent(id); } catch { /* a stray % in the address: the fragment is read as it stands */ }
    const el = id && document.getElementById(id);
    if (!el) return;
    const details = el.closest('details');
    if (details && !details.open) { details.open = true; el.scrollIntoView({ block: 'start' }); }
  };
  window.addEventListener('hashchange', openTarget);
  openTarget();
}

function reveals() {
  const els = [...document.querySelectorAll('.reveal')];
  if (!els.length) return;
  if (!('IntersectionObserver' in window)) { els.forEach((el) => el.classList.add('is-in')); return; }
  const io = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      entry.target.classList.add('is-in');
      io.unobserve(entry.target);
    }
  }, { rootMargin: '0px 0px -8% 0px' });
  els.forEach((el) => io.observe(el));
}

// Each on its own: one that throws leaves the rest running. The words come first: `js` (which lets .reveal hide what
// has not been scrolled to yet) goes on only once reveals() is watching, so nothing can leave the page's text unseen.
const start = (fn) => { try { fn(); } catch (err) { console.warn('Ghost Keyboard: part of the page did not start.', err); } };
try { reveals(); root.classList.add('js'); }
catch (err) { for (const el of document.querySelectorAll('.reveal')) el.classList.add('is-in'); console.warn(err); }
start(chrome);
start(pauseButton);
start(watchChunks);
start(() => { release(); });

// For looking in from the console: window.ghost.mounted is each key's keyboard.
window.ghost = { mounted, mountChunk };
