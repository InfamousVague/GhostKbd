// The adaptive keys chunk: three neighbouring keys (u i o) on a keycap's face, with the plugin's map over them.
//
// Read from the Android sources, and kept in their terms:
//   AdaptiveKeys.kt        hit() (the running mean of where you touch a key, as fractions of the key), offset() (no aim
//                          until a key has had MIN_SAMPLES touches; never more than MAX_SHIFT of a key from its middle)
//   KeyboardView.kt        adapt() (a touch goes to whichever neighbouring letter's aim is nearest: kbd.js does this
//                          once it has a model), drawAdaptive() (a 3.5dp dot where a key now listens, tied to the
//                          key's middle by a 1.5dp line in the theme's accent)
//   DevKeyboardService.kt  endWord() (a touch left standing teaches the key it typed; only a finger's touch, never a
//                          hardware key or a screen reader's click)
//
// ONE thing is sped up, and the caption under the key says so: the app waits for 15 touches on a key before its aim
// counts, this waits for 3. The mean, the cap and the hit-testing are the app's.
//
// Not here: the red glows. The app draws one for each slip, and it knows a slip by backspace then the letter beside it,
// or by autocorrect swapping a neighbour in. Three keys have neither, so nothing here is called a slip.
//
// Idle, a ghost thumb plays: it lands just over the edge on o (o types), then keeps touching i toward that side until
// i's dot has slid over, then lands on the same spot again (i types). A visitor's first touch clears what it learned.

import { GhostKbd } from '../kbd.js';

// AdaptiveKeys.kt
const MAX_SHIFT = 0.35;
const MIN_SAMPLES = 3;               // the app: 15

// The ghost thumb's touches, as fractions of the i key from its middle (x right, y down).
const OVER_THE_EDGE = [0.62, 0.1];   // on o's face, a little past the edge it shares with i
const TOUCHES = [[0.16, 0.06], [0.27, 0.14], [0.37, 0.04], [0.44, 0.12], [0.46, 0.09], [0.47, 0.1]];

const MARK_MS = 620;                 // how long a touch stays drawn
const isLetter = (c) => typeof c === 'string' && c.length === 1 && c.toLowerCase() !== c.toUpperCase();
const rgb = (n) => `${(n >> 16) & 255},${(n >> 8) & 255},${n & 255}`;

/** AdaptiveKeys' aims: for each letter the mean of the touches meant for it, and how many made it. */
class Aims {
  constructor() { this.aims = new Map(); }

  reset() { this.aims.clear(); }

  /** AdaptiveKeys.hit: a running mean that settles after a few hundred touches and still follows a change of grip. */
  hit(c, dx, dy, weight = 1) {
    if (!isLetter(c) || Number.isNaN(dx) || Number.isNaN(dy) || Math.abs(dx) > 1.5 || Math.abs(dy) > 1.5) return;
    const k = c.toLowerCase();
    const a = this.aims.get(k) || { dx: 0, dy: 0, n: 0 };
    this.aims.set(k, a);
    for (let i = 0; i < weight; i++) {
      a.n++;
      const rate = 1 / Math.min(a.n, 200);
      a.dx += (dx - a.dx) * rate;
      a.dy += (dy - a.dy) * rate;
    }
  }

  /** AdaptiveKeys.offset: where to aim for `c`, or the key's middle until it has had enough touches. */
  offset(c) {
    const a = this.aims.get(String(c).toLowerCase());
    if (!a || a.n < MIN_SAMPLES) return [0, 0];
    const cap = (v) => Math.min(MAX_SHIFT, Math.max(-MAX_SHIFT, v));
    return [cap(a.dx), cap(a.dy)];
  }
}

/** Fills a keycap's body (`root`, the .keycap__body) with the chunk. Returns its GhostKbd; destroy() takes it away. */
export function mount(root) {
  root.classList.add('chunk-adaptive');
  root.innerHTML = '<div class="keycap__keys"></div><p class="gk-vh" aria-live="polite"></p>';
  const says = root.lastChild;

  const kbd = new GhostKbd(root.firstChild, {
    fragment: { keys: ['u', 'i', 'o'], fit: 'contain', maxDp: 2.6 },
    theme: 'auto', bare: true, borders: true, preview: false, autoCaps: false, hardKeys: false,
    longPressMs: 2 ** 30,            // no room on a keycap for the panel of accents
    label: 'Three keys of Ghost Keyboard, u, i and o, with the adaptive keys map over them',
  });
  // Adaptive keys learns from where a finger lands, which a hardware keyboard or a screen reader's click cannot
  // show (the app does not learn from them either). So the fragment is not a tab stop that would do nothing: it is a
  // picture with a description, and the polite line under it says what a visitor's touches have taught.
  kbd.el.removeAttribute('tabindex');
  kbd.el.removeAttribute('aria-roledescription');
  kbd.el.setAttribute('role', 'img');
  kbd.el.setAttribute('aria-label', 'Adaptive keys, shown on u, i and o: touches land to the right of i’s middle, and the dot that marks where i listens moves over to meet them.');
  for (const key of kbd.el.querySelectorAll('.gk-key')) key.setAttribute('aria-hidden', 'true');
  kbd.on('layout', () => { for (const key of kbd.el.querySelectorAll('.gk-key')) key.setAttribute('aria-hidden', 'true'); });

  const aims = new Aims();
  kbd.setAdaptive(aims);             // hit-testing only; the map is drawn below, so its dot can slide

  const shown = new Map();           // letter -> [dx, dy] as drawn, on its way to the aim
  let marks = [];                    // touches, fading: {x, y, t}
  let last = 0, live = false;

  const forget = () => { aims.reset(); shown.clear(); marks = []; says.textContent = ''; kbd.redraw(); };
  const mark = (x, y) => { if (!kbd.reduced) { marks.push({ x, y, t: performance.now() }); kbd.redraw(); } };

  kbd.setOverlay((ctx, g, now) => {
    const ease = kbd.reduced ? 1 : 1 - Math.exp(-(live ? Math.min(64, now - last) : 16) / 70);
    last = now;
    let moving = false;

    // A touch: where a thumb came down. Not the app's drawing; only so the dot can be seen to follow something.
    marks = marks.filter((m) => now - m.t < MARK_MS);
    const ink = rgb(g.theme.text);
    for (const m of marks) {
      const a = 1 - (now - m.t) / MARK_MS;
      ctx.beginPath();
      ctx.arc(m.x, m.y, g.keyWidth * 0.3, 0, Math.PI * 2);
      ctx.fillStyle = `rgba(${ink},${(0.12 * a).toFixed(3)})`;
      ctx.fill();
      ctx.lineWidth = 1;
      ctx.strokeStyle = `rgba(${ink},${(0.5 * a).toFixed(3)})`;
      ctx.stroke();
    }

    // KeyboardView.drawAdaptive: each letter whose aim has moved, a dot where it now is, on a line from its middle.
    ctx.strokeStyle = ctx.fillStyle = `rgb(${rgb(g.theme.accent)})`;
    ctx.lineWidth = 1.5 * g.dp;
    ctx.lineCap = 'butt';
    for (const key of g.keys) {
      if (key.type !== 'char' || !isLetter(key.char)) continue;
      const [tx, ty] = aims.offset(key.char);
      if (!tx && !ty) { shown.delete(key.char); continue; }
      let at = shown.get(key.char);
      if (!at) shown.set(key.char, (at = [0, 0]));
      at[0] += (tx - at[0]) * ease;
      at[1] += (ty - at[1]) * ease;
      if (Math.abs(tx - at[0]) + Math.abs(ty - at[1]) < 0.004) { at[0] = tx; at[1] = ty; } else moving = true;
      const ax = key.cx + at[0] * key.rect.w, ay = key.cy + at[1] * key.rect.h;
      ctx.beginPath(); ctx.moveTo(key.cx, key.cy); ctx.lineTo(ax, ay); ctx.stroke();
      ctx.beginPath(); ctx.arc(ax, ay, 3.5 * g.dp, 0, Math.PI * 2); ctx.fill();
    }

    live = moving || marks.length > 0;
    return live;
  });

  // A finger's touch (and the ghost thumb's) teaches the key it typed where it is aimed at.
  kbd.on('down', (ev) => mark(ev.x, ev.y));
  kbd.on('key', (ev) => {
    if (ev.type !== 'char' || !ev.rect || !isLetter(ev.key.text) || (ev.source !== 'pointer' && ev.source !== 'script')) return;
    const r = ev.rect, c = ev.key.text;
    aims.hit(c, (ev.x - (r.x + r.w / 2)) / r.w, (ev.y - (r.y + r.h / 2)) / r.h);
    kbd.redraw();
    if (ev.source !== 'pointer') return;
    const [ox, oy] = aims.offset(c);
    const parts = [];
    if (Math.abs(ox) >= 0.01) parts.push(`${Math.round(Math.abs(ox) * 100)}% of a key to the ${ox > 0 ? 'right' : 'left'}`);
    if (Math.abs(oy) >= 0.01) parts.push(`${Math.round(Math.abs(oy) * 100)}% of a key ${oy > 0 ? 'lower' : 'higher'}`);
    if (parts.length) says.textContent = `${c} now listens ${parts.join(' and ')}.`;
  });
  kbd.on('theme', () => kbd.redraw());

  /** The ghost thumb comes down at a point given in fractions of the i key. */
  const thumb = (s, [fx, fy], hold) => {
    const r = kbd.keyRect('i');
    if (!r) return s.wait(hold);
    const x = r.cx + fx * r.w, y = r.cy + fy * r.h;
    mark(x, y);
    return s.tapAt(x, y, { hold });
  };

  kbd.loop(async (s) => {
    forget();
    await s.wait(900);
    await thumb(s, OVER_THE_EDGE, 260);                    // o
    await s.wait(1100);
    for (const t of TOUCHES) { await thumb(s, t, 110); await s.wait(430); }
    await s.wait(700);
    await thumb(s, OVER_THE_EDGE, 260);                    // the same spot: i
    await s.wait(3200);
  }, {
    gap: 600,
    onHandOver: forget,
    // Reduced motion: what the thumb's touches teach, there at once.
    still: () => { forget(); for (const [dx, dy] of TOUCHES) aims.hit('i', dx, dy); kbd.redraw(); },
  });

  return kbd;
}

export default mount;
