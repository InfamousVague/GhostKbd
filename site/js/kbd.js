// Ghost Keyboard, drawn in HTML: the real keyboard's layout, colours, popups and backlight, as one component.
//
// Read from the Android sources, and kept in their terms so the two can be compared side by side:
//   Layouts.kt        the rows of each page, the { } key beside space, the mic key in the full stop's place, long-press
//                     choices, hints
//   KeyboardView.kt   metrics (60dp rows, 3dp/6dp key padding, 6dp radius), the key preview, the long-press panel,
//                     the pill keys, the Retro keycaps, shift, the space-bar cursor, the swipe trail, the heatmap,
//                     the four key heights (Condensed's 46dp rows and smaller labels), and layoutKeys' layouts: on a
//                     wide screen split, full width, or a phone-sized board docked left, centre or right; on a
//                     phone, one-handed
//   KeyboardTheme.kt  the colours, and fromBase(), which makes a whole theme from five
//   Backlight.kt      the six effects on the keys' legends
//   PluginRow.kt      the row above the keys: the cog, the strip, the arrow keys
//   RowIcons.kt       Phosphor's icons (regular, with the duotone wash)
//
// No dependencies, no network. Everything is measured in "dp": one dp is the keyboard's width / 412 CSS pixels
// (clamped), so a keyboard keeps a phone's proportions at any size.
//
// A FRAGMENT is the same keyboard showing only part of itself (a few named keys, a row, a block of keys, or only the
// row above the keys), sized to a small container: new GhostKbd(host, {fragment: ['g', 'h', 'o', 's', 't']}). It
// presses, lights, previews and types exactly as the whole board does, because it is the whole board's code.
//
// Also here: GhostField (what a keyboard types into), typeInto (a headline typed out, no keyboard), icon().

const HAS_DOM = typeof window !== 'undefined' && typeof document !== 'undefined';
const RM = HAS_DOM && window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
const DARK_MQ = HAS_DOM && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
const clock = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());

// The page's own "pause the keys": while it is on every keyboard behaves as under prefers-reduced-motion (nothing
// plays, lights hold still), and each idle loop shows its still picture until it is taken off again.
let PAUSED = false;
const BOARDS = new Set();
/** Pauses (or lets go again) every keyboard on the page: idle loops, backlights, overlays. */
export function setPaused(on) {
  on = !!on;
  if (on === PAUSED) return;
  PAUSED = on;
  for (const kbd of [...BOARDS]) kbd._pausedChanged();
}
/** Whether the page's keys are paused. */
export const isPaused = () => PAUSED;
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// --- Layouts.kt ------------------------------------------------------------------------------------------------------

/** One key. `¦` in text or an alternative is where the cursor is left: `(¦)` types a pair, cursor between. */
function K(type, o = {}) {
  const text = o.text ?? '';
  const key = { type, text, label: o.label ?? text, width: o.width ?? 1, hint: o.hint ?? null, alts: o.alts ?? [], page: o.page ?? null, pill: o.pill ?? true };
  key.functional = type === 'shift' || type === 'delete' || type === 'mode';
  return key;
}
const row = (keys, inset = 0) => ({ keys, inset, units: keys.reduce((n, k) => n + k.width, 0) + inset * 2 });

const ACCENTS = {
  a: ['à', 'á', 'â', 'ä', 'æ', 'ã', 'å', 'ā'], c: ['ç', 'ć', 'č'], e: ['è', 'é', 'ê', 'ë', 'ē', 'ę', 'ė'],
  i: ['î', 'ï', 'í', 'ī', 'į', 'ì'], l: ['ł'], n: ['ñ', 'ń'], o: ['ô', 'ö', 'ò', 'ó', 'œ', 'ø', 'ō', 'õ'],
  s: ['ß', 'ś', 'š'], u: ['û', 'ü', 'ù', 'ú', 'ū'], y: ['ÿ'], z: ['ž', 'ź', 'ż'],
};
const SYMBOL_ALTS = {
  1: ['¹', '½', '⅓', '¼', '⅛'], 2: ['²', '⅔'], 3: ['³', '¾', '⅜'], 4: ['⁴'], 0: ['ⁿ', '∅'],
  '(': ['<', '[', '{'], ')': ['>', ']', '}'], '-': ['_', '–', '—', '·'], '+': ['±'], '/': ['\\'],
  "'": ['`', '‘', '’', '‚', '‹', '›'], '"': ['“', '”', '„', '«', '»'], '!': ['¡'], '?': ['¿', '‽'],
  $: ['¢', '£', '€', '¥', '₹', '₱'], '*': ['†', '‡', '★'], '=': ['≠', '≈', '∞'], '%': ['‰', '℅'], '#': ['№'],
  '&': ['§'], '<': ['≤', '«', '‹'], '>': ['≥', '»', '›'],
};
const PERIOD_ALTS = ['?', '!', ',', "'", '"', ':', ';', '-', '/', '@', '#', '&', '(', ')', '%', '+'];
const DIGITS = [...'1234567890'];

const letter = (c, hint) => K('char', { text: c, hint, alts: [...(hint ? [hint] : []), ...(ACCENTS[c] || [])] });
const sym = (c) => K('char', { text: c, alts: SYMBOL_ALTS[c] || [] });
const snip = (label, text, ...alts) => K('char', { text, label, alts });
const shiftKey = () => K('shift', { width: 1.5 });
const deleteKey = () => K('delete', { width: 1.5 });
const bottomRow = (modeLabel, modePage, second) => row([
  K('mode', { label: modeLabel, width: 1.5, page: modePage }),
  second || K('char', { text: ',' }),
  K('space', { text: ' ', width: 5 }),
  K('char', { text: '.', alts: PERIOD_ALTS }),
  K('enter', { width: 1.5 }),
]);
const numberRow = () => row(DIGITS.map(sym));

export const Layouts = {
  letters(withNumbers = false) {
    const top = [...'qwertyuiop'].map((c, i) => letter(c, withNumbers ? null : DIGITS[i]));
    const middle = [...'asdfghjkl'].map((c, i) => letter(c, '@#$_&-+()'[i]));
    const bottom = [...'zxcvbnm'].map((c, i) => letter(c, '*"\':;!?'[i]));
    const rows = [row(top), row(middle, 0.5), row([shiftKey(), ...bottom, deleteKey()]),
      bottomRow('?123', 'symbols', K('mode', { label: '{ }', page: 'dev', pill: false }))];
    return withNumbers ? [numberRow(), ...rows] : rows;
  },
  dev() {
    return [
      row([
        snip('( )', '(¦)', '(', ')'), snip('[ ]', '[¦]', '[', ']'), snip('{ }', '{¦}', '{', '}', '{\n  ¦\n}'),
        snip('< >', '<¦>', '<', '>', '</¦>'), snip('" "', '"¦"', '"'), snip("' '", "'¦'", "'"),
        snip('` `', '`¦`', '`', '```\n¦\n```'), snip('${ }', '${¦}', '{{¦}}', '%s', '{}'),
        snip('=>', ' => ', '->', '<-', '=>', '::'),
        snip('ƒ', 'function ¦() {}', '() => {¦}', 'const ¦ = () => {}', 'def ¦():', 'fn ¦() {}', 'func ¦() {}'),
      ]),
      row([
        snip('=', '=', '==', '===', '!=', '!==', '<=', '>='), snip('+', '+', '++', '+='), snip('-', '-', '--', '-=', '->'),
        snip('*', '*', '**', '*=', '/*¦*/'), snip('/', '/', '//', '/=', '\\'), snip('|', '|', '||', '|=', '|>'),
        snip('&', '&', '&&', '&='), snip('!', '!', '!=', '!!'), snip('?', '?', '??', '?.', '??='),
        snip('%', '%', '%=', '^', '^='),
      ]),
      row([
        K('char', { text: '\t', label: '⇥', width: 1.5 }),
        snip(';', ';'), snip(':', ':', '::', ':='), snip('_', '_', '__'), snip('$', '$', '$('),
        snip('#', '#', '#!/'), snip('@', '@'), snip('~', '~', '`', '^'),
        deleteKey(),
      ]),
      // In { }'s place: a comma, which types one and goes back to the letters.
      bottomRow('ABC', 'letters', K('char', { text: ',', page: 'letters', pill: false })),
    ];
  },
  symbols() {
    return [
      numberRow(),
      row([...'@#$_&-+()/'].map(sym)),
      row([K('mode', { label: '=\\<', width: 1.5, page: 'more' }), ...[...'*"\':;!?'].map(sym), deleteKey()]),
      bottomRow('ABC', 'letters'),
    ];
  },
  more() {
    return [
      row([...'~`|•√π÷×§∆'].map(sym)),
      row([...'£¥€¢^°={}\\'].map(sym)),
      row([K('mode', { label: '?123', width: 1.5, page: 'symbols' }), ...[...'%©®™✓[]'].map(sym), deleteKey()]),
      bottomRow('ABC', 'letters'),
    ];
  },
  /**
   * The dictation plugin's mic key (Layouts.withMic; the app has it so since 0.16.0): on the letters page it stands
   * where the full stop was, next to enter, and the space bar keeps its whole width. The full stop leads its
   * long-press, with the full stop's own punctuation after it, and shows in its corner. Other pages have no { } key
   * and come back as they were, full stop and all. The emoji key that stood after { } from 0.12.1 to 0.15.0 is gone:
   * emoji is a button in the row above the keys again.
   */
  withMic(rows) {
    const last = rows[rows.length - 1];
    if (!last.keys.some((k) => k.type === 'mode' && k.page === 'dev')) return rows;
    const at = last.keys.findIndex((k) => k.type === 'enter') - 1;
    const stop = last.keys[at];
    if (!stop || stop.type !== 'char' || !stop.text) return rows;
    const mic = K('mic', { hint: stop.text, alts: [...new Set([stop.text, ...stop.alts])] });
    return [...rows.slice(0, -1), row(last.keys.map((k, i) => (i === at ? mic : k)), last.inset)];
  },
  page(page, withNumbers = false) {
    return page === 'symbols' ? Layouts.symbols() : page === 'more' ? Layouts.more() : page === 'dev' ? Layouts.dev() : Layouts.letters(withNumbers);
  },
};
export const PAGES = ['letters', 'symbols', 'more', 'dev'];

// --- KeyboardTheme.kt ------------------------------------------------------------------------------------------------

const rgbOf = (c) => [(c >> 16) & 255, (c >> 8) & 255, c & 255];
const cssOf = (c) => '#' + (c & 0xffffff).toString(16).padStart(6, '0').toUpperCase();
function mix(a, b, t) {
  const A = rgbOf(a), B = rgbOf(b);
  const l = (x, y) => clamp(Math.trunc(x + (y - x) * t), 0, 255);
  return (l(A[0], B[0]) << 16) | (l(A[1], B[1]) << 8) | l(A[2], B[2]);
}
function luminance(c) {
  const ch = (v) => { const x = v / 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
  const [r, g, b] = rgbOf(c);
  return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
}
/** A whole theme from five colours: the keyboard's ground, its keys, its function keys, the accent, the ink. */
function fromBase(background, key, functionKey, accent, text, extra = {}) {
  const dark = luminance(background) < 0.4;
  return {
    background, key, keyPressed: mix(key, text, 0.18), functionKey, functionKeyPressed: mix(functionKey, text, 0.18),
    accent, accentPressed: mix(accent, dark ? 0xffffff : 0x000000, 0.15), onAccent: luminance(accent) > 0.5 ? 0x111111 : 0xffffff,
    text, hint: mix(text, background, 0.45), popup: mix(key, text, dark ? 0.08 : 0),
    popupSelected: accent, onPopupSelected: luminance(accent) > 0.5 ? 0x111111 : 0xffffff,
    popupShadow: dark ? 'rgba(0,0,0,.4)' : 'rgba(0,0,0,.2)', toolbarIcon: mix(text, background, 0.2), dark, mechanical: false, ...extra,
  };
}
const LIGHT = {
  background: 0xe8eaf0, key: 0xffffff, keyPressed: 0xd5d9e0, functionKey: 0xcdd3dd, functionKeyPressed: 0xb4bbc7,
  accent: 0x008b8a, accentPressed: 0x006e6d, onAccent: 0xffffff, text: 0x1f1f1f, hint: 0x5f6368, popup: 0xffffff,
  popupSelected: 0x008b8a, onPopupSelected: 0xffffff, popupShadow: 'rgba(0,0,0,.2)', toolbarIcon: 0x444746, dark: false, mechanical: false,
};
const DARK = {
  background: 0x0a0b0c, key: 0x232528, keyPressed: 0x383b3f, functionKey: 0x16181a, functionKeyPressed: 0x2c2f33,
  accent: 0x51fcfb, accentPressed: 0x2ed8d7, onAccent: 0x00302f, text: 0xe8eded, hint: 0x8a9393, popup: 0x2c2f33,
  popupSelected: 0x51fcfb, onPopupSelected: 0x00302f, popupShadow: 'rgba(0,0,0,.5)', toolbarIcon: 0xb9c4c4, dark: true, mechanical: false,
};
/** The keyboard's themes by id: Ghost (its own dark look), Light, and the themes plugin's presets. */
export const THEMES = {
  ghost: DARK,
  light: LIGHT,
  glacier: fromBase(0x0e1621, 0x1d2a3a, 0x152030, 0x7cc4fa, 0xe6f1fb),
  midnight: fromBase(0x000000, 0x1a1a1a, 0x0d0d0d, 0x8ab4f8, 0xffffff),
  paper: fromBase(0xf4efe6, 0xfffcf6, 0xe4dccd, 0xb5522b, 0x2b2622),
  // A beige mechanical board: cream caps, grey modifiers, a red-orange enter, on a dark case.
  retro: fromBase(0x34302b, 0xe9e1cd, 0xa8a190, 0xd9542b, 0x2e2a24, { hint: 0x7a715f, toolbarIcon: 0xd8cfbb, mechanical: true }),
};
export const THEME_NAMES = { ghost: 'Ghost', light: 'Light', glacier: 'Glacier', midnight: 'Midnight', paper: 'Paper', retro: 'Retro' };

const parseHex = (s) => { const m = /^#?([0-9a-f]{6})$/i.exec(String(s).trim()); return m ? parseInt(m[1], 16) : null; };
/** A theme from an id, "#bg,#key,#fn,#accent,#text" (the app's own-theme format), or {background,key,functionKey,accent,text}. */
function resolveTheme(spec) {
  if (spec && typeof spec === 'object') {
    if ('keyPressed' in spec) return spec;
    const c = ['background', 'key', 'functionKey', 'accent', 'text'].map((k) => (typeof spec[k] === 'number' ? spec[k] : parseHex(spec[k])));
    if (c.every((v) => v != null)) return fromBase(c[0], c[1], c[2], c[3], c[4], spec.mechanical ? { mechanical: true } : {});
    return DARK;
  }
  if (spec === 'auto') {
    // The page's own pin first (<html data-theme="light|dark">, as site.css reads it), then the reader's system.
    const pin = HAS_DOM ? document.documentElement.dataset.theme : null;
    return pin === 'light' ? LIGHT : pin === 'dark' ? DARK : DARK_MQ && !DARK_MQ.matches ? LIGHT : DARK;
  }
  if (THEMES[spec]) return THEMES[spec];
  if (typeof spec === 'string' && spec.includes(',')) {
    const c = spec.split(',').map(parseHex);
    if (c.length === 5 && c.every((v) => v != null)) return fromBase(c[0], c[1], c[2], c[3], c[4]);
  }
  return DARK;
}
/** A theme's colours as the CSS custom properties kbd.css reads. */
export function themeVars(spec) {
  const t = resolveTheme(spec);
  return {
    '--kb-bg': cssOf(t.background), '--kb-key': cssOf(t.key), '--kb-key-down': cssOf(t.keyPressed), '--kb-fn': cssOf(t.functionKey),
    '--kb-fn-down': cssOf(t.functionKeyPressed), '--kb-accent': cssOf(t.accent), '--kb-accent-down': cssOf(t.accentPressed),
    '--kb-on-accent': cssOf(t.onAccent), '--kb-text': cssOf(t.text), '--kb-hint': cssOf(t.hint), '--kb-popup': cssOf(t.popup),
    '--kb-popup-sel': cssOf(t.popupSelected), '--kb-on-popup-sel': cssOf(t.onPopupSelected), '--kb-popup-shadow': t.popupShadow,
    '--kb-icon': cssOf(t.toolbarIcon),
  };
}
/** Puts a theme's custom properties on any element (a phone frame around the keyboard, a swatch). */
export function applyTheme(el, spec) {
  const vars = themeVars(spec);
  for (const k in vars) el.style.setProperty(k, vars[k]);
  return resolveTheme(spec);
}

// --- Backlight.kt ----------------------------------------------------------------------------------------------------

const FADE_MS = 1200;
const RIPPLE_S = 1.6;
export const BACKLIGHT_COLOURS = { ghost: 0x51fcfb, ice: 0x7fd8ff, phosphor: 0x39ff88, warm: 0xff8a3d, violet: 0xb47cff, rose: 0xff5c8a, white: 0xffffff };
/** Settings › Backlight's effects: id, name, what it does (catalogue.dart). */
export const EFFECTS = [
  ['reactive', 'Reactive', 'The key you press glows, then fades.'],
  ['ripple', 'Ripple', 'A ring of light runs out from each key.'],
  ['heatmap', 'Heatmap', 'Keys warm with use and cool when left.'],
  ['rainbow', 'Rainbow wave', 'A slow hue wave across the board; presses flare.'],
  ['starlight', 'Starlight', 'Keys twinkle at random; presses flare.'],
  ['breathe', 'Breathing', 'The board breathes softly; presses flare.'],
];
const STRENGTHS = { low: 0.35, medium: 0.6, high: 0.9 };
const FADES = { fast: 0.5, medium: 1, slow: 2 };

function hsv(h, s, v) {
  h = ((h % 360) + 360) % 360;
  const c = v * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = v - c;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
}
/** Java's String.hashCode, which seeds each key's twinkle in the starlight effect. */
function jhash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0; return h; }

/** Backlight.kt, line for line: how lit a key's legend is, and in what colour. Times are milliseconds. */
export class Backlight {
  constructor(effect = 'reactive', colour = 'accent', strength = 0.6, mode = 'brighten', fade = 1) {
    this.effect = effect; this.colour = colour; this.strength = strength; this.mode = mode; this.fade = fade;
    this.fadeMs = FADE_MS * fade;
    this.rippleS = RIPPLE_S * fade;
    this.presses = new Map();
    this.ripples = [];
    this.heat = new Map();
    this.heatAt = 0;
  }
  get dims() { return this.mode === 'dim'; }
  press(id, x, y, now) {
    this.presses.set(id, now);
    if (this.effect === 'ripple') { this.ripples.push([x, y, now]); while (this.ripples.length > 12) this.ripples.shift(); }
    if (this.effect === 'heatmap') { this.cool(now); this.heat.set(id, (this.heat.get(id) || 0) + 1); }
  }
  cool(now) {
    if (this.heatAt !== 0) {
      const k = Math.exp(-(now - this.heatAt) / 30000);
      for (const [id, v] of this.heat) { const n = v * k; if (n < 0.02) this.heat.delete(id); else this.heat.set(id, n); }
    }
    this.heatAt = now;
  }
  /** How lit the key `id` at (x, y) is, 0 to 1, on a board `width` wide. */
  light(id, x, y, width, now) {
    const at = this.presses.get(id);
    const since = at == null ? Infinity : now - at;
    const flare = since < this.fadeMs ? (1 - since / this.fadeMs) ** 2 : 0;
    switch (this.effect) {
      case 'ripple': {
        let sum = flare;
        const ring = width * 0.07;
        for (const [rx, ry, t] of this.ripples) {
          const age = (now - t) / 1000;
          if (age > this.rippleS) continue;
          const off = (Math.hypot(x - rx, y - ry) - age * width * 0.6) / ring;
          sum += Math.exp(-off * off) * (1 - age / this.rippleS);
        }
        return Math.min(1, sum);
      }
      case 'heatmap': {
        let max = 0;
        for (const v of this.heat.values()) if (v > max) max = v;
        return Math.max(max > 0 ? ((this.heat.get(id) || 0) / max) * 0.85 : 0, flare * 0.6);
      }
      case 'rainbow': return Math.max(0.38, flare);
      case 'starlight': {
        const seed = (jhash(id) & 0xffff) / 6553.6;
        const twinkle = Math.sin(now / 650 + seed * 2.7);
        return Math.max(twinkle > 0.86 ? ((twinkle - 0.86) / 0.14) * 0.7 : 0, flare);
      }
      case 'breathe': return Math.max(0.12 + 0.14 * (0.5 + 0.5 * Math.sin(now / 1300)), flare);
      default: return flare;
    }
  }
  /** The light's colour at x: the accent, a fixed colour, or (rainbow) a hue across the board and time. [r, g, b]. */
  colourAt(x, width, now, accent, heatLevel = 0) {
    if (this.colour === 'rainbow' || this.effect === 'rainbow') return hsv((x / width) * 300 + now / 25, 0.75, 1);
    if (this.effect === 'heatmap' && this.colour === 'accent') return hsv(210 - 190 * heatLevel, 0.8, 1);
    const fixed = BACKLIGHT_COLOURS[this.colour] ?? parseHex(this.colour);
    return rgbOf(fixed ?? accent);
  }
  flaring(now) { for (const t of this.presses.values()) if (now - t < this.fadeMs) return true; return false; }
  /** Whether anything is still moving. (A warm heatmap is still: it only changes with a press, so it costs nothing.) */
  animating(now) {
    switch (this.effect) {
      case 'rainbow': case 'starlight': case 'breathe': return true;
      case 'ripple': return this.ripples.some((r) => now - r[2] < this.rippleS * 1000) || this.flaring(now);
      default: return this.flaring(now);
    }
  }
  /** Effects that only change slowly draw at a gentle rate (ms between frames). */
  frameDelay() { return this.effect === 'heatmap' ? 120 : this.effect === 'breathe' ? 50 : 16; }
}

// --- RowIcons.kt -----------------------------------------------------------------------------------------------------

// Phosphor's icons (MIT), regular with the duotone wash, as the app draws them: paths filled, the wash under them.
const ICONS = {
  'settings': {"box":24,"washAlpha":0.2,"paths":["M12 7.5a4.5 4.5 0 104.5 4.5A4.5 4.5 0 0012 7.5Zm0 7.5a3 3 0 113-3 3 3 0 01-3 3Zm8.25-2.8q.01-.2 0-.4l1.4-1.75a.75.75 0 00.14-.66 10.05 10.05 0 00-1.02-2.46.75.75 0 00-.57-.37l-2.22-.25q-.14-.15-.28-.28L17.44 3.8a.75.75 0 00-.37-.56 10.1 10.1 0 00-2.46-1.02.75.75 0 00-.66.14L12.2 3.75q-.2 0-.4 0l-1.75-1.4a.75.75 0 00-.66-.13A10.09 10.09 0 006.93 3.24a.75.75 0 00-.37.56L6.31 6.03q-.15.13-.28.28L3.8 6.56a.75.75 0 00-.56.37A10.1 10.1 0 002.22 9.39a.75.75 0 00.14.66L3.75 11.8q0 .2 0 .4l-1.4 1.75a.75.75 0 00-.13.66 10.05 10.05 0 001.02 2.46.75.75 0 00.56.37l2.22.25q.14.15.28.28l.26 2.23a.75.75 0 00.37.56 10.1 10.1 0 002.46 1.02.75.75 0 00.66-.14l1.75-1.39q.2.01.4 0l1.75 1.4a.75.75 0 00.66.14 10.05 10.05 0 002.46-1.02.75.75 0 00.37-.57l.25-2.22q.15-.14.28-.28l2.23-.26a.75.75 0 00.56-.37 10.1 10.1 0 001.02-2.46.75.75 0 00-.14-.66Zm-1.51-.61a6.93 6.93 0 010 .82.75.75 0 00.16.51l1.33 1.66a8.58 8.58 0 01-.58 1.41l-2.12.24a.75.75 0 00-.48.25 6.95 6.95 0 01-.57.57.75.75 0 00-.25.48l-.24 2.12a8.56 8.56 0 01-1.4.58l-1.66-1.33a.75.75 0 00-.47-.16h-.05a6.93 6.93 0 01-.81 0 .75.75 0 00-.52.16L9.42 20.23a8.58 8.58 0 01-1.41-.58l-.24-2.12a.75.75 0 00-.25-.48 6.95 6.95 0 01-.57-.57.75.75 0 00-.48-.25l-2.12-.24a8.56 8.56 0 01-.58-1.4l1.33-1.67a.75.75 0 00.16-.51 6.93 6.93 0 010-.81.75.75 0 00-.16-.52L3.77 9.42a8.58 8.58 0 01.58-1.41l2.12-.24a.75.75 0 00.48-.25 6.95 6.95 0 01.57-.57.75.75 0 00.25-.48l.24-2.12a8.56 8.56 0 011.4-.58l1.67 1.33a.75.75 0 00.51.16 6.93 6.93 0 01.81 0 .75.75 0 00.52-.16l1.66-1.33a8.58 8.58 0 011.41.58l.24 2.12a.75.75 0 00.25.48 6.95 6.95 0 01.57.57.75.75 0 00.48.25l2.12.24a8.56 8.56 0 01.58 1.4l-1.33 1.66a.75.75 0 00-.16.52Z"],"wash":["M19.49 11.55l1.57-1.97a9.29 9.29 0 00-.94-2.28l-2.51-.28a7.59 7.59 0 00-.64-.64l-.28-2.5a9.32 9.32 0 00-2.27-.94l-1.97 1.57a7.65 7.65 0 00-.91 0L9.57 2.94A9.29 9.29 0 007.3 3.88l-.28 2.51a7.59 7.59 0 00-.64.64l-2.5.28a9.32 9.32 0 00-.94 2.27l1.57 1.97a7.65 7.65 0 000 .91l-1.57 1.97a9.29 9.29 0 00.95 2.27l2.5.28a7.59 7.59 0 00.64.64l.28 2.51a9.32 9.32 0 002.28.93l1.97-1.57a7.65 7.65 0 00.9 0l1.97 1.58a9.29 9.29 0 002.28-.95l.28-2.5a7.59 7.59 0 00.63-.64l2.51-.28a9.32 9.32 0 00.94-2.28l-1.58-1.97a7.65 7.65 0 000-.9ZM12 15.75A3.75 3.75 0 1115.75 12 3.75 3.75 0 0112 15.75Z"],"rotate":0},
  'hide': {"box":24,"washAlpha":0.33,"paths":["M20.03 9.53l-7.5 7.5a.75.75 0 01-1.06 0l-7.5-7.5A.74.74 0 015.03 8.47L12 15.44l6.97-6.97a.74.74 0 011.06 1.06Z"],"wash":["M20.03 9.53l-7.5 7.5a.75.75 0 01-1.06 0l-7.5-7.5A.75.75 0 014.5 8.25h15a.75.75 0 01.53 1.28Z"],"rotate":0},
  'back': {"box":24,"washAlpha":0.33,"paths":["M21 12a.75.75 0 01-.75.75H5.56l5.47 5.47a.74.74 0 01-1.06 1.06l-6.75-6.75a.75.75 0 010-1.06l6.75-6.75a.74.74 0 011.06 1.06L5.56 11.25H20.25A.75.75 0 0121 12Z"],"wash":["M21 12a.75.75 0 01-.75.75h-9v6a.75.75 0 01-1.28.53l-6.75-6.75a.75.75 0 010-1.06l6.75-6.75a.75.75 0 011.28.53v6h9A.75.75 0 0121 12Z"],"rotate":0},
  'forward': {"box":24,"washAlpha":0.33,"paths":["M20.78 12.53l-6.75 6.75a.74.74 0 01-1.06-1.06l5.47-5.47H3.75a.75.75 0 010-1.5H18.44L12.97 5.78a.74.74 0 011.06-1.06l6.75 6.75a.75.75 0 010 1.06Z"],"wash":["M20.78 12.53l-6.75 6.75a.75.75 0 01-1.28-.53v-6h-9a.75.75 0 010-1.5h9v-6a.75.75 0 011.28-.53l6.75 6.75a.75.75 0 010 1.06Z"],"rotate":0},
  'search': {"box":24,"washAlpha":0.2,"paths":["M21.53 20.47l-4.69-4.69a8.26 8.26 0 10-1.06 1.06l4.69 4.69a.74.74 0 001.06-1.06ZM3.75 10.5a6.75 6.75 0 116.75 6.75A6.76 6.76 0 013.75 10.5Z"],"wash":["M18 10.5A7.5 7.5 0 1110.5 3 7.5 7.5 0 0118 10.5Z"],"rotate":0},
  'key': {"box":24,"washAlpha":0.2,"paths":["M20.3 3.7A7.5 7.5 0 007.87 11.32L2.69 16.5a1.49 1.49 0 00-.44 1.06v2.69a1.5 1.5 0 001.5 1.5h3A.75.75 0 007.5 21V19.5H9a.75.75 0 00.75-.75v-1.5h1.5a.75.75 0 00.53-.22l.9-.9A7.47 7.47 0 0015 16.5h.01A7.5 7.5 0 0020.3 3.7ZM21 9.2c-.1 3.19-2.79 5.8-5.99 5.8H15a5.97 5.97 0 01-2.22-.42.75.75 0 00-.83.15l-1.01 1.02H9a.75.75 0 00-.75.75V18H6.75a.75.75 0 00-.75.75v1.5H3.75V17.56l5.52-5.51a.75.75 0 00.15-.83A5.97 5.97 0 019 8.99c0-3.2 2.61-5.88 5.8-5.99A6 6 0 0121 9.2ZM18 7.13A1.13 1.13 0 1116.88 6 1.13 1.13 0 0118 7.13Z"],"wash":["M21.75 9.22c-.12 3.62-3.12 6.53-6.74 6.53a6.72 6.72 0 01-2.53-.48h0L11.25 16.5H9v2.25H6.75V21h-3A.75.75 0 013 20.25V17.56a.75.75 0 01.22-.53l5.51-5.51h0A6.72 6.72 0 018.25 8.99c0-3.62 2.91-6.62 6.53-6.74a6.75 6.75 0 016.97 6.97Z"],"rotate":0},
  'gif': {"box":24,"washAlpha":0.2,"paths":["M20.25 3.75H3.75a1.5 1.5 0 00-1.5 1.5v13.5a1.5 1.5 0 001.5 1.5h16.5a1.5 1.5 0 001.5-1.5V5.25a1.5 1.5 0 00-1.5-1.5Zm0 1.5v9.63l-2.44-2.44a1.5 1.5 0 00-2.13 0l-1.87 1.87-4.13-4.12a1.5 1.5 0 00-2.12 0L3.75 14V5.25ZM3.75 16.13l4.88-4.88 7.5 7.5H3.75Zm16.5 2.62h-2l-3.38-3.37 1.88-1.88 3.5 3.5v1.75ZM13.5 9.38a1.13 1.13 0 111.13 1.12A1.13 1.13 0 0113.5 9.38Z"],"wash":["M21 5.25V16.69l-3.72-3.72a.75.75 0 00-1.06 0l-2.41 2.41L9.16 10.72a.75.75 0 00-1.07 0L3 15.81V5.25a.75.75 0 01.75-.75h16.5a.75.75 0 01.75.75Z"],"rotate":0},
  'emoji': {"box":256,"washAlpha":0.2,"paths":["M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm0,192a88,88,0,1,1,88-88A88.1,88.1,0,0,1,128,216ZM80,108a12,12,0,1,1,12,12A12,12,0,0,1,80,108Zm96,0a12,12,0,1,1-12-12A12,12,0,0,1,176,108Zm-1.08,48c-10.29,17.79-27.39,28-46.92,28s-36.63-10.2-46.92-28a8,8,0,1,1,13.84-8c7.47,12.91,19.21,20,33.08,20s25.61-7.1,33.08-20a8,8,0,1,1,13.84,8Z"],"wash":["M224,128a96,96,0,1,1-96-96A96,96,0,0,1,224,128Z"]},
  'mic': {"box":256,"washAlpha":0.2,"paths":["M128,176a48.05,48.05,0,0,0,48-48V64a48,48,0,0,0-96,0v64A48.05,48.05,0,0,0,128,176ZM96,64a32,32,0,0,1,64,0v64a32,32,0,0,1-64,0Zm40,143.6V240a8,8,0,0,1-16,0V207.6A80.11,80.11,0,0,1,48,128a8,8,0,0,1,16,0,64,64,0,0,0,128,0,8,8,0,0,1,16,0A80.11,80.11,0,0,1,136,207.6Z"],"wash":["M168,64v64a40,40,0,0,1-40,40h0a40,40,0,0,1-40-40V64a40,40,0,0,1,40-40h0A40,40,0,0,1,168,64Z"],"rotate":0},
  'delete': {"box":256,"washAlpha":0.2,"paths":["M216,40H68.53a16.08,16.08,0,0,0-13.72,7.77L9.14,123.88a8,8,0,0,0,0,8.24l45.67,76.11A16.08,16.08,0,0,0,68.53,216H216a16,16,0,0,0,16-16V56A16,16,0,0,0,216,40ZM61.67,204.12,68.53,200h0ZM216,200H68.53l-43.2-72,43.2-72H216ZM106.34,146.34,124.69,128l-18.35-18.34a8,8,0,0,1,11.32-11.32L136,116.69l18.34-18.35a8,8,0,0,1,11.32,11.32L147.31,128l18.35,18.34a8,8,0,0,1-11.32,11.32L136,139.31l-18.34,18.35a8,8,0,0,1-11.32-11.32Z"],"wash":["M224,56V200a8,8,0,0,1-8,8H68.53a8,8,0,0,1-6.86-3.88L16,128,61.67,51.88A8,8,0,0,1,68.53,48H216A8,8,0,0,1,224,56Z"],"rotate":0},
  'shift': {"box":256,"washAlpha":0.2,"paths":["M229.66,114.34l-96-96a8,8,0,0,0-11.32,0l-96,96A8,8,0,0,0,32,128H72v80a16,16,0,0,0,16,16h80a16,16,0,0,0,16-16V128h40a8,8,0,0,0,5.66-13.66ZM176,112a8,8,0,0,0-8,8v88H88V120a8,8,0,0,0-8-8H51.31L128,35.31,204.69,112Z"],"wash":["M224,120H176v88a8,8,0,0,1-8,8H88a8,8,0,0,1-8-8V120H32l96-96Z"],"rotate":0},
  'enter': {"box":256,"washAlpha":0.2,"paths":["M200,32V176a8,8,0,0,1-8,8H67.31l34.35,34.34a8,8,0,0,1-11.32,11.32l-48-48a8,8,0,0,1,0-11.32l48-48a8,8,0,0,1,11.32,11.32L67.31,168H184V32a8,8,0,0,1,16,0Z"],"wash":["M96,128v96L48,176Z"],"rotate":0},
  'caret-left': {"box":256,"washAlpha":0.2,"paths":["M165.66,202.34a8,8,0,0,1-11.32,11.32l-80-80a8,8,0,0,1,0-11.32l80-80a8,8,0,0,1,11.32,11.32L91.31,128Z"],"wash":["M160,48V208L80,128Z"],"rotate":0},
  'caret-up': {"box":256,"washAlpha":0.2,"paths":["M165.66,202.34a8,8,0,0,1-11.32,11.32l-80-80a8,8,0,0,1,0-11.32l80-80a8,8,0,0,1,11.32,11.32L91.31,128Z"],"wash":["M160,48V208L80,128Z"],"rotate":90},
  'caret-right': {"box":256,"washAlpha":0.2,"paths":["M165.66,202.34a8,8,0,0,1-11.32,11.32l-80-80a8,8,0,0,1,0-11.32l80-80a8,8,0,0,1,11.32,11.32L91.31,128Z"],"wash":["M160,48V208L80,128Z"],"rotate":180},
  'caret-down': {"box":256,"washAlpha":0.2,"paths":["M165.66,202.34a8,8,0,0,1-11.32,11.32l-80-80a8,8,0,0,1,0-11.32l80-80a8,8,0,0,1,11.32,11.32L91.31,128Z"],"wash":["M160,48V208L80,128Z"],"rotate":270},
};

/** One of the keyboard's icons as inline SVG: settings, hide, back, forward, search, key, gif, emoji, mic, delete, shift, enter, caret-left/up/down/right. */
export function icon(name, { size = null, filled = false, cls = '' } = {}) {
  const g = ICONS[name];
  if (!g) return '';
  const dim = size == null ? '' : ` width="${size}" height="${size}"`;
  const turn = g.rotate ? ` style="transform:rotate(${g.rotate}deg)"` : '';
  const wash = g.wash.map((d) => `<path d="${d}" fill="currentColor" fill-opacity="${filled ? 1 : g.washAlpha}" class="gk-wash"/>`).join('');
  const lines = g.paths.map((d) => `<path d="${d}" fill="currentColor"/>`).join('');
  return `<svg class="gk-ico${cls ? ' ' + cls : ''}" viewBox="0 0 ${g.box} ${g.box}"${dim}${turn} aria-hidden="true" focusable="false">${wash}${lines}</svg>`;
}
export const ICON_NAMES = Object.keys(ICONS);

// --- the text target -------------------------------------------------------------------------------------------------

const chars = (s) => Array.from(s);

/**
 * What the keyboard types into: a text and a caret. Bound to an element it draws the text there with a visible caret
 * (any block element), or keeps a real <textarea>/<input> in step. Unbound it is only the model.
 */
export class GhostField {
  constructor(el = null, opts = {}) {
    this.el = el;
    this.opts = opts;
    this._text = '';
    this._caret = 0;
    this._subs = new Set();
    this._native = !!el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT');
    if (!el) return;
    if (this._native) {
      this._text = opts.text ?? el.value;
      this._caret = this._text.length;
      this._onNative = () => { this._text = el.value; this._caret = el.selectionStart ?? el.value.length; this._changed('native'); };
      el.addEventListener('input', this._onNative);
    } else {
      this._text = opts.text ?? el.textContent ?? '';
      this._caret = this._text.length;
      el.classList.add('gk-field');
      if (opts.placeholder != null) el.dataset.placeholder = opts.placeholder;
      this._caretEl = document.createElement('span');
      this._caretEl.className = 'gk-caret';
      this._caretEl.setAttribute('aria-hidden', 'true');
      this._onClick = (e) => { const at = this._offsetAt(e.clientX, e.clientY); if (at != null) this.caret = at; };
      el.addEventListener('click', this._onClick);
    }
    this.render();
  }

  get text() { return this._text; }
  set text(v) { this.set(v); }
  get caret() { return this._caret; }
  set caret(n) { const c = clamp(n | 0, 0, this._text.length); if (c !== this._caret) { this._caret = c; this._changed('caret'); } }
  get before() { return this._text.slice(0, this._caret); }
  get after() { return this._text.slice(this._caret); }

  /** Replaces the whole text; the caret goes to its end unless given. */
  set(text, caret = null, why = 'set') {
    this._text = String(text ?? '');
    this._caret = clamp(caret == null ? this._text.length : caret, 0, this._text.length);
    this._changed(why);
  }
  clear() { this.set('', 0, 'clear'); }
  /** Types `str` at the caret, leaving the caret `caretBack` characters before its end (a pair's inside). */
  insert(str, caretBack = 0) {
    str = String(str);
    this._text = this.before + str + this.after;
    this._caret = clamp(this._caret + str.length - caretBack, 0, this._text.length);
    this._changed('insert');
  }
  /** Deletes `n` characters before the caret (whole code points, so an emoji goes at once). */
  backspace(n = 1) {
    const b = chars(this.before);
    if (!b.length) return;
    const kept = b.slice(0, Math.max(0, b.length - n)).join('');
    this._text = kept + this.after;
    this._caret = kept.length;
    this._changed('delete');
  }
  /** Moves the caret `steps` characters (negative: left), as the space-bar cursor and the arrow keys do. */
  move(steps) {
    if (!steps) return;
    const b = chars(this.before), a = chars(this.after);
    const next = steps < 0 ? b.slice(0, Math.max(0, b.length + steps)).join('').length : this._caret + a.slice(0, steps).join('').length;
    this.caret = next;
  }
  /** Moves the caret a line up (-1) or down (1), keeping its column. */
  moveLine(dir) {
    const t = this._text, c = this._caret;
    const start = t.lastIndexOf('\n', c - 1) + 1;
    const col = c - start;
    if (dir < 0) {
      if (start === 0) { this.caret = 0; return; }
      const prev = t.lastIndexOf('\n', start - 2) + 1;
      this.caret = Math.min(prev + col, start - 1);
    } else {
      const end = t.indexOf('\n', c);
      if (end < 0) { this.caret = t.length; return; }
      const nextEnd = t.indexOf('\n', end + 1);
      this.caret = Math.min(end + 1 + col, nextEnd < 0 ? t.length : nextEnd);
    }
  }
  /** Replaces [start, end) with `str` (an autocorrection, a rewrite); the caret lands after it. */
  replace(start, end, str) {
    str = String(str);
    this._text = this._text.slice(0, start) + str + this._text.slice(end);
    this._caret = clamp(start + str.length, 0, this._text.length);
    this._changed('replace');
  }
  /** The letters (and apostrophes) just before the caret: the word being typed. */
  wordBefore() { const m = /[\p{L}\p{N}']+$/u.exec(this.before); return m ? m[0] : ''; }

  /** `fn({text, caret, why, field})` after every change. Returns a function that stops it. */
  on(type, fn) { if (type !== 'change') return () => {}; this._subs.add(fn); return () => this._subs.delete(fn); }

  _changed(why) {
    this.render();
    const e = { text: this._text, caret: this._caret, why, field: this };
    for (const fn of [...this._subs]) { try { fn(e); } catch (err) { console.error(err); } }
  }

  /** The text's runs: [{start, end, cls}] from opts.marks ([{re, cls}], first match wins), plain runs between. */
  _runs(text) {
    const marks = this.opts.marks;
    if (!marks || !marks.length || !text) return [{ start: 0, end: text.length, cls: null }];
    const found = [];
    for (const m of marks) {
      const re = new RegExp(m.re.source, m.re.flags.includes('g') ? m.re.flags : m.re.flags + 'g');
      let hit;
      while ((hit = re.exec(text))) {
        if (!hit[0].length) { re.lastIndex++; continue; }
        found.push({ start: hit.index, end: hit.index + hit[0].length, cls: m.cls });
      }
    }
    found.sort((a, b) => a.start - b.start);
    const runs = [];
    let at = 0;
    for (const f of found) {
      if (f.start < at) continue;
      if (f.start > at) runs.push({ start: at, end: f.start, cls: null });
      runs.push(f);
      at = f.end;
    }
    if (at < text.length) runs.push({ start: at, end: text.length, cls: null });
    return runs;
  }

  render() {
    const el = this.el;
    if (!el) return;
    if (this._native) {
      if (el.value !== this._text) el.value = this._text;
      try { el.setSelectionRange(this._caret, this._caret); } catch { /* an input type without a selection */ }
      return;
    }
    if (typeof this.opts.render === 'function') { this.opts.render(this, el, this._caretEl); return; }
    const frag = document.createDocumentFragment();
    const text = this._text, caret = this._caret;
    const put = (s, cls) => {
      if (!s) return;
      if (!cls) { frag.append(document.createTextNode(s)); return; }
      const span = document.createElement('span');
      span.className = cls;
      span.textContent = s;
      frag.append(span);
    };
    // A fresh caret each time restarts its blink, so it holds solid while text is going in.
    const c = this._caretEl.cloneNode(false);
    this._caretEl = c;
    let placed = this.opts.caret === false;
    for (const r of this._runs(text)) {
      if (!placed && caret <= r.start) { frag.append(c); placed = true; }
      if (!placed && caret < r.end) {
        put(text.slice(r.start, caret), r.cls); frag.append(c); placed = true; put(text.slice(caret, r.end), r.cls);
      } else put(text.slice(r.start, r.end), r.cls);
    }
    if (!placed) frag.append(c);
    el.replaceChildren(frag);
    el.classList.toggle('gk-field--empty', !text);
    if (this.opts.follow !== false && (el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1)) {
      const cr = c.getBoundingClientRect(), er = el.getBoundingClientRect();
      if (cr.bottom > er.bottom) el.scrollTop += cr.bottom - er.bottom + 4; else if (cr.top < er.top) el.scrollTop -= er.top - cr.top + 4;
      if (cr.right > er.right) el.scrollLeft += cr.right - er.right + 8; else if (cr.left < er.left) el.scrollLeft -= er.left - cr.left + 8;
    }
  }

  /** The caret position under a point of the page, or null. */
  _offsetAt(x, y) {
    let node = null, offset = 0;
    if (document.caretPositionFromPoint) { const p = document.caretPositionFromPoint(x, y); if (p) { node = p.offsetNode; offset = p.offset; } }
    else if (document.caretRangeFromPoint) { const r = document.caretRangeFromPoint(x, y); if (r) { node = r.startContainer; offset = r.startOffset; } }
    if (!node || !this.el.contains(node)) return null;
    try {
      const r = document.createRange();
      r.setStart(this.el, 0);
      r.setEnd(node, offset);
      return clamp(r.toString().length, 0, this._text.length);
    } catch { return null; }
  }

  destroy() {
    const el = this.el;
    if (!el) return;
    if (this._onNative) el.removeEventListener('input', this._onNative);
    if (this._onClick) el.removeEventListener('click', this._onClick);
    this._subs.clear();
  }
}

// --- sound -----------------------------------------------------------------------------------------------------------

// A soft "thock", made in WebAudio (the app plays recorded packs; nothing is fetched here). One context for the page,
// made only inside a person's own tap or key press, and only for a keyboard with sound on.
const audio = { ctx: null, noise: null, round: 0 };
function unlockAudio() {
  if (!HAS_DOM) return;
  const AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) return;
  try {
    if (!audio.ctx) audio.ctx = new AC();
    if (audio.ctx.state === 'suspended') audio.ctx.resume().catch(() => {});
  } catch { /* no audio: the keyboard is silent */ }
}
function thock(type, volume = 0.35) {
  const ctx = audio.ctx;
  if (!ctx || ctx.state !== 'running') return;
  // As SoundPacks.kt pitches one pack's key: a wobble across a run of keys, lower for backspace, lower still for enter.
  const rate = type === 'delete' ? 0.9 : type === 'enter' ? 0.8 : type === 'space' ? 0.74 : 0.97 + (audio.round++ % 4) * 0.02;
  const t = ctx.currentTime;
  const out = ctx.createGain();
  out.gain.value = volume * 0.5;
  out.connect(ctx.destination);
  const body = ctx.createOscillator(), bodyGain = ctx.createGain();
  body.type = 'sine';
  body.frequency.setValueAtTime(210 * rate, t);
  body.frequency.exponentialRampToValueAtTime(95 * rate, t + 0.06);
  bodyGain.gain.setValueAtTime(0.0001, t);
  bodyGain.gain.exponentialRampToValueAtTime(0.9, t + 0.004);
  bodyGain.gain.exponentialRampToValueAtTime(0.0001, t + (type === 'space' ? 0.11 : 0.075));
  body.connect(bodyGain).connect(out);
  body.start(t); body.stop(t + 0.14);
  if (!audio.noise) {
    const n = Math.floor(ctx.sampleRate * 0.04);
    audio.noise = ctx.createBuffer(1, n, ctx.sampleRate);
    const d = audio.noise.getChannelData(0);
    for (let i = 0; i < n; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / n);
  }
  const click = ctx.createBufferSource(), band = ctx.createBiquadFilter(), clickGain = ctx.createGain();
  click.buffer = audio.noise;
  click.playbackRate.value = rate;
  band.type = 'bandpass'; band.frequency.value = 1900 * rate; band.Q.value = 0.9;
  clickGain.gain.setValueAtTime(0.5, t);
  clickGain.gain.exponentialRampToValueAtTime(0.0001, t + 0.03);
  click.connect(band).connect(clickGain).connect(out);
  click.start(t);
}

// --- the keyboard ----------------------------------------------------------------------------------------------------

// KeyboardView's own numbers (its companion object), in dp.
const SPLIT_MIN = 600;          // a board this wide is on a wide screen: an unfolded Fold's inner screen, a tablet
const SPLIT_GAP = 0.3;          // the open middle of a split keyboard, as a share of its width
const COMPACT_DP = 400;         // a docked keyboard on a wide screen is this wide: a phone's, in reach of one thumb
const ONE_HANDED_SHARE = 0.8;   // one-handed on a phone: the keys take this share of the width, on the thumb's side
const DOCK_SLACK_DP = 14;       // a touch this far past a docked keyboard's edge still means its nearest key
/** Settings › Look & feel › Key height: a row, from one to the next, in dp. */
export const KEY_HEIGHTS = { condensed: 46, short: 52, normal: 60, tall: 68 };
/** Settings › Look & feel › Layout's choices for a wide screen (catalogue.dart wideLayouts): [id, name]. */
export const WIDE_LAYOUTS = [['split', 'Split'], ['full', 'Full width'], ['left', 'Docked left'], ['center', 'Centred'], ['right', 'Docked right']];
const isWideLayout = (id) => WIDE_LAYOUTS.some((l) => l[0] === id);
const fold = (s) => s.normalize('NFD').replace(/[̀-ͯ]/g, '');
const isLetter = (s) => s.length === 1 && s.toLowerCase() !== s.toUpperCase();
const lightId = (key) => `${key.type.toUpperCase()}|${key.text}|${key.label}`;
const typeOfChar = (c) => (c === ' ' ? 'space' : c === '\n' ? 'enter' : c === '\b' ? 'delete' : 'char');
function mulberry32(a) { return () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
const ARIA = { space: 'space', delete: 'backspace', enter: 'enter', shift: 'shift', mic: 'dictate' };
const PAGE_NAMES = { letters: 'letters', symbols: 'numbers and symbols', more: 'more symbols', dev: 'developer keys' };
let measureCtx = null;

const DEFAULTS = {
  theme: 'ghost', backlight: 'reactive', row: true, arrows: false, sound: false, numberRow: false, interactive: true,
  borders: false, hints: true, preview: true, mic: false, split: false, swipe: false, autoCaps: true, doubleSpacePeriod: true,
  spaceCursor: true, keyHeight: 'normal', longPressMs: 300, enter: 'newline', touchAction: 'pan-y', scale: null,
  volume: 0.35, motion: 'auto', label: 'Ghost Keyboard', fragment: null, cog: true, bare: false, hardKeys: 'all',
  wideLayout: null, oneHanded: 'off',
};
// mic: the dictation key in the full stop's place on the letters page, as the app has it with dictation on.
// keyHeight: 'condensed' | 'short' | 'normal' | 'tall' (KEY_HEIGHTS).
// wideLayout: what a board on a wide screen does (WIDE_LAYOUTS: 'split' | 'full' | 'left' | 'center' | 'right'); null
//   leaves it to `split`, the older way to ask for 'split'. A board is on a wide screen when it is SPLIT_MIN dp across:
//   with `scale` (the dp, in CSS px) that is its width / scale; without, a board 600 CSS px wide that asks for a wide
//   layout is drawn at a phone's own size (1dp = 1px), and any other is a phone's board scaled to its width.
// oneHanded: 'off' | 'left' | 'right', on a board that is not on a wide screen: the keys narrow toward that thumb.
// A docked or one-handed board has a chevron on its empty side; a tap there moves the keys across ('dock' fires).

// --- fragments -------------------------------------------------------------------------------------------------------

const UNIT_DP = (412 - 3) / 10;          // a key's width on a phone, in dp: what a fragment's keys keep
const HEADROOM = { preview: 52, panel: 58 };
const KEY_TYPES = { space: 'space', enter: 'enter', delete: 'delete', backspace: 'delete', shift: 'shift', mic: 'mic' };

/**
 * A fragment spec made regular, or null for the whole board.
 *   'bar'                                    only the row above the keys (the cog, suggestions, chips)
 *   ['g', 'h', 'o', 's', 't']                named keys in one row; [['q', 'w'], ['a', 's']] in rows
 *   {keys: […], page, insets, align}         the same, with options
 *   {row: 1} | {rows: [1, 2]}                whole rows of the page showing (negative counts from the bottom row)
 *   {block: {from: 'w', to: 'c'}}            the keys in the rectangle between two keys, at their true stagger
 *   {block: {row, col, rows, cols}}          the same in key units from the board's top left
 * Common: {page = 'letters', headroom: 'preview' | 'panel' | dp, fit: 'width' | 'contain', minDp = .5,
 *          maxDp = 1.6 (1.25 for 'bar'), width (dp the fragment is designed at; 'bar': 360), align: 'center' | 'start' | 'end'}.
 */
function normFragment(spec) {
  if (!spec) return null;
  if (spec === 'bar') return { kind: 'bar', page: 'letters' };
  if (Array.isArray(spec)) return { kind: 'keys', page: 'letters', keys: spec };
  if (typeof spec !== 'object') return null;
  const f = { page: 'letters', ...spec };
  f.kind = spec.keys ? 'keys' : spec.block ? 'block' : spec.rows != null || spec.row != null ? 'rows' : 'bar';
  if (f.kind === 'rows') f.rows = [].concat(spec.rows ?? spec.row);
  if (!PAGES.includes(f.page)) f.page = 'letters';
  return f;
}

export class GhostKbd {
  /**
   * @param {HTMLElement} host  the element the keyboard is put in; it takes the host's width.
   * @param {object} [opts]     see DEFAULTS above; `fragment` (normFragment, above) shows only part of the board.
   *                            site-dev/kbd.html exercises the whole board, site-dev/keycaps.html the fragments.
   */
  constructor(host, opts = {}) {
    if (!HAS_DOM) throw new Error('GhostKbd needs a document');
    this.host = host;
    this.opts = { ...DEFAULTS, ...opts };
    this._frag = normFragment(this.opts.fragment);
    // A fragment shows the row above the keys only when asked to, or when it is nothing but that row.
    if (this._frag && !('row' in opts)) this.opts.row = this._frag.kind === 'bar';
    this.page = this._frag ? this._frag.page : 'letters';
    this.shift = 'off';
    this.visible = true;
    this._subs = new Map();
    this.detach();
    this._keys = [];
    this._geo = null;
    this._touch = null;
    this._ghost = null;
    this._run = 0;
    this._sleepers = new Set();
    this._waiters = new Set();
    this._leavers = new Set();
    this._resumers = new Set();
    this._timers = new Set();
    this._rowItems = [];
    this._rowSig = null;
    this._autoShifted = false;
    this._lastShiftTap = 0;
    this._dictation = null;
    this._level = 0;
    this._overlay = null;
    this._overlayDirty = true;
    this._trail = null;
    this._fading = null;
    this._adaptive = null;
    this._adaptiveMap = false;
    this._wordLight = new Backlight('reactive', 'accent', 0.85);
    this._backlight = null;
    this._raf = 0;
    this._lastLight = -1e9;
    this._lit = false;
    this._W = 0;
    this._H = 0;
    this._loop = null;
    this._lastUser = 0;
    this._seen = new Promise((resolve) => { this._sawFirst = resolve; });

    const el = (this.el = document.createElement('div'));
    el.className = 'gk';
    el.innerHTML =
      `<div class="gk-bar"><button type="button" class="gk-ib gk-cog" tabindex="-1" aria-label="Keyboard settings">${icon('settings')}<i class="gk-badge" hidden></i></button>` +
      `<div class="gk-strip"></div><div class="gk-arrows" hidden>` +
      [['left', 'caret-left'], ['up', 'caret-up'], ['down', 'caret-down'], ['right', 'caret-right']]
        .map(([d, i]) => `<button type="button" class="gk-ib gk-arrow" tabindex="-1" data-dir="${d}" aria-label="Cursor ${d}">${icon(i)}</button>`).join('') +
      `</div></div><div class="gk-keys"></div>` +
      `<button type="button" class="gk-dock" tabindex="-1" hidden><svg viewBox="-7 -12 14 24" aria-hidden="true"><path d="M-5-10 5 0-5 10"/></svg></button>` +
      `<div class="gk-dict" hidden><div class="gk-dict-halo"></div><div class="gk-dict-dot">${icon('mic')}</div><div class="gk-dict-label"></div></div>` +
      `<canvas class="gk-overlay" aria-hidden="true"></canvas><div class="gk-pop" aria-hidden="true" hidden></div><div class="gk-panel" aria-hidden="true" hidden></div>`;
    this._bar = el.querySelector('.gk-bar');
    this._strip = el.querySelector('.gk-strip');
    this._arrows = el.querySelector('.gk-arrows');
    this._cog = el.querySelector('.gk-cog');
    this._keysEl = el.querySelector('.gk-keys');
    this._dock = el.querySelector('.gk-dock');
    this._dict = el.querySelector('.gk-dict');
    this._canvas = el.querySelector('.gk-overlay');
    this._pop = el.querySelector('.gk-pop');
    this._panelEl = el.querySelector('.gk-panel');

    this._applyFlags();
    this.setTheme(this.opts.theme);
    this.setBacklight(this.opts.backlight, this.opts.backlightOptions);
    this._bind();
    host.append(el);
    this._build();

    if ('ResizeObserver' in window) {
      // A fragment that fits its host's height as well (fit: 'contain') is laid out again when that changes.
      // A frame later: laying the keys out changes the keyboard's own height, which this same observer watches.
      this._ro = new ResizeObserver(() => {
        cancelAnimationFrame(this._roRaf);
        this._roRaf = requestAnimationFrame(() => {
          if (this._dead) return;
          if (el.clientWidth !== this._W || (this._frag?.fit === 'contain' && host.clientHeight !== this._H)) this._build();
        });
      });
      this._ro.observe(el);
      this._ro.observe(host);
    }
    if ('IntersectionObserver' in window) {
      this._io = new IntersectionObserver((entries) => { this._setVisible(entries[entries.length - 1].isIntersecting); this._sawFirst(); }, { rootMargin: '0px', threshold: 0.15 });
      this._io.observe(el);
    } else this._sawFirst();
    this._onVis = () => { if (!document.hidden) { this._wake(); this._kick(); } };
    document.addEventListener('visibilitychange', this._onVis);
    this._onScheme = () => { if (this.opts.theme === 'auto') this.setTheme('auto'); };
    DARK_MQ?.addEventListener?.('change', this._onScheme);
    this._onMotion = () => { this._lightDirty(); this._kick(); };
    RM?.addEventListener?.('change', this._onMotion);
    BOARDS.add(this);
  }

  // --- events --------------------------------------------------------------------------------------------------------

  /**
   * Listens for 'key', 'down', 'input', 'page', 'shift', 'row', 'settings', 'picker', 'arrow', 'cursor', 'swipe', 'mic',
   * 'user', 'theme', 'layout', 'visible', 'motion' ({paused}: the page's pause went on or off), 'dock' ({side, wide}:
   * the chevron beside docked or one-handed keys was tapped, and the keys went across). Returns the unsubscribe.
   */
  on(type, fn) {
    if (!this._subs.has(type)) this._subs.set(type, new Set());
    this._subs.get(type).add(fn);
    return () => this.off(type, fn);
  }
  off(type, fn) { this._subs.get(type)?.delete(fn); }
  _emit(type, detail = {}) {
    const subs = this._subs.get(type);
    if (subs) for (const fn of [...subs]) { try { fn(detail); } catch (err) { console.error(err); } }
    return detail;
  }

  // --- settings ------------------------------------------------------------------------------------------------------

  get reduced() { return PAUSED || this.opts.motion === 'reduced' || (this.opts.motion !== 'full' && !!RM && RM.matches); }
  _pausedChanged() {
    if (PAUSED) this._loop?.pause();
    else { const r = [...this._resumers]; this._resumers.clear(); r.forEach((fn) => fn()); }
    this._lightDirty();
    this._overlayDirty = true;
    this._kick();
    this._emit('motion', { paused: PAUSED });
  }
  /** The theme in use: KeyboardTheme's fields as 0xRRGGBB numbers. */
  get theme() { return this._theme; }
  /** {width, height, dp, keysTop, keysHeight, rowHeight, keyWidth, split, keys:[{char, label, type, rect, face, cx, cy}]} in CSS px from the keyboard's top left. */
  get geometry() {
    const g = this._geo;
    if (!g) return null;
    return { ...g, keys: this._keys.map((e) => ({ char: e.key.text, label: e.key.label, type: e.key.type, rect: { ...e.rect }, face: { ...e.face }, cx: e.rect.x + e.rect.w / 2, cy: e.rect.y + e.rect.h / 2 })) };
  }

  _applyFlags() {
    const o = this.opts, el = this.el;
    el.classList.toggle('gk--borders', !!o.borders && !this._theme?.mechanical);
    el.classList.toggle('gk--row', !!o.row);
    el.classList.toggle('gk--nohints', !o.hints);
    el.classList.toggle('gk--static', !o.interactive);
    el.classList.toggle('gk--frag', !!this._frag);
    el.classList.toggle('gk--bar-only', this._frag?.kind === 'bar');
    el.classList.toggle('gk--bare', !!o.bare);
    this._cog.hidden = !o.cog;
    this._arrows.hidden = !o.arrows;
    this._keysEl.style.touchAction = o.touchAction;
    // Only the row above the keys: its own buttons take the focus, and there are no keys to type on.
    const keyless = this._frag?.kind === 'bar';
    if (o.interactive && keyless) {
      el.removeAttribute('tabindex');
      el.setAttribute('role', 'group');
      el.removeAttribute('aria-roledescription');
      el.setAttribute('aria-label', o.label);
    } else if (o.interactive) {
      el.tabIndex = 0;
      el.setAttribute('role', 'group');
      el.setAttribute('aria-roledescription', 'on-screen keyboard');
      el.setAttribute('aria-label', o.hardKeys === false ? o.label : `${o.label}. Tap the keys, or focus it and type on your own keyboard. Tab moves on.`);
    } else {
      el.removeAttribute('tabindex');
      el.setAttribute('role', 'img');
      el.setAttribute('aria-label', o.label);
    }
  }

  /**
   * Changes options after the fact: any of the constructor's, e.g. {borders: true, arrows: true, numberRow: true},
   * {keyHeight: 'condensed'}, {wideLayout: 'right'}, {oneHanded: 'left'}.
   */
  set(opts) {
    Object.assign(this.opts, opts);
    if ('fragment' in opts) {
      this._frag = normFragment(opts.fragment);
      if (this._frag && !('row' in opts)) this.opts.row = this._frag.kind === 'bar';
      if (this._frag && this.page !== this._frag.page) { this.page = this._frag.page; this._emit('page', { page: this.page }); }
    }
    this._applyFlags();
    if ('theme' in opts) this.setTheme(opts.theme);
    if ('backlight' in opts) this.setBacklight(opts.backlight, opts.backlightOptions);
    this._build();
    return this;
  }

  /** 'ghost' | 'light' | 'glacier' | 'midnight' | 'paper' | 'retro' | 'auto' | "#bg,#key,#fn,#accent,#text" | {background, key, functionKey, accent, text}. */
  setTheme(spec) {
    this.opts.theme = spec;
    const t = (this._theme = applyTheme(this.el, spec));
    this.el.dataset.theme = typeof spec === 'string' && !spec.includes(',') ? spec : 'custom';
    this.el.classList.toggle('gk--mech', !!t.mechanical);
    this.el.classList.toggle('gk--borders', !!this.opts.borders && !t.mechanical);
    this.el.classList.toggle('gk--light', !t.dark);
    this._lightDirty();
    this._overlayDirty = true;
    this._kick();
    this._emit('theme', { theme: t, id: this.el.dataset.theme });
    return this;
  }

  /**
   * The backlight: an effect id ('reactive' | 'ripple' | 'heatmap' | 'rainbow' | 'starlight' | 'breathe') or null for off.
   * opts: {colour: 'accent' | 'rainbow' | 'ghost' | 'ice' | 'phosphor' | 'warm' | 'violet' | 'rose' | 'white' | '#RRGGBB',
   *        strength: 'low' | 'medium' | 'high' | 0..1, mode: 'brighten' | 'dim', fade: 'fast' | 'medium' | 'slow' | number}.
   */
  setBacklight(effect, opts = {}) {
    const o = { colour: 'accent', strength: 'medium', mode: 'brighten', fade: 'medium', ...(opts || {}) };
    this.opts.backlight = effect || null;
    this._backlight = effect
      ? new Backlight(effect, o.colour, typeof o.strength === 'number' ? o.strength : STRENGTHS[o.strength] ?? 0.6, o.mode, typeof o.fade === 'number' ? o.fade : FADES[o.fade] ?? 1)
      : null;
    this._lightDirty();
    this._kick();
    return this;
  }

  setSound(on) { this.opts.sound = !!on; if (on) unlockAudio(); return this; }

  /** 'letters' | 'symbols' | 'more' | 'dev'. */
  setPage(page) {
    if (!PAGES.includes(page) || page === this.page) return this;
    // A fragment of named keys has no pages to turn: it says which page was asked for, and stays as it is.
    if (this._frag && this._frag.kind !== 'rows' && this._frag.kind !== 'block') { this._emit('page', { page, asked: true }); return this; }
    this.page = page;
    if (page !== 'letters') { this._setShift('off'); this._autoShifted = false; }
    this._build();
    this._emit('page', { page });
    return this;
  }

  /** 'off' | 'on' | 'locked'. */
  setShift(state) { this._autoShifted = false; this._setShift(state); return this; }
  _setShift(state) {
    if (state === this.shift) return;
    this.shift = state;
    this._relabel();
    this._emit('shift', { shift: state });
  }
  /** Auto-capitalisation: turns shift on, or back off if it alone turned it on (KeyboardView.setAutoShift). */
  _autoShift(on) {
    if (this.page !== 'letters' || this.shift === 'locked') return;
    if (on && this.shift === 'off') { this._autoShifted = true; this._setShift('on'); }
    else if (!on && this.shift === 'on' && this._autoShifted) { this._autoShifted = false; this._setShift('off'); }
  }

  /** A dot on the cog, in the accent: an update is waiting. */
  setBadge(on) { this._cog.querySelector('.gk-badge').hidden = !on; return this; }

  /** The dictation panel over the keys: 'listening' | 'transcribing' | null. */
  setDictation(phase) {
    this._dictation = phase || null;
    if (!phase) this._level = 0;
    this._dict.hidden = !phase;
    this._dict.querySelector('.gk-dict-label').textContent = phase === 'listening' ? 'Listening… tap to finish' : phase ? 'Writing it down…' : '';
    this.setLevel(0);
    this._sync();
    return this;
  }
  /** The dictation panel's phase now: 'listening' | 'transcribing' | null. */
  get dictation() { return this._dictation; }
  /** How loud the microphone hears it, 0..1 (eased, as the app eases it): the ring swells with it. */
  setLevel(v) {
    this._level = this._level * 0.6 + clamp(v, 0, 1) * 0.4;
    this._dict.style.setProperty('--gk-level', this._level.toFixed(3));
    return this;
  }

  // --- the text target -----------------------------------------------------------------------------------------------

  /**
   * Typing goes into `el`: a block element (drawn with a visible caret) or a real textarea/input.
   * opts: {text, placeholder, marks: [{re, cls}], caret: false, follow: false, render(field, el, caretEl), focusOnClick: false}.
   */
  attach(el, opts = {}) {
    this.detach();
    this.field = el instanceof GhostField ? el : new GhostField(el, opts);
    this._fieldOff = this.field.on('change', (e) => this._fieldChanged(e));
    const target = this.field.el;
    if (target && !this.field._native && opts.focusOnClick !== false && this.opts.interactive) {
      this._fieldFocus = () => { this._user('field'); this.focus(); };
      target.addEventListener('pointerdown', this._fieldFocus);
    }
    this._fieldChanged({ text: this.field.text, caret: this.field.caret, why: 'attach', field: this.field });
    return this.field;
  }
  detach() {
    this._fieldOff?.();
    if (this._fieldFocus) this.field.el?.removeEventListener('pointerdown', this._fieldFocus);
    this._fieldOff = this._fieldFocus = null;
    this.field = new GhostField();
    this._fieldOff = this.field.on('change', (e) => this._fieldChanged(e));
    return this;
  }
  _fieldChanged(e) {
    if (e.why === 'native') this._user('field');
    if (this.opts.autoCaps) this._autoShift(/(^|[.!?]\s+|\n)$/.test(this.field.before));
    this._emit('input', e);
  }
  focus() { if (this.opts.interactive) this.el.focus({ preventScroll: true }); return this; }

  // --- layout (KeyboardView.layoutKeys) ------------------------------------------------------------------------------

  _rows(page = this.page) {
    const rows = Layouts.page(page, this.opts.numberRow);
    return this.opts.mic ? Layouts.withMic(rows) : rows;
  }

  /**
   * Key height's numbers (KeyboardView.configure, toolbarHeight, labelScale, keyPadY): a row in dp; whether that is
   * Condensed, which also has smaller labels, less padding round a key's face and a slimmer strip above the keys.
   */
  _metrics() {
    const rowDp = KEY_HEIGHTS[this.opts.keyHeight] ?? KEY_HEIGHTS.normal;
    const condensed = rowDp <= KEY_HEIGHTS.condensed;
    return { rowDp, condensed, head: this.opts.row ? (condensed ? 34 : 44) : (condensed ? 8 : 18), padY: condensed ? 4.5 : 6, label: condensed ? 0.87 : 1 };
  }

  /** The whole board's keys, laid out across `W` CSS px. */
  _layoutBoard(W) {
    const o = this.opts;
    const m = this._metrics();
    // A wide screen (an unfolded foldable, a tablet) takes Layout's choice; a phone can be one-handed. See DEFAULTS.
    const asked = isWideLayout(o.wideLayout) ? o.wideLayout : o.split ? 'split' : null;
    const wide = o.scale ? W / o.scale >= SPLIT_MIN : !!asked && W >= SPLIT_MIN;
    const layout = wide ? asked || 'full' : null;
    const dp = o.scale || (wide ? 1 : clamp(W / 412, 0.62, 1.25));
    const keysTop = m.head * dp;
    const keysHeight = (o.numberRow ? 5 : 4) * m.rowDp * dp;
    const height = Math.ceil(keysTop + keysHeight + 2 * dp);
    const rows = this._rows();
    const units = Math.max(...rows.map((r) => r.units));
    const sidePad = 1.5 * dp;
    // Split: each half keeps a phone's key size and the middle is left open for the thumbs. Docked: a phone's
    // keyboard on the big screen, under one thumb or in the middle. One-handed: the keys narrowed toward a thumb.
    let gap = 0, areaW = W, areaLeft = 0;
    if (layout === 'split') gap = W * SPLIT_GAP;
    else if (layout === 'left' || layout === 'center' || layout === 'right') {
      areaW = Math.min(W, COMPACT_DP * dp);
      areaLeft = layout === 'left' ? 0 : layout === 'right' ? W - areaW : (W - areaW) / 2;
    } else if (!wide && (o.oneHanded === 'left' || o.oneHanded === 'right')) {
      areaW = W * ONE_HANDED_SHARE;
      areaLeft = o.oneHanded === 'right' ? W - areaW : 0;
    }
    const areaRight = areaLeft + areaW;
    // The empty side of docked or one-handed keys (KeyboardView.dockFlip): where they go when it is tapped. None
    // when the keys fill the width or stand in the middle.
    const dock = areaW >= W - 1 ? null : areaLeft <= 0.5 ? { side: 'right', from: areaRight, to: W } : areaRight >= W - 0.5 ? { side: 'left', from: 0, to: areaLeft } : null;
    const unit = (areaW - sidePad * 2 - gap) / units;
    const mid = areaLeft + sidePad + (units * unit) / 2;
    const rowH = keysHeight / rows.length;
    const keys = [];
    let y = keysTop;
    for (const r of rows) {
      let x = areaLeft + sidePad + r.inset * unit + ((units - r.units) * unit) / 2;
      for (const key of r.keys) {
        const w = key.width * unit, left = x, right = x + w;
        const add = (l, rgt) => keys.push({ key, rect: { x: l, y, w: rgt - l, h: rowH } });
        if (!gap) add(left, right);
        else if (key.width >= 2 && left < mid && right > mid) { add(left, mid); add(mid + gap, right + gap); }
        else if ((left + right) / 2 <= mid + 0.01) add(left, right);
        else add(left + gap, right + gap);
        x += w;
      }
      y += rowH;
    }
    return { dp, keysTop, keysHeight, height, rowH, unit, split: layout === 'split', keys, layout, oneHanded: dock && !wide ? o.oneHanded : null, area: { left: areaLeft, right: areaRight }, dock };
  }

  /** One key of a fragment by name: a character, a label ('( )', '?123'), 'space', 'enter', 'delete', 'shift', 'mic', 'tab'. */
  _namedKey(n) {
    if (n && typeof n === 'object') {
      if (n.key != null) { const { key, ...over } = n; const base = this._namedKey(key); return K(base.type, { ...base, ...over }); }
      return K(n.type || 'char', n);
    }
    const name = String(n);
    const order = [this._frag.page, ...PAGES.filter((p) => p !== this._frag.page)];
    const all = (p) => Layouts.page(p, this.opts.numberRow).flatMap((r) => r.keys);
    if (name === 'mic') return K('mic');
    const type = KEY_TYPES[name];
    const lower = isLetter(name) ? name.toLowerCase() : name;
    for (const p of order) {
      const hit = all(p).find((k) => (type ? k.type === type : name === 'tab' ? k.text === '\t'
        : (k.type === 'char' || k.type === 'mode') && (k.label === name || k.text === name || k.text === lower)));
      if (hit) return hit;
    }
    return K('char', { text: name });
  }

  /** A fragment's keys in key units from its own left edge: {rows: [[{key, x, w}]], units}. */
  _fragRows() {
    const f = this._frag;
    if (f.kind === 'bar') return { rows: [], units: 0 };
    if (f.kind === 'keys') {
      const lists = f.keys.some(Array.isArray) ? f.keys.map((r) => [].concat(r)) : [f.keys];
      const rows = lists.map((names, i) => {
        let x = f.insets?.[i] || 0;
        const keys = [];
        for (const n of names) {
          if (n && typeof n === 'object' && 'gap' in n) { x += +n.gap || 0; continue; }
          const key = this._namedKey(n);
          keys.push({ key, x, w: key.width });
          x += key.width;
        }
        return { keys, w: x };
      });
      const units = Math.max(0, ...rows.map((r) => r.w));
      // Rows narrower than the widest are centred under it, as the board centres its own: a real stagger.
      if (f.align !== 'start' && !f.insets) for (const r of rows) { const off = (units - r.w) / (f.align === 'end' ? 1 : 2); for (const k of r.keys) k.x += off; }
      return { rows: rows.map((r) => r.keys), units };
    }
    // Whole rows, or a block, of the page showing, each key where the board has it.
    const page = this._rows();
    const U = Math.max(...page.map((r) => r.units));
    const placed = page.map((r) => { let x = r.inset + (U - r.units) / 2; return r.keys.map((key) => { const k = { key, x, w: key.width }; x += key.width; return k; }); });
    if (f.kind === 'rows') return { rows: f.rows.map((i) => placed[i < 0 ? placed.length + i : i]).filter(Boolean), units: U };
    const b = f.block || {};
    let r0 = b.row ?? 0, r1 = r0 + (b.rows ?? 1) - 1, x0 = b.col ?? 0, x1 = x0 + (b.cols ?? 1);
    if (b.from != null && b.to != null) {
      const where = (name) => {
        const want = this._namedKey(name);
        for (let i = 0; i < placed.length; i++) { const k = placed[i].find((c) => c.key.type === want.type && c.key.text === want.text && c.key.label === want.label); if (k) return { i, k }; }
        return null;
      };
      const A = where(b.from), Z = where(b.to);
      if (A && Z) { r0 = Math.min(A.i, Z.i); r1 = Math.max(A.i, Z.i); x0 = Math.min(A.k.x, Z.k.x); x1 = Math.max(A.k.x + A.k.w, Z.k.x + Z.k.w); }
    }
    const rows = placed.slice(Math.max(0, r0), r1 + 1).map((r) => r.filter((k) => { const c = k.x + k.w / 2; return c > x0 + 1e-6 && c < x1 - 1e-6; }));
    const flat = rows.flat();
    if (!flat.length) return { rows: [], units: 0 };
    const minX = Math.min(...flat.map((k) => k.x)), maxX = Math.max(...flat.map((k) => k.x + k.w));
    for (const k of flat) k.x -= minX;
    return { rows, units: maxX - minX };
  }

  /** A fragment laid out across `W` CSS px: its keys keep a phone key's proportions, as large as the host lets them be. */
  _layoutFrag(W) {
    const o = this.opts, f = this._frag;
    const { rows, units } = this._fragRows();
    const n = rows.length;
    const m = this._metrics();
    const rowDp = m.rowDp;
    // Room above the first row: the row above the keys, or what a key's preview bubble (or long-press panel) needs.
    const head = o.row ? m.head : HEADROOM[f.headroom] ?? (+f.headroom || 0);
    const foot = n ? 2 : 0;
    const totalDp = head + n * rowDp + foot;
    const designW = +f.width || (n ? units * UNIT_DP + 3 : 360);
    let dp = o.scale || clamp(W / designW, f.minDp ?? 0.5, f.maxDp ?? (n ? 1.6 : 1.25));
    if (!o.scale && f.fit === 'contain') {
      const H = (this._H = this.host.clientHeight);
      if (H > 0 && totalDp > 0) dp = Math.min(dp, Math.max(0.35, H / totalDp));
    }
    const sidePad = n ? 1.5 * dp : 0;
    const unit = n ? Math.min(UNIT_DP * dp, (W - sidePad * 2) / units) : 0;
    const used = units * unit;
    const offX = f.align === 'start' ? sidePad : f.align === 'end' ? W - used - sidePad : (W - used) / 2;
    const keysTop = head * dp, rowH = rowDp * dp, keysHeight = n * rowH;
    const keys = [];
    rows.forEach((r, i) => { for (const k of r) keys.push({ key: k.key, rect: { x: offX + k.x * unit, y: keysTop + i * rowH, w: k.w * unit, h: rowH } }); });
    return { dp, keysTop, keysHeight, height: Math.ceil(keysTop + keysHeight + foot * dp - 0.01), rowH: n ? rowH : 0, unit, split: false, keys, layout: null, oneHanded: null, area: { left: 0, right: W }, dock: null };
  }

  _build() {
    const el = this.el;
    const W = el.clientWidth;
    if (!W) return;
    this._W = W;
    this._cancelTouch();
    this._releaseGhost();
    const { dp, keysTop, keysHeight, height, rowH, unit, split, keys, layout, oneHanded, area, dock } = this._frag ? this._layoutFrag(W) : this._layoutBoard(W);
    const m = this._metrics();
    const padX = 3 * dp, padY = m.padY * dp;
    // layout: the wide screen's ('split' | 'full' | 'left' | 'center' | 'right'), null on a phone. area: where the keys
    // stand across the board. dock: the empty side of docked or one-handed keys ('left' | 'right'), or null.
    this._geo = { width: W, height, dp, keysTop, keysHeight, rowHeight: rowH, keyWidth: unit, split, layout, oneHanded, area, dock: dock ? dock.side : null, condensed: m.condensed, labelScale: m.label };

    el.classList.toggle('gk--condensed', m.condensed);
    // The chevron on the empty side, as wide as that side less the slack a key's own touch keeps there.
    const slack = DOCK_SLACK_DP * dp, d = this._dock;
    d.hidden = !dock;
    if (dock) {
      const from = dock.side === 'right' ? dock.from + slack : dock.from, to = dock.side === 'right' ? dock.to : dock.to - slack;
      d.dataset.side = dock.side;
      d.style.cssText = `left:${from.toFixed(2)}px;width:${Math.max(0, to - from).toFixed(2)}px;top:${keysTop.toFixed(2)}px;height:${keysHeight.toFixed(2)}px;--kb-dock-nudge:${(dock.side === 'right' ? -slack / 2 : slack / 2).toFixed(2)}px`;
      d.setAttribute('aria-label', `Move the keys to the ${dock.side}`);
    }
    el.style.setProperty('--kb-dp', dp + 'px');
    el.style.setProperty('--kb-shrink', (Math.max(0, rowH - padY * 2) * 0.13).toFixed(2) + 'px');
    el.style.height = height + 'px';
    el.classList.toggle('gk--short', keysHeight < 130 * dp);
    this._keysEl.style.top = this._dict.style.top = keysTop + 'px';

    const frag = document.createDocumentFragment();
    for (const e of keys) {
      const { key, rect } = e;
      e.face = { x: rect.x + padX, y: rect.y + padY, w: rect.w - padX * 2, h: rect.h - padY * 2 };
      const b = (e.el = document.createElement('button'));
      b.type = 'button';
      b.tabIndex = -1;
      b.className = `gk-key gk-k-${key.type}` + (key.functional ? ' gk-fn' : '') +
        (key.type === 'enter' || key.type === 'space' || (key.type === 'mode' && key.pill) ? ' gk-pill' : '');
      b.style.cssText = `left:${rect.x.toFixed(2)}px;top:${(rect.y - keysTop).toFixed(2)}px;width:${rect.w.toFixed(2)}px;height:${rect.h.toFixed(2)}px`;
      b.innerHTML = `<span class="gk-face"><span class="gk-cap"><span class="gk-legend"></span>${key.hint ? `<span class="gk-hint">${esc(key.hint)}</span>` : ''}</span></span>`;
      e.legend = b.querySelector('.gk-legend');
      e.down = false;
      e.lit = '';
      frag.append(b);
    }
    this._keys = keys;
    this._keysEl.replaceChildren(frag);
    this._relabel();

    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    this._canvas.width = Math.round(W * dpr);
    this._canvas.height = Math.round(height * dpr);
    this._dpr = dpr;
    this._renderRow(true);
    this._lightDirty();
    this._overlayDirty = true;
    this._sync();
    this._kick();
    this._emit('layout', this._geo);
  }

  /** What a character key shows: its label where it has its own (the developer page), else what it types. */
  _shown(key) { return key.label !== key.text ? key.label : this._display(key.text); }
  _display(text) { return this.page === 'letters' && this.shift !== 'off' ? text.toUpperCase() : text; }

  _fit(text, px, weight, maxW, minPx) {
    if (!measureCtx) measureCtx = document.createElement('canvas').getContext('2d');
    if (!measureCtx) return px;
    const family = this._family || (this._family = getComputedStyle(this.el).fontFamily || 'sans-serif');
    const step = this._geo.dp;
    for (;;) {
      measureCtx.font = `${weight} ${px}px ${family}`;
      if (px <= minPx || measureCtx.measureText(text).width <= maxW) return px;
      px -= step;
    }
  }

  /** Writes every key's legend: letters in the shift's case, icons, and the screen reader's name for it. */
  _relabel() {
    const g = this._geo;
    if (!g) return;
    const dp = g.dp;
    for (const e of this._keys) {
      const { key, el, legend } = e;
      let label = ARIA[key.type] || key.label;
      if (key.type === 'char') {
        const text = this._shown(key);
        const isL = this.page === 'letters' && isLetter(text);
        const px = this._fit(text, (isL ? 23 : chars(text).length > 2 ? 17 : 21) * dp * g.labelScale, 400, e.face.w - 4 * dp, 9 * dp);
        legend.style.fontSize = px.toFixed(2) + 'px';
        legend.textContent = text;
        label = text === '⇥' ? 'tab' : text;
      } else if (key.type === 'mode') {
        legend.textContent = key.label;
        label = `Switch to ${PAGE_NAMES[key.page] || 'letters'}`;
      } else if (key.type === 'shift') {
        legend.innerHTML = icon('shift', { filled: this.shift !== 'off' });
        el.classList.toggle('is-on', this.shift !== 'off');
        el.classList.toggle('is-locked', this.shift === 'locked');
        el.setAttribute('aria-pressed', String(this.shift !== 'off'));
      } else if (key.type === 'delete' || key.type === 'enter' || key.type === 'mic') {
        if (!legend.firstChild) legend.innerHTML = icon(key.type);
      }
      el.setAttribute('aria-label', label);
    }
  }

  // --- the row above the keys (PluginRow.kt) -------------------------------------------------------------------------

  /**
   * The row's items, in order: {id, label?, icon?, bold?, wide?, chip?, fill?, view?, disabled?, onTap?}.
   * `wide` is a suggestion (a third of the row each); `chip` a rounded chip; `fill` takes the width the others leave;
   * `icon` one of ICON_NAMES or your own inline SVG; `view` an element of your own shown as it is.
   */
  setRow(items = []) {
    this._rowItems = items || [];
    this._renderRow(false);
    return this;
  }
  /** Suggestions as the app lays them out: three across, the pick in bold and in the middle. */
  setSuggestions(words = [], onPick = null, pick = 0) {
    let items = words.map((w, i) => ({ id: `s-${i}-${w}`, label: w, wide: true, bold: i === pick, onTap: onPick ? () => onPick(w, i) : undefined }));
    if (items.length >= 3 && pick === 0) items = [items[1], items[0], ...items.slice(2)];
    return this.setRow(items);
  }
  _renderRow(force) {
    const items = this._rowItems;
    const sig = items.map((i) => [i.id, i.label, !!i.bold, !!i.view].join('\u0001')).join('\u0002');
    // The same words again keeps the row as it is, scroll and all.
    if (!force && sig === this._rowSig) return;
    this._rowSig = sig;
    const g = this._geo;
    const strip = this._strip;
    strip.replaceChildren();
    if (!g || !this.opts.row) return;
    const dp = g.dp;
    const room = strip.clientWidth || g.width - 44 * dp - (this.opts.arrows ? 144 * dp : 0);
    const wideCount = Math.max(3, items.filter((i) => i.wide).length);
    const wideWidth = room / wideCount < 80 * dp ? room / 3 : room / wideCount;
    for (const item of items) {
      let v;
      if (item.view) { v = item.view; v.classList.add('gk-view'); }
      else {
        const iconOnly = item.icon && item.label == null;
        v = document.createElement(item.disabled ? 'span' : 'button');
        if (!item.disabled) v.type = 'button';
        const art = item.icon ? (String(item.icon).trim().startsWith('<') ? item.icon : icon(item.icon)) : '';
        if (iconOnly) {
          v.className = 'gk-ib';
          v.innerHTML = art;
          v.setAttribute('aria-label', item.ariaLabel || item.id);
        } else {
          v.className = 'gk-ri' + (item.chip ? ' gk-chip' : '') + (item.wide ? ' gk-wide' : '') + (item.bold ? ' gk-bold' : '') + (item.fill ? ' gk-fill' : '');
          v.innerHTML = `${art}<span class="gk-ri-t">${esc(item.label ?? '')}</span>`;
          if (item.ariaLabel) v.setAttribute('aria-label', item.ariaLabel);
          if (item.wide) v.style.flex = `0 0 ${wideWidth.toFixed(2)}px`;
        }
        if (!item.disabled) {
          if (!this.opts.interactive) v.tabIndex = -1;
          v.addEventListener('click', () => {
            unlockIf(this);
            this._user('row');
            this._emit('row', { id: item.id, item });
            try { item.onTap?.(item); } catch (err) { console.error(err); }
          });
        }
      }
      v.dataset.id = item.id;
      strip.append(v);
    }
    strip.scrollLeft = 0;
    this._stripEdge();
  }
  /** A row with more past its right edge fades there (kbd.css .gk-strip.is-cut), since its scrollbar is not drawn. */
  _stripEdge() {
    const strip = this._strip;
    strip.classList.toggle('is-cut', strip.scrollWidth - strip.clientWidth - strip.scrollLeft > 1);
  }

  // --- finding keys --------------------------------------------------------------------------------------------------

  /** The key that types `c` among `keys` ([{key}]): itself, or (with alts) the key whose long-press holds it. */
  _find(c, alts = true, keys = this._keys) {
    const t = typeOfChar(c);
    if (t !== 'char') { const e = keys.find((k) => k.key.type === t); return e ? { e, alt: -1 } : null; }
    const folded = (chars(fold(c.toLowerCase()))[0] || c).toLowerCase();
    const direct = keys.find((k) => k.key.type === 'char' && (k.key.text === c || k.key.text.toLowerCase() === folded));
    if (direct) return { e: direct, alt: -1 };
    if (!alts) return null;
    for (const e of keys) {
      if (e.key.type !== 'char') continue;
      let i = e.key.alts.indexOf(c);
      if (i < 0 && isLetter(c)) i = e.key.alts.indexOf(c.toLowerCase());
      if (i >= 0) return { e, alt: i };
    }
    return null;
  }

  /**
   * Where `char`'s key is now, in CSS px from the keyboard's top left: {x, y, w, h, cx, cy, face}. Also takes
   * 'space', 'enter', 'delete', 'shift', 'mic'. Null when the page showing has no such key.
   */
  keyRect(char) {
    const named = ['space', 'enter', 'delete', 'shift', 'mic', 'mode'].includes(char);
    const e = named ? this._keys.find((k) => k.key.type === char) : this._find(String(char), false)?.e;
    if (!e) return null;
    const r = e.rect;
    return { x: r.x, y: r.y, w: r.w, h: r.h, cx: r.x + r.w / 2, cy: r.y + r.h / 2, face: { ...e.face } };
  }

  /** The key a touch at (x, y) means: {char, label, type, rect}. The adaptive aims, when set, are used as the app uses them. */
  keyAt(x, y) {
    const e = this._keyAt(x, y);
    return e ? { char: e.key.text, label: e.key.label, type: e.key.type, rect: { ...e.rect } } : null;
  }
  _keyAt(x, y) {
    const g = this._geo;
    if (!g || y < g.keysTop) return null;
    // Docked or one-handed: past the keys' own edge and its slack the touch is the empty side's, not the nearest key's.
    const slack = DOCK_SLACK_DP * g.dp;
    if (x < g.area.left - slack || x > g.area.right + slack) return null;
    const hit = this._keys.find((e) => x >= e.rect.x && x < e.rect.x + e.rect.w && y >= e.rect.y && y < e.rect.y + e.rect.h);
    if (hit) return this._adapt(hit, x, y);
    // Below the last row or past its ends: the nearest key, so the edges never swallow a touch.
    let best = null, bestD = Infinity;
    for (const e of this._keys) {
      const r = e.rect;
      const dx = x < r.x ? r.x - x : x > r.x + r.w ? x - r.x - r.w : 0;
      const dy = y < r.y ? r.y - y : y > r.y + r.h ? y - r.y - r.h : 0;
      if (dx * dx + dy * dy < bestD) { bestD = dx * dx + dy * dy; best = e; }
    }
    // A fragment has room round its keys that is not keyboard: only a touch near a key is that key's.
    if (this._frag && bestD > (g.keyWidth * 0.5) ** 2) return null;
    return best;
  }
  _isLetterKey(key) { return key.type === 'char' && isLetter(key.text); }
  _aim(c) {
    const m = this._adaptive;
    if (!m) return [0, 0];
    const o = typeof m.offset === 'function' ? m.offset(c) : m.offsets?.[c];
    return o || [0, 0];
  }
  /** Of the letter under the touch and its neighbours, the one whose learned aim is nearest (KeyboardView.adapt). */
  _adapt(hit, x, y) {
    if (!this._adaptive || this.page !== 'letters' || !this._isLetterKey(hit.key)) return hit;
    const hx = hit.rect.x + hit.rect.w / 2, hy = hit.rect.y + hit.rect.h / 2;
    let best = hit, bestC = Infinity;
    for (const e of this._keys) {
      if (!this._isLetterKey(e.key)) continue;
      const r = e.rect, cx0 = r.x + r.w / 2, cy0 = r.y + r.h / 2;
      if (Math.abs(cx0 - hx) >= hit.rect.w * 1.6 || Math.abs(cy0 - hy) >= hit.rect.h * 1.6) continue;
      const [ox, oy] = this._aim(e.key.text);
      const dx = (x - (cx0 + ox * r.w)) / r.w, dy = (y - (cy0 + oy * r.h)) / r.h;
      const cost = dx * dx + dy * dy;
      if (cost < bestC) { bestC = cost; best = e; }
    }
    return best;
  }

  // --- what a key does -----------------------------------------------------------------------------------------------

  _keyEvent(type, char, e, x, y, source, extra = {}) {
    const rect = e ? { ...e.rect } : null;
    const ev = {
      type, char, key: e ? e.key : null, rect, face: e ? { ...e.face } : null, source,
      x: x ?? (rect ? rect.x + rect.w / 2 : null), y: y ?? (rect ? rect.y + rect.h / 2 : null),
      defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, ...extra,
    };
    this._emit('key', ev);
    return ev;
  }

  /** Puts a typed thing into the field unless a 'key' listener prevented it. */
  _apply(type, char, e, x, y, source, extra = {}) {
    const ev = this._keyEvent(type, char, e, x, y, source, extra);
    if (ev.defaultPrevented) return ev;
    const f = this.field;
    if (type === 'char') f.insert(char, extra.caretBack || 0);
    else if (type === 'space') {
      // Two spaces after a word make a full stop, as the app's "double-space period" does (a person's typing only).
      if (this.opts.doubleSpacePeriod && source !== 'script' && source !== 'ghost' && /[\p{L}\p{N})\]"'] $/u.test(f.before)) { f.backspace(1); f.insert('. '); }
      else f.insert(' ');
    } else if (type === 'delete') f.backspace(1);
    else if (type === 'enter' && this.opts.enter === 'newline') f.insert('\n');
    return ev;
  }

  /** A key's text as it is typed: the caret mark taken out, the caret left there. */
  _commit(raw, e, x, y, source, extra = {}) {
    if (this.shift === 'on') { this._autoShifted = false; this._setShift('off'); }
    const caret = raw.indexOf('¦');
    const text = caret < 0 ? raw : raw.slice(0, caret) + raw.slice(caret + 1);
    return this._apply('char', text, e, x, y, source, { ...extra, caretBack: caret < 0 ? 0 : raw.length - 1 - caret });
  }

  _tapShift() {
    const t = Date.now();
    // A second tap soon after the first locks caps.
    const next = this.shift === 'off' ? 'on' : this.shift === 'on' ? (!this._autoShifted && t - this._lastShiftTap < 400 ? 'locked' : 'off') : 'off';
    this._autoShifted = false;
    this._lastShiftTap = t;
    this._setShift(next);
  }

  _feedback(key) { if (this.opts.sound) thock(key.type, this.opts.volume); }

  // --- pointer, as KeyboardView.onTouchEvent -------------------------------------------------------------------------

  _point(ev) {
    const r = this.el.getBoundingClientRect();
    const sx = r.width / (this.el.offsetWidth || r.width || 1), sy = r.height / (this.el.offsetHeight || r.height || 1);
    return [(ev.clientX - r.left) / sx, (ev.clientY - r.top) / sy];
  }

  _bind() {
    const el = this.el, keys = this._keysEl;
    const on = (t, type, fn, o) => t.addEventListener(type, fn, o);
    on(keys, 'mousedown', (ev) => ev.preventDefault());
    on(keys, 'contextmenu', (ev) => ev.preventDefault());
    on(keys, 'pointerdown', (ev) => {
      if (!this.opts.interactive || (ev.pointerType === 'mouse' && ev.button !== 0)) return;
      unlockIf(this);
      // A finger that comes down on the keys may be scrolling the page (the browser then cancels the touch): it takes
      // the focus and stops the idle loop only once it has turned out to be a tap, a long press or a swipe (_claim).
      const finger = ev.pointerType === 'touch';
      if (!finger) this.focus();
      // Rolling typing: a second finger down finishes the first finger's key.
      if (this._touch) { if (this._touch.panel) return; this._release(this._touch); }
      const [x, y] = this._point(ev);
      const e = this._keyAt(x, y);
      if (!e) return;
      if (!finger) this._user('pointer');
      try { keys.setPointerCapture(ev.pointerId); } catch { /* a synthetic pointer */ }
      this._press({ id: ev.pointerId, key: e.key, e, rect: e.rect, first: e.key, downX: x, downY: y, lastX: x, lastY: y, anchor: x, panel: null, longPressed: false, cursorMoved: false, swipe: null, unclaimed: finger });
    });
    on(keys, 'pointermove', (ev) => {
      const t = this._touch;
      if (!t || t.id !== ev.pointerId) return;
      const [x, y] = this._point(ev);
      if (t.swipe && ev.getCoalescedEvents) for (const c of ev.getCoalescedEvents()) { const p = this._point(c); this._swipePoint(t.swipe, p[0], p[1]); }
      this._move(t, x, y);
    });
    on(keys, 'pointerup', (ev) => { const t = this._touch; if (t && t.id === ev.pointerId) this._release(t); });
    on(keys, 'pointercancel', (ev) => { const t = this._touch; if (t && t.id === ev.pointerId) this._cancelTouch(); });
    // A screen reader's or a switch's "click" comes with no pointer: it is a tap on that key.
    on(keys, 'click', (ev) => {
      if (ev.detail !== 0 || !this.opts.interactive) return;
      const e = this._keys.find((k) => k.el === ev.target.closest('.gk-key'));
      if (e) { this._user('a11y'); this._tap(e, 'a11y'); }
    });
    on(this._cog, 'click', () => { this._user('cog'); this._emit('settings', {}); });
    // The empty side of docked or one-handed keys: a tap there moves them across (KeyboardView's onDock).
    on(this._dock, 'click', () => {
      const g = this._geo, side = this._dock.dataset.side;
      if (!this.opts.interactive || !g?.dock) return;
      const wide = !!g.layout;
      this._user('dock');
      this.set(wide ? { wideLayout: side } : { oneHanded: side });
      this._emit('dock', { side, wide });
    });
    // The row scrolls sideways with no scrollbar: an item that takes the focus is brought whole into view.
    on(this._strip, 'focusin', (ev) => {
      const strip = this._strip, v = ev.target.closest?.('.gk-strip > *');
      if (!v) return;
      const s = strip.getBoundingClientRect(), r = v.getBoundingClientRect();
      if (r.right > s.right) strip.scrollLeft += r.right - s.right + 6; else if (r.left < s.left) strip.scrollLeft -= s.left - r.left + 6;
    });
    on(this._strip, 'scroll', () => this._stripEdge(), { passive: true });
    for (const b of this._arrows.querySelectorAll('.gk-arrow')) {
      // On touch, then again and again while held: 400 ms before the first repeat, then every 50 ms.
      let first = 0, again = 0;
      const stop = () => { clearTimeout(first); clearInterval(again); };
      on(b, 'pointerdown', (ev) => {
        if (!this.opts.interactive) return;
        ev.preventDefault();
        unlockIf(this);
        stop();
        this._user('arrow');
        this._arrow(b.dataset.dir);
        first = setTimeout(() => { again = setInterval(() => this._arrow(b.dataset.dir), 50); }, 400);
      });
      for (const type of ['pointerup', 'pointercancel', 'pointerleave']) on(b, type, stop);
      on(b, 'click', (ev) => { if (ev.detail === 0 && this.opts.interactive) { this._user('arrow'); this._arrow(b.dataset.dir); } });
    }
    on(this._dict, 'click', () => { if (this._dictation === 'listening') { this._user('mic'); this._emit('mic', {}); } });
    on(el, 'keydown', (ev) => this._hardKey(ev));
  }

  _arrow(dir) {
    const ev = { dir, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    this._emit('arrow', ev);
    if (ev.defaultPrevented) return;
    if (dir === 'left') this.field.move(-1); else if (dir === 'right') this.field.move(1); else this.field.moveLine(dir === 'up' ? -1 : 1);
  }

  /** A finger's touch has turned out to be the visitor's own (not a scroll): the focus, and the idle loop makes way. */
  _claim(t) {
    if (!t.unclaimed) return;
    t.unclaimed = false;
    this.focus();
    this._user('pointer');
  }

  _press(t) {
    this._touch = t;
    this._feedback(t.key);
    if (!this.reduced) this._backlight?.press(lightId(t.key), t.rect.x + t.rect.w / 2, t.rect.y + t.rect.h / 2, clock());
    this._emit('down', { key: t.key, type: t.key.type, char: t.key.text, x: t.downX, y: t.downY, rect: { ...t.rect }, source: 'pointer' });
    const type = t.key.type;
    if (type === 'delete') {
      this._apply('delete', '\b', t.e, t.downX, t.downY, 'pointer');
      t.repeat = setTimeout(() => { t.repeat = setInterval(() => { this._feedback(t.key); this._apply('delete', '\b', t.e, t.downX, t.downY, 'pointer', { repeat: true }); }, 50); }, 400);
    } else if (type === 'char' || type === 'space' || type === 'shift' || type === 'mic' || (type === 'mode' && t.key.label === '{ }')) {
      t.long = setTimeout(() => this._longPress(t), this.opts.longPressMs);
    }
    this._sync();
    this._kick();
  }

  _move(t, x, y) {
    t.lastX = x; t.lastY = y;
    if (t.panel) { this._selectInPanel(t.panel, x, y); return; }
    const dp = this._geo.dp;
    if (t.key.type === 'space') {
      if (!this.opts.spaceCursor) return;
      // Slide along the space bar to move the cursor a character at a time.
      const step = 12 * dp, dx = x - t.anchor;
      if (Math.abs(dx) >= step && (t.cursorMoved || Math.abs(x - t.downX) > 16 * dp)) {
        if (!t.cursorMoved) { t.cursorMoved = true; clearTimeout(t.long); this._claim(t); }
        const steps = Math.trunc(dx / step);
        t.anchor += steps * step;
        this.field.move(steps);
        this._emit('cursor', { steps });
      }
      return;
    }
    if (t.key.type !== 'char') return;
    if (t.swipe) { this._swipePoint(t.swipe, x, y); this._overlayDirty = true; this._kick(); return; }
    // Swipe typing: a slide of most of a key's width from a letter starts spelling a word.
    if (this.opts.swipe && this.page === 'letters' && this._isLetterKey(t.first) && Math.hypot(x - t.downX, y - t.downY) > t.rect.w * 0.9) {
      this._claim(t);
      t.swipe = [[t.downX, t.downY], [x, y]];
      clearTimeout(t.long);
      this._trail = t.swipe;
      this._fading = null;
      this._overlayDirty = true;
      this._sync();
      this._kick();
      return;
    }
    // A finger that clearly slides onto another character key types that one instead (40% of a key past its edge).
    const r = t.rect, slackX = r.w * 0.4, slackY = r.h * 0.4;
    if (x >= r.x - slackX && x <= r.x + r.w + slackX && y >= r.y - slackY && y <= r.y + r.h + slackY) return;
    const e = this._keyAt(x, y);
    if (e && e.key !== t.key && e.key.type === 'char') {
      t.key = e.key; t.e = e; t.rect = e.rect;
      clearTimeout(t.long);
      t.long = setTimeout(() => this._longPress(t), this.opts.longPressMs);
      this._sync();
    }
  }

  _swipePoint(path, x, y) {
    const last = path[path.length - 1];
    if (last && Math.hypot(x - last[0], y - last[1]) < 1.5 * this._geo.dp) return;
    path.push([x, y]);
  }

  _longPress(t) {
    if (this._touch !== t) return;
    this._claim(t);
    const type = t.key.type;
    if (type === 'space') { t.longPressed = true; this._emit('picker', {}); }
    else if (type === 'mode') { t.longPressed = true; this._emit('settings', {}); }
    else if (type === 'shift') { t.longPressed = true; this._autoShifted = false; this._setShift('locked'); }
    else if (type === 'char') {
      // The comma, where it is the developer page's way back, opens settings as Gboard's comma did.
      if (t.key.text === ',') { t.longPressed = true; this._emit('settings', {}); return; }
      const panel = this._makePanel(t.key, t.rect);
      if (!panel) return;
      t.panel = panel;
      t.longPressed = true;
    } else if (type === 'mic') {
      // Where the full stop was: its long-press is the full stop's, the full stop first.
      const panel = this._makePanel(t.key, t.rect);
      if (!panel) return;
      t.panel = panel;
      t.longPressed = true;
    }
    this._sync();
  }

  _release(t) {
    clearTimeout(t.long); clearTimeout(t.repeat); clearInterval(t.repeat);
    this._claim(t);
    this._touch = null;
    const { key, e } = t;
    if (t.swipe) {
      const path = t.swipe;
      this._trail = null;
      this._fading = { path, since: clock() };
      this._overlayDirty = true;
      if (this.shift === 'on') { this._autoShifted = false; this._setShift('off'); }
      this._emit('swipe', { path: path.map((p) => ({ x: p[0], y: p[1] })), letters: this._lettersAlong(path), keyWidth: this._geo.keyWidth, source: 'pointer' });
    } else if (t.panel) this._commit(t.panel.choices[t.panel.selected], e, t.downX, t.downY, 'pointer', { alt: true });
    else if (!t.longPressed && !(key.type === 'space' && t.cursorMoved)) this._act(key, e, t.downX, t.downY, 'pointer');
    this._sync();
    this._kick();
  }

  /** A tap's effect, by the key's kind (KeyboardView.release). */
  _act(key, e, x, y, source) {
    switch (key.type) {
      case 'char':
        this._commit(this._display(key.text), e, x, y, source);
        // A character key that names a page goes there after typing: the developer page's comma.
        if (key.page) this.setPage(key.page);
        break;
      case 'space': this._apply('space', ' ', e, x, y, source); break;
      case 'enter': this._apply('enter', '\n', e, x, y, source); break;
      case 'shift': this._tapShift(); this._keyEvent('shift', '', e, x, y, source); break;
      case 'mode': this._keyEvent('mode', key.label, e, x, y, source); this.setPage(key.page || 'letters'); break;
      case 'mic': this._keyEvent('mic', '', e, x, y, source); this._emit('mic', {}); break;
      case 'delete': if (source !== 'pointer') this._apply('delete', '\b', e, x, y, source); break;
    }
  }

  /** A whole tap with no finger: an assistive click, or tapAt(). */
  _tap(e, source, x, y, hold = 90) {
    this._feedback(e.key);
    this._ghostPress(e, -1, hold, this._backlight);
    this._act(e.key, e, x, y, source);
  }

  _cancelTouch() {
    const t = this._touch;
    if (!t) return;
    clearTimeout(t.long); clearTimeout(t.repeat); clearInterval(t.repeat);
    this._touch = null;
    if (t.swipe) { this._trail = null; this._overlayDirty = true; }
    this._sync();
  }

  // --- a hardware keyboard, while the keyboard has focus -------------------------------------------------------------

  _hardKey(ev) {
    if (!this.opts.interactive || this.opts.hardKeys === false || ev.target !== this.el) return;   // a focused row button keeps its own Enter and Space
    if (ev.ctrlKey || ev.metaKey || ev.altKey || ev.isComposing) return;
    const k = ev.key;
    if (k === 'Tab') return;                                           // never a focus trap
    if (k === 'Escape') { this.el.blur(); return; }
    if (k === 'ArrowLeft' || k === 'ArrowRight') { ev.preventDefault(); this._arrow(k === 'ArrowLeft' ? 'left' : 'right'); return; }
    const c = k === 'Backspace' ? '\b' : k === 'Enter' ? '\n' : chars(k).length === 1 ? k : null;
    if (c == null) return;
    // Backspace is never left to the browser here (some still go back a page on it), delete key or no delete key.
    if (k === 'Backspace') ev.preventDefault();
    const m = this._find(c, false);
    // hardKeys: 'shown' answers only to the keys this keyboard (or fragment) has; the rest are the page's.
    if (!m && this.opts.hardKeys === 'shown') return;
    ev.preventDefault();
    unlockIf(this);
    this._user('keyboard');
    const type = typeOfChar(c);
    if (m) { this._feedback(m.e.key); if (!this.reduced) this._ghostPress(m.e, -1, 90, this._backlight); }
    else if (this.opts.sound) thock(type, this.opts.volume);
    this._apply(type, c, m?.e || null, null, null, 'keyboard');
  }

  // --- the long-press panel (KeyboardView.makePanel) -----------------------------------------------------------------

  _makePanel(key, rect, selected = 0) {
    if (!key.alts.length) return null;
    const g = this._geo, dp = g.dp;
    const choices = key.alts.map((a) => this._display(a));
    const padY = this._metrics().padY * dp;
    const fx = rect.x + 3 * dp, fy = rect.y + padY, fw = rect.w - 6 * dp, fh = rect.h - padY * 2;
    const cellW = fw + 6 * dp, cellH = fh;
    const cols = Math.min(choices.length, 8), lines = Math.ceil(choices.length / cols), pad = 6 * dp;
    const w = cols * cellW + pad * 2, h = lines * cellH + pad * 2;
    // Centred on the key where it fits, the first choice nearest the finger.
    let left = fx + fw / 2 - cellW / 2 - pad;
    if (left + w > g.width - 2 * dp) left = g.width - 2 * dp - w;
    if (left < 2 * dp) left = 2 * dp;
    const top = fy - 2 * dp - h;
    const cells = choices.map((_, i) => {
      const line = lines - 1 - Math.floor(i / cols), col = i % cols;      // the first line is the one nearest the key
      return { x: left + pad + col * cellW, y: top + pad + line * cellH, w: cellW, h: cellH };
    });
    return { key, choices, cells, cols, lines, bounds: { x: left, y: top, w, h }, selected: clamp(selected, 0, choices.length - 1) };
  }
  _selectInPanel(panel, x, y) {
    const { cols, lines, cells } = panel;
    const col = clamp(Math.floor((x - cells[0].x) / cells[0].w), 0, cols - 1);
    // A finger below the panel is still on its first line.
    const fromTop = clamp(Math.floor((y - (panel.bounds.y + 6 * this._geo.dp)) / cells[0].h), 0, lines - 1);
    const index = Math.min((lines - 1 - fromTop) * cols + col, panel.choices.length - 1);
    if (index !== panel.selected) { panel.selected = index; this._sync(); }
  }
  /** An alternative or snippet as it is shown: the caret mark gone, a line break as ⏎, a tab as ⇥. */
  _pretty(text) { return text.replace('¦', '').replace(/\n/g, '⏎').replace(/\t/g, '⇥').trim() || text.trim(); }

  // --- what is pressed, and the popups -------------------------------------------------------------------------------

  _sync() {
    const t = this._touch, g = this._ghost;
    for (const e of this._keys) {
      const down = (!!t && t.key === e.key && !t.panel && !t.swipe) || (!!g && g.e.key === e.key && !g.panel);
      if (down !== e.down) { e.down = down; e.el.classList.toggle('is-down', down); }
    }
    let panel = null, pop = null;
    if (!this._dictation) {
      if (t) {
        if (t.panel) panel = t.panel;
        else if (this.opts.preview && t.key.type === 'char' && !t.cursorMoved && !t.swipe) pop = t;
      } else if (g) {
        if (g.panel) panel = g.panel;
        else if (this.opts.preview && g.e.key.type === 'char') pop = { key: g.e.key, rect: g.e.rect };
      }
    }
    this._showPop(pop);
    this._showPanel(panel);
  }

  /** The key preview: a bubble the width of the key standing on top of it, the character drawn large. */
  _showPop(p) {
    const pop = this._pop;
    if (!p || !this._geo) { if (!pop.hidden) pop.hidden = true; this._popKey = null; return; }
    const dp = this._geo.dp, r = p.rect;
    const fw = r.w - 6 * dp, fh = r.h - this._metrics().padY * 2 * dp, h = fh * 1.15, w = fw + 4 * dp;
    const text = this._shown(p.key);
    if (this._popKey !== p.key || pop.textContent !== text) {
      this._popKey = p.key;
      pop.textContent = text;
      pop.style.cssText = `left:${(r.x + dp).toFixed(2)}px;top:${(r.y + 4 * dp - h).toFixed(2)}px;width:${w.toFixed(2)}px;height:${h.toFixed(2)}px;` +
        `font-size:${this._fit(text, 30 * dp, 400, w - 4 * dp, 9 * dp).toFixed(2)}px`;
    }
    pop.hidden = false;
  }
  _showPanel(panel) {
    const el = this._panelEl;
    if (!panel) { if (!el.hidden) el.hidden = true; this._panelShown = null; return; }
    if (this._panelShown !== panel) {
      this._panelShown = panel;
      const b = panel.bounds, dp = this._geo.dp;
      el.style.cssText = `left:${b.x.toFixed(2)}px;top:${b.y.toFixed(2)}px;width:${b.w.toFixed(2)}px;height:${b.h.toFixed(2)}px`;
      el.replaceChildren(...panel.choices.map((c, i) => {
        const cell = panel.cells[i], s = document.createElement('span'), text = this._pretty(c);
        s.className = 'gk-cell';
        s.textContent = text;
        s.style.cssText = `left:${(cell.x - b.x).toFixed(2)}px;top:${(cell.y - b.y).toFixed(2)}px;width:${cell.w.toFixed(2)}px;height:${cell.h.toFixed(2)}px;` +
          `font-size:${this._fit(text, 22 * dp, 400, cell.w - 4 * dp, 9 * dp).toFixed(2)}px`;
        return s;
      }));
    }
    [...el.children].forEach((c, i) => c.classList.toggle('is-sel', i === panel.selected));
    el.hidden = false;
  }

  // --- a key going down with no finger on it (KeyboardView.ghostPress) -----------------------------------------------

  /** `light` is the backlight to flare: the service's own typing lights a key even with the backlight off. */
  _ghostPress(e, alt, hold, light = this._backlight || this._wordLight) {
    clearTimeout(this._ghostT);
    this._ghost = { e, panel: alt >= 0 ? this._makePanel(e.key, e.rect, alt) : null };
    if (light && !this.reduced) light.press(lightId(e.key), e.rect.x + e.rect.w / 2, e.rect.y + e.rect.h / 2, clock());
    this._sync();
    this._kick();
    this._ghostT = setTimeout(() => this._releaseGhost(), hold);
  }
  _releaseGhost() {
    clearTimeout(this._ghostT);
    if (!this._ghost) return;
    this._ghost = null;
    this._sync();
  }

  _sleep(ms) {
    return new Promise((resolve) => {
      const s = { resolve, id: setTimeout(() => { this._sleepers.delete(s); resolve(); }, ms) };
      this._sleepers.add(s);
    });
  }
  /** Waits while the keyboard is off screen or the tab is hidden; false once the script `run` has been cancelled. */
  async _ready(run) {
    await this._seen;                                                  // not before the page has said whether it is on screen
    while (run === this._run && (!this.visible || document.hidden)) await new Promise((r) => this._waiters.add(r));
    return run === this._run;
  }
  _wake() { const w = [...this._waiters]; this._waiters.clear(); w.forEach((r) => r()); }

  /** A scripted character as the field gets it, with its 'key' event. `\b` is a backspace, `\n` enter. */
  _typeChar(c, m, source) {
    const type = typeOfChar(c);
    if (type === 'char' && this.shift === 'on' && this.page === 'letters') { this._autoShifted = false; this._setShift('off'); }
    return this._apply(type, c, m?.e || null, null, null, source, m && m.alt >= 0 ? { alt: true } : {});
  }

  /**
   * One key going down and coming up `hold` ms later, typing `char`: the pressed face, the preview bubble (or the
   * long-press panel, for a character that lives in one), the backlight's flare, the sound. `\b` is backspace, `\n`
   * enter. A character with no key on the page showing still types; nothing lights. Resolves when the key is up.
   */
  press(char, { hold = 70, alts = true, source = 'script' } = {}) {
    const c = String(char);
    const m = this._find(c, alts);
    if (this.reduced || !this._geo) { this._typeChar(c, m, source); return Promise.resolve(true); }
    if (m) {
      if (m.alt >= 0) hold = Math.max(hold, 260);
      this._feedback(m.e.key);
      this._ghostPress(m.e, m.alt, hold);
    }
    this._typeChar(c, m, source);
    const run = this._run;
    return this._sleep(hold).then(() => run === this._run);
  }

  /** A tap at a point (CSS px from the keyboard's top left), resolved to a key as a finger's would be. Resolves to the key hit. */
  tapAt(x, y, { hold = 90, source = 'script' } = {}) {
    const e = this._keyAt(x, y);
    if (!e) return Promise.resolve(null);
    if (this.reduced) this._act(e.key, e, x, y, source); else this._tap(e, source, x, y, hold);
    const hit = { char: e.key.text, label: e.key.label, type: e.key.type, rect: { ...e.rect } };
    return this.reduced ? Promise.resolve(hit) : this._sleep(hold).then(() => hit);
  }

  /** The page that has `c`, and how: on the page showing first, then the others. */
  _pageFor(c) {
    const others = PAGES.filter((p) => p !== this.page);
    const lists = new Map(PAGES.map((p) => [p, p === this.page ? this._keys : this._rows(p).flatMap((r) => r.keys.map((key) => ({ key })))]));
    const here = this._find(c, true);
    if (here && (here.alt < 0 || here.e.key.hint === c)) return this.page;
    for (const p of others) if (this._find(c, false, lists.get(p))) return p;
    if (here) return this.page;
    for (const p of others) if (this._find(c, true, lists.get(p))) return p;
    return null;
  }
  /** Goes to `page` by pressing the keys that lead there (?123, =\<, ABC, { }). */
  async _goto(page, run, hold) {
    const next = (from) => this._rows(from).flatMap((r) => r.keys).filter((k) => k.type === 'mode' && k.page).map((k) => k.page);
    const seen = new Map([[this.page, null]]);
    const queue = [this.page];
    while (queue.length && !seen.has(page)) { const p = queue.shift(); for (const n of next(p)) if (!seen.has(n)) { seen.set(n, p); queue.push(n); } }
    if (!seen.has(page)) { this.setPage(page); return; }
    const path = [];
    for (let p = page; p !== this.page; p = seen.get(p)) path.unshift(p);
    for (const hop of path) {
      const e = this._keys.find((k) => k.key.type === 'mode' && k.key.page === hop);
      if (e) { this._feedback(e.key); this._ghostPress(e, -1, hold); await this._sleep(hold + 40); }
      if (run !== this._run) return;
      this.setPage(hop);
      await this._sleep(60);
      if (run !== this._run) return;
    }
  }
  async _shiftFor(c, run, hold) {
    if (this.page !== 'letters' || !isLetter(c)) return;
    const upper = c !== c.toLowerCase();
    if (upper === (this.shift !== 'off')) return;
    const e = this._keys.find((k) => k.key.type === 'shift');
    if (e) { this._feedback(e.key); this._ghostPress(e, -1, hold); }
    this._autoShifted = false;
    this._setShift(upper ? 'on' : 'off');
    await this._sleep(hold + 30);
  }

  /**
   * Types `text` key by key, as a person would. {wpm = 90, humanize = true, pages = true, hold, seed}.
   * `pages`: switches pages and taps shift to reach each character, as a thumb must (off by default on a fragment,
   * where a character with no key simply types and lights nothing). `\b` in the text is a backspace.
   * Waits while the keyboard is off screen. Resolves true when done, false if cancel() stopped it.
   * With prefers-reduced-motion the text arrives at once.
   */
  async type(text, { wpm = 90, humanize = true, pages = !this._frag, hold = null, seed = null } = {}) {
    this.cancel();
    const run = this._run;
    const list = chars(String(text));
    if (this.reduced) { for (const c of list) this._typeChar(c, null, 'script'); return true; }
    const pending = (this._pending = { list, at: 0, run });
    const rnd = seed == null ? Math.random : mulberry32(seed);
    const base = 60000 / (Math.max(1, wpm) * 5);
    const h = hold ?? Math.min(70, base * 0.6);
    for (let i = 0; i < list.length; i++) {
      if (!(await this._ready(run))) return false;
      const c = list[i];
      if (pages && typeOfChar(c) === 'char') {
        if (!this._find(c, false)) { const p = this._pageFor(c); if (p && p !== this.page) await this._goto(p, run, h); }
        if (run !== this._run) return false;
        await this._shiftFor(c, run, h);
        if (run !== this._run) return false;
      }
      pending.at = i + 1;
      await this.press(c, { hold: h });
      if (run !== this._run) return false;
      let d = base;
      if (humanize) {
        d *= 0.6 + rnd() * 0.8;
        if (/[.!?\n]/.test(c)) d += base * 1.8; else if (/[,;:]/.test(c)) d += base * 0.9; else if (c === ' ') d *= 1.15;
        if (rnd() < 0.04) d += base * 2;
      }
      await this._sleep(Math.max(0, d - h));
    }
    if (this._pending === pending) this._pending = null;
    return run === this._run;
  }

  /**
   * Types `text` as Ghostwriter does on the phone (DevKeyboardService.typeTick): a few characters a tick, the last
   * one's key going down for 60 ms and lighting, 22 ms between ticks, longer after a space, a comma, a full stop.
   * No page changes: a character with no key on the page lights nothing, and the text alone moves.
   */
  async ghostType(text, { hold = 60 } = {}) {
    this.cancel();
    const run = this._run;
    const list = chars(String(text));
    if (this.reduced) { this._apply('char', list.join(''), null, null, null, 'ghost'); return true; }
    const pending = (this._pending = { list, at: 0, run, ghost: true });
    const per = Math.min(6, Math.floor(list.length / 150) + 1);
    while (pending.at < list.length) {
      if (!(await this._ready(run))) return false;
      const chunk = list.slice(pending.at, pending.at + per);
      pending.at += chunk.length;
      const last = chunk[chunk.length - 1];
      const m = this._find(last, false);
      if (m) { this._ghostPress(m.e, -1, hold); this._feedback(m.e.key); }
      this._apply('char', chunk.join(''), m?.e || null, null, null, 'ghost');
      const pause = (/[.!?\n]/.test(last) ? 140 : /[,;:]/.test(last) ? 70 : last === ' ' ? 34 : 22) + Math.random() * 18;
      await this._sleep(pause);
      if (run !== this._run) return false;
    }
    if (this._pending === pending) this._pending = null;
    return true;
  }

  /** Stops type(), ghostType(), swipe() and any press in flight; their promises resolve false. */
  cancel() {
    this._run++;
    this._pending = null;
    for (const s of [...this._sleepers]) { clearTimeout(s.id); s.resolve(); }
    this._sleepers.clear();
    this._wake();
    this._releaseGhost();
    if (this._scripted) { this._scripted = false; this._trail = null; this._overlayDirty = true; this._kick(); }
    return this;
  }
  /** Writes what is left of a type() or ghostType() at once (Ghostwriter's "Skip"). */
  finish() {
    const p = this._pending;
    this.cancel();
    if (!p || p.at >= p.list.length) return this;
    const rest = p.list.slice(p.at);
    if (p.ghost) this._apply('char', rest.join(''), null, null, null, 'ghost'); else for (const c of rest) this._typeChar(c, null, 'script');
    return this;
  }
  /** Whether a script is typing now. */
  get typing() { return !!this._pending; }

  /** A pause in a script: waits `ms` (and while the keyboard is off screen). Resolves false if cancel() cut it short. */
  async wait(ms) {
    const run = this._run;
    if (!(await this._ready(run))) return false;
    await this._sleep(ms);
    return run === this._run;
  }

  // --- the visitor, and the idle loop that makes way for them --------------------------------------------------------

  /** A person touched the keyboard (a key, the row, their own keys, its field): 'user' fires, and an idle loop makes way. */
  _user(kind) {
    this._lastUser = clock();
    this._emit('user', { kind });
    this._loop?.handOver();
  }
  /** Says a visitor has taken over by some control of the page's own (a search box, a button): the idle loop stops for them. */
  interrupt(kind = 'page') { this._user(kind); return this; }

  /**
   * Plays `script` while the keyboard is on screen and nobody is using it: `rounds` times, then it rests as the script
   * left it until the keyboard has been scrolled away and come back. The moment a visitor touches it the script stops
   * where it is and the keys are theirs; it plays again only on their next visit to it (scrolled away and back), or
   * `resume` ms after they have left it alone when that is a number.
   *
   *   kbd.loop(async (s) => {            // s: {type, ghostType, swipe, press, tapAt, wait, kbd, field}
   *     s.field.clear();
   *     await s.type('on my way');       // each step ends the script cleanly if the visitor takes over
   *     await s.wait(1600);
   *   }, { gap: 1400, still: (kbd) => kbd.field.set('on my way') });
   *
   * {resume = 'return' (a number of ms; false: never again), rounds = 3 (Infinity: no rest), gap = 1400 between
   * rounds, still(kbd): what to show, once and unmoving, under prefers-reduced-motion and while the page's keys are
   * paused (setPaused; without it the script runs once, at once), onHandOver(kbd), onResume(kbd)}.
   * Returns a function that stops the loop.
   */
  loop(script, { resume = 'return', rounds = 3, gap = 1400, still = null, onHandOver = null, onResume = null } = {}) {
    this.stopLoop();
    const STOP = { stop: true };
    const L = (this._loop = { stopped: false, held: false, paused: false, at: 0, instant: false });
    const safely = (fn) => { try { fn?.(this); } catch (err) { console.error(err); } };
    const guard = (ok) => { if (!ok || L.stopped || L.held || L.paused) throw STOP; return ok; };
    const nap = (ms) => new Promise((resolve) => { const id = setTimeout(() => { this._timers.delete(id); resolve(); }, ms); this._timers.add(id); });
    /** Resolves once the keyboard is off screen (at once if it is), or the loop is stopped. */
    const away = () => (this.visible && !L.stopped ? new Promise((resolve) => this._leavers.add(resolve)) : Promise.resolve());
    const kbd = this;
    const s = {
      kbd,
      get field() { return kbd.field; },
      type: (text, o) => this.type(text, o).then(guard),
      ghostType: (text, o) => this.ghostType(text, o).then(guard),
      swipe: (word, o) => this.swipe(word, o).then(guard),
      press: (char, o) => this.press(char, o).then(guard),
      tapAt: (x, y, o) => this.tapAt(x, y, o).then((hit) => { guard(true); return hit; }),
      wait: (ms) => (L.instant ? Promise.resolve(guard(true)) : this.wait(ms).then(guard)),
    };
    L.handOver = () => {
      L.at = clock();
      if (L.held || L.stopped) return;
      L.held = true;
      this.cancel();
      safely(onHandOver);
    };
    L.pause = () => { if (L.stopped || L.paused) return; L.paused = true; this.cancel(); };
    (async () => {
      await this._seen;
      let played = 0;
      while (!L.stopped && !this._dead) {
        if (PAUSED) {
          // The page's keys are paused: the still picture, and nothing more until they are let go again.
          L.paused = true;
          if (!L.held) safely(still);
          await new Promise((resolve) => this._resumers.add(resolve));
          L.paused = false;
          played = 0;
          continue;
        }
        if (this.reduced) {
          // Nothing plays: the still picture, or the script once with every pause taken out.
          if (still) safely(still);
          else { L.instant = true; try { await script(s); } catch (err) { if (err !== STOP) console.error(err); } }
          return;
        }
        if (L.held) {
          if (resume === false) return;
          if (typeof resume === 'number') {
            // Not while they are still in it: a finger down, or the focus on the keyboard or its field.
            if (!this.visible || document.hidden) { await this._ready(this._run); continue; }
            const active = document.activeElement;
            const busy = !!this._touch || (active && (this.el.contains(active) || this.field.el === active));
            if (busy) L.at = clock();
            if (busy || clock() - L.at < resume) { await nap(250); continue; }
          } else {
            // Theirs for as long as they can see it. Once it has been scrolled away it starts over for the next visit.
            await away();
            if (L.stopped || PAUSED) continue;
          }
          L.held = false;
          played = 0;
          safely(onResume);
        }
        if (played >= rounds) { await away(); played = 0; continue; }
        if (!(await this._ready(this._run))) continue;
        try { await script(s); await s.wait(gap); played++; }
        catch (err) { if (err !== STOP) console.error(err); if (!L.held && !L.stopped && !L.paused) await nap(Math.max(250, gap)); }
      }
    })();
    return () => { if (this._loop === L) this.stopLoop(); else L.stopped = true; };
  }
  /** Stops the idle loop for good (the keyboard stays as it is). */
  stopLoop() {
    const L = this._loop;
    if (!L) return this;
    L.stopped = true;
    this._loop = null;
    this.cancel();
    this._letGo();
    return this;
  }

  /** Lets a stopped loop's waits end (for the keyboard to leave the screen, for the page's pause to be lifted). */
  _letGo() {
    for (const set of [this._leavers, this._resumers]) { const all = [...set]; set.clear(); all.forEach((fn) => fn()); }
  }

  // --- swipe typing --------------------------------------------------------------------------------------------------

  /** The letters a path passes over, runs collapsed: what a decoder starts from. */
  _lettersAlong(path) {
    const letters = this._keys.filter((e) => this._isLetterKey(e.key));
    let out = '';
    for (const [x, y] of path) {
      let best = null, bestD = Infinity;
      for (const e of letters) { const d = Math.hypot(x - e.rect.x - e.rect.w / 2, y - e.rect.y - e.rect.h / 2); if (d < bestD) { bestD = d; best = e; } }
      if (best && bestD < best.rect.w * 0.75 && out[out.length - 1] !== best.key.text) out += best.key.text;
    }
    return out;
  }
  /** Lights a word's letters one after another, 55 ms apart, as the app does for a swiped word. */
  lightWord(word) {
    if (this.reduced) return this;
    const light = this._backlight || this._wordLight;
    chars(fold(String(word).toLowerCase())).forEach((c, i) => {
      const e = this._keys.find((k) => k.key.type === 'char' && k.key.text === c);
      if (!e) return;
      const id = setTimeout(() => {
        this._timers.delete(id);
        light.press(lightId(e.key), e.rect.x + e.rect.w / 2, e.rect.y + e.rect.h / 2, clock());
        this._kick();
      }, i * 55);
      this._timers.add(id);
    });
    return this;
  }
  /**
   * A finger swiping `word` across the letters: the trail drawn through its keys over `ms`, the letters lit, the word
   * typed (with the space the app puts before it). {ms = 90 per letter, commit = true}. Resolves true, or false if cancelled.
   */
  async swipe(word, { ms = null, commit = true } = {}) {
    this.cancel();
    const run = this._run;
    word = String(word);
    const put = () => {
      if (!commit) return;
      const b = this.field.before;
      this._apply('char', (b && !/\s$/.test(b) ? ' ' : '') + word, null, null, null, 'script', { swipe: true });
    };
    if (this.page !== 'letters') this.setPage('letters');
    const pts = chars(fold(word.toLowerCase())).map((c) => this.keyRect(c)).filter(Boolean).map((r) => [r.cx, r.cy]);
    if (this.reduced || pts.length < 2 || !(await this._ready(run))) { if (run === this._run) put(); return run === this._run; }
    const total = ms ?? Math.max(420, pts.length * 90);
    const path = [pts[0].slice()];
    this._trail = path;
    this._fading = null;
    this._scripted = true;
    const t0 = clock();
    const step = () => {
      const f = clamp((clock() - t0) / total, 0, 1) * (pts.length - 1);
      const i = Math.min(pts.length - 2, Math.floor(f)), u = f - i, s = u * u * (3 - 2 * u);
      this._swipePoint(path, pts[i][0] + (pts[i + 1][0] - pts[i][0]) * s, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * s);
      this._overlayDirty = true;
      this._kick();
    };
    while (clock() - t0 < total) { step(); await this._sleep(16); if (run !== this._run) return false; }
    step();
    this._scripted = false;
    this._trail = null;
    this._fading = { path, since: clock() };
    this._overlayDirty = true;
    this.lightWord(word);
    put();
    this._emit('swipe', { word, path: path.map((p) => ({ x: p[0], y: p[1] })), letters: this._lettersAlong(path), keyWidth: this._geo.keyWidth, source: 'script' });
    this._kick();
    return true;
  }

  // --- adaptive keys -------------------------------------------------------------------------------------------------

  /**
   * The adaptive keys model: {offsets: {a: [dx, dy], …} in fractions of a key, mistakes: [{x, y}] in fractions of the
   * keyboard's width and of the keys' height}, or null. Touches then go to the key whose learned aim is nearest, and
   * with {map: true} the heatmap is drawn over the letters as the app draws it.
   */
  setAdaptive(model, { map = false } = {}) {
    this._adaptive = model || null;
    this._adaptiveMap = !!model && map;
    this._overlayDirty = true;
    this._kick();
    return this;
  }

  // --- the canvas over the keys --------------------------------------------------------------------------------------

  /**
   * `fn(ctx, geometry, now)` draws over the keyboard on a canvas in CSS px (cleared first; geometry is this.geometry
   * plus keyRect(char) and theme). Return true to be drawn again next frame; otherwise it is drawn on redraw().
   */
  setOverlay(fn) { this._overlay = fn || null; this._overlayDirty = true; this._kick(); return this; }
  /** Asks for the overlay to be drawn again (your data changed). */
  redraw() { this._overlayDirty = true; this._kick(); return this; }

  _drawTrail(ctx, pts, fade) {
    if (pts.length < 2) return;
    const dp = this._geo.dp;
    const from = Math.max(0, pts.length - 160);
    const tail = pts[from], head = pts[pts.length - 1];
    ctx.beginPath();
    ctx.moveTo(tail[0], tail[1]);
    for (let i = from + 1; i < pts.length; i++) { const p = pts[i - 1], q = pts[i]; ctx.quadraticCurveTo(p[0], p[1], (p[0] + q[0]) / 2, (p[1] + q[1]) / 2); }
    ctx.lineTo(head[0], head[1]);
    const [r, g, b] = rgbOf(this._theme.accent);
    const strong = `rgba(${r},${g},${b},${clamp((fade * 235) / 255, 0, 1)})`, faint = `rgba(${r},${g},${b},${clamp((fade * 40) / 255, 0, 1)})`;
    let style = strong;
    if (Math.hypot(head[0] - tail[0], head[1] - tail[1]) >= 4 * dp) {
      style = ctx.createLinearGradient(tail[0], tail[1], head[0], head[1]);
      style.addColorStop(0, faint); style.addColorStop(1, strong);
    }
    ctx.strokeStyle = style;
    ctx.lineWidth = 5 * dp;
    ctx.lineCap = ctx.lineJoin = 'round';
    ctx.stroke();
  }
  /** Each remembered mistake a soft red glow where the touch landed; each moved aim a dot tied to its key's middle. */
  _drawAdaptive(ctx) {
    const g = this._geo, dp = g.dp, m = this._adaptive;
    const radius = g.keyWidth * 0.45;
    for (const hit of m.mistakes || []) {
      const x = hit.x * g.width, y = g.keysTop + hit.y * g.keysHeight;
      const glow = ctx.createRadialGradient(x, y, 0, x, y, radius);
      glow.addColorStop(0, 'rgba(255,59,48,.333)'); glow.addColorStop(1, 'rgba(255,59,48,0)');
      ctx.fillStyle = glow;
      ctx.beginPath(); ctx.arc(x, y, radius, 0, Math.PI * 2); ctx.fill();
    }
    ctx.strokeStyle = ctx.fillStyle = cssOf(this._theme.accent);
    ctx.lineWidth = 1.5 * dp;
    ctx.lineCap = 'butt';
    for (const e of this._keys) {
      if (!this._isLetterKey(e.key)) continue;
      const [ox, oy] = this._aim(e.key.text);
      if (!ox && !oy) continue;
      const r = e.rect, cx = r.x + r.w / 2, cy = r.y + r.h / 2, ax = cx + ox * r.w, ay = cy + oy * r.h;
      ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(ax, ay); ctx.stroke();
      ctx.beginPath(); ctx.arc(ax, ay, 3.5 * dp, 0, Math.PI * 2); ctx.fill();
    }
  }
  _drawOverlay(now) {
    const g = this._geo, ctx = this._canvas.getContext('2d');
    if (!g || !ctx) return false;
    let again = false;
    ctx.setTransform(this._dpr, 0, 0, this._dpr, 0, 0);
    ctx.clearRect(0, 0, g.width, g.height);
    if (this._adaptiveMap && this.page === 'letters') this._drawAdaptive(ctx);
    if (this._trail) { this._drawTrail(ctx, this._trail, 1); again = !!this._scripted; }
    if (this._fading) {
      const f = 1 - (now - this._fading.since) / 280;
      if (f <= 0) this._fading = null; else { this._drawTrail(ctx, this._fading.path, f); again = true; }
    }
    if (this._overlay) {
      ctx.save();
      try { if (this._overlay(ctx, { ...this.geometry, keyRect: (c) => this.keyRect(c), theme: this._theme }, now) === true && !this.reduced) again = true; }
      catch (err) { console.error(err); }
      ctx.restore();
    }
    return again;
  }

  // --- the light on the legends (KeyboardView.legendLight) -----------------------------------------------------------

  _lightDirty() { this._lightStale = true; }
  _paintLight(light, now) {
    const g = this._geo;
    if (!g) return;
    const th = this._theme, text = rgbOf(th.text), hint = rgbOf(th.hint);
    let any = false;
    for (const e of this._keys) {
      const f = e.face, cx = f.x + f.w / 2, cy = f.y + f.h / 2;
      let css = '';
      const i = light ? light.light(lightId(e.key), cx, cy, g.width, now) * light.strength : 0;
      if (i > 0.01) {
        const t = Math.min(1, i);
        if (light.dims) css = `a${(1 - t * 0.8).toFixed(3)}`;
        // On the enter key's accent pill the accent would vanish, so it keeps its own ink.
        else if (e.key.type !== 'enter') {
          const c = light.colourAt(cx, g.width, now, th.accent, t), k = 1 - t;
          const m = (base) => `rgb(${Math.min(255, Math.round(base[0] * k + c[0] * t))},${Math.min(255, Math.round(base[1] * k + c[1] * t))},${Math.min(255, Math.round(base[2] * k + c[2] * t))})`;
          css = m(text) + '|' + m(hint);
        }
      }
      if (css) any = true;
      if (css === e.lit) continue;
      e.lit = css;
      const s = e.el.style;
      if (!css) { s.removeProperty('--kb-lit'); s.removeProperty('--kb-lit-hint'); s.removeProperty('--kb-lit-a'); }
      else if (css[0] === 'a') { s.removeProperty('--kb-lit'); s.removeProperty('--kb-lit-hint'); s.setProperty('--kb-lit-a', css.slice(1)); }
      else { const [a, b] = css.split('|'); s.setProperty('--kb-lit', a); s.setProperty('--kb-lit-hint', b); s.removeProperty('--kb-lit-a'); }
    }
    this._lit = any;
  }

  // --- frames: only while something moves, and only on screen --------------------------------------------------------

  _setVisible(v) {
    if (v === this.visible) return;
    this.visible = v;
    this.el.classList.toggle('gk--paused', !v);
    this.field.el?.classList?.toggle('gk-field--paused', !v);
    if (v) { this._wake(); this._kick(); }
    else {
      // Off screen with the focus still in it: a key pressed now would type where nobody is looking, and an idle loop
      // waiting for its visitor to leave would wait for ever. It lets the focus go.
      const active = document.activeElement;
      if (active && active !== document.body && (this.el.contains(active) || this.field.el === active)) active.blur?.();
      const gone = [...this._leavers];
      this._leavers.clear();
      gone.forEach((fn) => fn());
    }
    this._emit('visible', { visible: v });
  }
  _kick() {
    if (this._raf || this._dead || !this.visible || document.hidden) return;
    this._raf = requestAnimationFrame((t) => this._frame(t));
  }
  _frame() {
    this._raf = 0;
    if (this._dead) return;
    const now = clock();
    let again = false;
    // With reduced motion the board shows its resting light once and never moves.
    const still = this.reduced;
    const light = this._backlight || (this._wordLight.flaring(now) ? this._wordLight : null);
    if (still) {
      if (this._lightStale) { this._lightStale = false; this._paintLight(this._backlight, 0); }
    } else if (light) {
      if (this._lightStale || now - this._lastLight >= light.frameDelay() - 2) { this._lightStale = false; this._lastLight = now; this._paintLight(light, now); }
      again = light.animating(now);
    } else if (this._lit || this._lightStale) { this._lightStale = false; this._paintLight(null, now); }
    if (this._overlayDirty || this._trail || this._fading || this._overlayLive) {
      this._overlayDirty = false;
      this._overlayLive = this._drawOverlay(now);
      again = again || this._overlayLive;
    }
    if (again) this._kick();
  }

  /** Takes the keyboard out of the page and lets go of everything it holds. */
  destroy() {
    this._dead = true;
    this.stopLoop();
    this.cancel();
    this._cancelTouch();
    cancelAnimationFrame(this._raf);
    cancelAnimationFrame(this._roRaf);
    BOARDS.delete(this);
    for (const id of this._timers) clearTimeout(id);
    this._sawFirst();
    this._ro?.disconnect();
    this._io?.disconnect();
    document.removeEventListener('visibilitychange', this._onVis);
    DARK_MQ?.removeEventListener?.('change', this._onScheme);
    RM?.removeEventListener?.('change', this._onMotion);
    this.detach();
    this._fieldOff?.();
    this._subs.clear();
    this.el.remove();
  }
}

function unlockIf(kbd) { if (kbd.opts.sound) unlockAudio(); }

GhostKbd.themes = THEMES;
GhostKbd.themeNames = THEME_NAMES;
GhostKbd.effects = EFFECTS;
GhostKbd.colours = BACKLIGHT_COLOURS;
GhostKbd.icon = icon;
GhostKbd.layouts = Layouts;
/** A fragment in one call: GhostKbd.fragment(host, ['g', 'h', 'o', 's', 't'], {borders: true}). */
GhostKbd.fragment = (host, spec, opts = {}) => new GhostKbd(host, { ...opts, fragment: spec });

// --- a headline, typed ----------------------------------------------------------------------------------------------

/**
 * Types `text` into `el` (a heading, a line of a hero) with a caret, no keyboard needed.
 *
 *   const t = typeInto(h1, 'A keyboard that writes like you.', { wpm: 80, humanize: true });
 *   t.cancel();  t.finish();  await t.done;        // done: true when it was all typed (or finished), false if cancelled
 *
 * The whole text is in the page from the start for a screen reader, and takes its room from the start, so nothing
 * moves as it is typed. `\b` in the text is a backspace (a slip, corrected). `text` may be a list of phrases: each is
 * typed, held, and rubbed out as far as the next one differs ({loop: true} goes round again). With no `text`, what
 * `el` already says is typed.
 *
 * {wpm = 70, humanize = true, caret = true | 'typing' (gone once done) | false, delay = 0 ms, whenVisible = true,
 *  hold = 1800 ms a phrase stays, eraseWpm = 240, loop = false, seed, label (what a screen reader hears),
 *  motion: 'auto' | 'full' | 'reduced', onDone(handle)}. With prefers-reduced-motion the text is simply there.
 */
export function typeInto(el, text = null, opts = {}) {
  const o = { wpm: 70, humanize: true, caret: true, delay: 0, whenVisible: true, hold: 1800, eraseWpm: 240, loop: false, seed: null, label: null, motion: 'auto', onDone: null, ...opts };
  const phrases = (Array.isArray(text) ? text : [text ?? el.textContent ?? '']).map((t) => chars(String(t)));
  const finals = phrases.map((list) => { const out = []; for (const c of list) { if (c === '\b') out.pop(); else out.push(c); } return out; });
  const reduced = o.motion === 'reduced' || (o.motion !== 'full' && !!RM && RM.matches);
  const rnd = o.seed == null ? Math.random : mulberry32(o.seed);

  const sr = document.createElement('span');
  sr.className = 'gk-vh';
  sr.textContent = o.label ?? finals[0].join('');
  const vis = document.createElement('span');
  vis.className = 'gk-typed-vis';
  vis.setAttribute('aria-hidden', 'true');
  const done = document.createElement('span'), rest = document.createElement('span'), caret = document.createElement('span');
  done.className = 'gk-typed-done';
  rest.className = 'gk-typed-rest';
  caret.className = 'gk-caret gk-caret--inline';
  vis.append(done, caret, rest);
  el.classList.add('gk-typed');
  el.replaceChildren(sr, vis);

  let shown = [], stopped = false, skip = false, timer = 0, wake = null, io = null, current = finals[0];
  const draw = (final) => {
    const typed = shown.join('');
    done.textContent = typed;
    rest.textContent = final.join('').startsWith(typed) ? final.slice(shown.length).join('') : '';
  };
  const sleep = (ms) => new Promise((resolve) => { wake = resolve; timer = setTimeout(resolve, ms); });
  const seen = () => new Promise((resolve) => {
    if (!o.whenVisible || !('IntersectionObserver' in window)) { resolve(); return; }
    wake = resolve;
    io = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) { io.disconnect(); io = null; resolve(); } }, { threshold: 0.2 });
    io.observe(el);
  });
  const awake = () => new Promise((resolve) => {
    if (!document.hidden) { resolve(); return; }
    const on = () => { if (!document.hidden) { document.removeEventListener('visibilitychange', on); resolve(); } };
    document.addEventListener('visibilitychange', on);
  });
  const settle = (final, keepCaret) => {
    shown = final.slice();
    draw(final);
    if (!keepCaret) caret.remove();
    el.classList.add('gk-typed--done');
  };

  const handle = {
    el,
    /** Stops where it is; what was typed stays. */
    cancel() { if (stopped) return handle; stopped = true; clearTimeout(timer); io?.disconnect(); wake?.(); return handle; },
    /** Writes the rest at once. */
    finish() { if (stopped) return handle; skip = true; clearTimeout(timer); io?.disconnect(); wake?.(); return handle; },
  };
  handle.done = (async () => {
    if (reduced) { settle(finals[0], false); return true; }
    draw(finals[0]);
    if (o.caret === false) caret.remove();
    await seen();
    if (o.delay > 0 && !stopped && !skip) await sleep(o.delay);
    const base = 60000 / (Math.max(1, o.wpm) * 5);
    const many = phrases.length > 1;
    for (let p = 0; !stopped && !skip; p++) {
      const list = phrases[p % phrases.length], final = (current = finals[p % phrases.length]);
      el.classList.remove('gk-typed--done');
      for (const c of list) {
        await awake();
        if (stopped || skip) break;
        if (c === '\b') shown.pop(); else shown.push(c);
        draw(final);
        caret.replaceWith(caret);                                      // put back in place: its blink starts over, so it holds while typing
        let d = base;
        if (o.humanize) {
          d *= 0.6 + rnd() * 0.8;
          if (/[.!?\n]/.test(c)) d += base * 1.8; else if (/[,;:]/.test(c)) d += base * 0.9; else if (c === ' ') d *= 1.15;
          if (rnd() < 0.04) d += base * 2;
        }
        await sleep(d);
      }
      if (stopped || skip) break;
      el.classList.add('gk-typed--done');
      const last = p % phrases.length === phrases.length - 1;
      if (!many || (last && !o.loop)) { settle(final, o.caret === true); return true; }
      await sleep(o.hold);
      if (stopped || skip) break;
      const next = finals[(p + 1) % phrases.length];
      let keep = 0;
      while (keep < shown.length && keep < next.length && shown[keep] === next[keep]) keep++;
      current = next;
      while (shown.length > keep && !stopped && !skip) { shown.pop(); draw(next); await sleep(60000 / (Math.max(1, o.eraseWpm) * 5)); }
    }
    if (skip) { settle(current, o.caret === true); return true; }
    return false;
  })().then((ok) => { if (ok) { try { o.onDone?.(handle); } catch (err) { console.error(err); } } return ok; });
  return handle;
}

export default GhostKbd;
