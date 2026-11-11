/*
 * look: the keyboard's colours, its backlight and its layouts, on the face of a keycap.
 *
 * The foot of a small screen stands on the key's face with the whole keyboard on it, as a phone has it: a line being
 * typed, and under it the real board (kbd.js) in a theme, under a backlight, in one of the layouts Settings › Look &
 * feel offers. Left alone, a quiet loop types what is showing ("condensed keys", "split when unfolded"), so the light
 * has something to follow and the picture says what it is, then steps to the next look; the key's two small legends
 * name it too, the layout at the top ("Condensed") and the theme and effect at the foot ("Glacier · Ripple").
 * There is no panel of controls: the whole key is the one control, and a tap (or Enter or Space on it) steps to the
 * next look.
 *
 * The looks go once through every layout: on a phone the key heights (Normal, Condensed, Tall; Short lies between
 * and is left to the words under the key) and one-handed; then the screen widens to a foldable's inner screen for
 * the five the app has there (catalogue.dart wideLayouts: split, full width, docked left, centred, docked right).
 * The app's five themes (themePresets: Ghost, Glacier, Midnight, Paper, Retro) and its six backlight effects go round
 * with them, each at least once.
 *
 * What is true of the app here, and what is a choice:
 *   - Every look is drawn at one size (one dp), so a condensed keyboard is as much shorter than a normal one here as
 *     on the phone, and the unfolded screen as much wider. Only where the key is too narrow for that (a phone's own
 *     width) is the unfolded screen drawn smaller than the phone beside it.
 *   - The layouts are the app's own code read across (KeyboardView.layoutKeys, in kbd.js): the open middle of the
 *     split is 30% of the width, a docked board is 400dp, one-handed keys take 80% of the width, and the chevron on
 *     the empty side is where the app draws it.
 *   - Key borders are ON (Settings › Look & feel › Keys › Key borders; off as installed), so a preset's key colour
 *     and each layout's shape show at this size. Symbol hints are off (on as installed): at this size they are specks.
 *   - No row above the keys (suggestions and tools stand there on the phone): at this size its words cannot be read.
 *     Condensed makes that row slimmer too, which is not shown.
 *   - The backlight's brightness is High and its colour Primary (the app starts at Medium); it lights the legends
 *     only, never the keys, as on the phone.
 *   - Ghost follows the page's light or dark, as the app's Ghost preset follows the phone's (theme 'auto').
 *   - One-handed is shown for the right hand; the left is its mirror, a tap on the chevron away.
 *
 *   import { mount } from './chunks/look.js';
 *   mount(document.querySelector('[data-chunk="look"]'));
 *
 * mount(root, {status, layout}) fills `root` (a .keycap__body) and returns the GhostKbd. `status` is the element
 * that is told the theme and effect in words and `layout` the one told the layout; left out, they are the
 * [data-look-status] and [data-look-layout] elements on the same keycap face, where there are any.
 * Styles: css/chunks/look.css.
 */
import { GhostKbd, applyTheme, THEME_NAMES, EFFECTS, KEY_HEIGHTS } from '../kbd.js';

/** Ghost is the app's own look. It follows the page's light or dark. */
const GHOST = 'auto';

/** The two screens, in dp across: a phone, and a foldable's inner screen unfolded (600dp and up is a wide screen). */
const PHONE = 412;
const UNFOLDED = 690;

/**
 * The layouts: the name on the key (Settings' own name for the choice, and short: it shares a line with the key's
 * legend), what is typed on it (letters and spaces only, so no page of the board is turned), the screen it is on,
 * and what the keyboard is asked for beyond PLAIN.
 */
const LAYOUTS = {
  normal: { name: 'Normal', says: 'normal keys', screen: PHONE, opts: {} },
  condensed: { name: 'Condensed', says: 'condensed keys', screen: PHONE, opts: { keyHeight: 'condensed' } },
  tall: { name: 'Tall', says: 'tall keys', screen: PHONE, opts: { keyHeight: 'tall' } },
  hand: { name: 'One-handed', says: 'one handed', screen: PHONE, opts: { oneHanded: 'right' } },
  split: { name: 'Split', says: 'split when unfolded', screen: UNFOLDED, opts: { wideLayout: 'split' } },
  full: { name: 'Full width', says: 'full width when unfolded', screen: UNFOLDED, opts: { wideLayout: 'full' } },
  left: { name: 'Docked left', says: 'docked left when unfolded', screen: UNFOLDED, opts: { wideLayout: 'left' } },
  center: { name: 'Centred', says: 'centred when unfolded', screen: UNFOLDED, opts: { wideLayout: 'center' } },
  right: { name: 'Docked right', says: 'docked right when unfolded', screen: UNFOLDED, opts: { wideLayout: 'right' } },
};
const PLAIN = { keyHeight: 'normal', oneHanded: 'off', wideLayout: 'full' };

/** [theme, effect id, layout]: every layout once, in the order above; each theme and each effect at least once. */
const LOOKS = [
  [GHOST, 'reactive', 'normal'], ['glacier', 'ripple', 'condensed'], ['retro', 'heatmap', 'tall'],
  ['midnight', 'rainbow', 'hand'], ['paper', 'starlight', 'split'], [GHOST, 'breathe', 'full'],
  ['glacier', 'reactive', 'left'], ['retro', 'ripple', 'center'], ['midnight', 'heatmap', 'right'],
];

/** A board's height in dp with no row above it (kbd.js _metrics): the strip over the keys, four rows, 2dp under them. */
const boardDp = (keyHeight = 'normal') => (keyHeight === 'condensed' ? 8 : 18) + 4 * KEY_HEIGHTS[keyHeight] + 2;
const TALLEST = Math.max(...Object.values(LAYOUTS).map((l) => boardDp(l.opts.keyHeight)));
const MAX_DP = 0.72;
const MIN_DP = 0.3;

export function mount(root, { status = null, layout = null } = {}) {
  root.innerHTML =
    '<div class="look"><div class="look__screen"><div class="look__app" aria-hidden="true"><div class="look__line"></div></div>' +
    '<div class="look__keys"></div></div></div>' +
    '<button type="button" class="look__next" aria-label="Look and feel: show the next theme, backlight and layout"></button>';
  const box = root.firstChild;
  const screen = box.firstChild;
  // The screen takes the first look's colours before anything is measured: measuring settles its style, and colours
  // that arrived after that would fade in from the stylesheet's own (a dark flash on a light page).
  applyTheme(screen, LOOKS[0][0]);
  const face = root.closest('.keycap__face');
  const says = status || face?.querySelector('[data-look-status]') || null;
  const names = layout || face?.querySelector('[data-look-layout]') || null;

  let at = 0;
  const effectOf = (id) => EFFECTS.find((e) => e[0] === id);
  const lookOf = (i) => { const [theme, effect, id] = LOOKS[i]; return { theme, effect, id, layout: LAYOUTS[id] }; };
  const themeName = (theme) => (theme === GHOST ? THEME_NAMES.ghost : THEME_NAMES[theme]);

  // One dp for every look where the face has the room: what the tallest board and the line over it leave of the
  // face's height. A screen too wide for the face at that dp (the unfolded one, on a phone) is drawn at what fits.
  let dps = { [PHONE]: 0.46, [UNFOLDED]: 0.46 };
  const fit = () => {
    const W = box.clientWidth, H = box.clientHeight;
    if (!W || !H) return false;
    const css = getComputedStyle(box);
    const app = parseFloat(css.getPropertyValue('--look-app')) || 0;
    const edge = (parseFloat(css.getPropertyValue('--look-edge')) || 0) * 2;
    const one = (across) => Math.round(Math.max(MIN_DP, Math.min(MAX_DP, (H - app - edge) / TALLEST, (W - edge) / across)) * 1000) / 1000;
    const next = { [PHONE]: one(PHONE), [UNFOLDED]: one(UNFOLDED) };
    const changed = next[PHONE] !== dps[PHONE] || next[UNFOLDED] !== dps[UNFOLDED];
    dps = next;
    return changed;
  };
  fit();
  /** The screen is as wide as the look's own, at its dp; the keyboard is told that dp, so it is that many dp across. */
  const widen = () => {
    const across = lookOf(at).layout.screen;
    screen.style.width = Math.floor(across * dps[across]) + 'px';
    screen.dataset.screen = across === PHONE ? 'phone' : 'unfolded';
  };
  const optsOf = () => { const l = lookOf(at).layout; return { ...PLAIN, ...l.opts, scale: dps[l.screen] }; };
  const labelOf = () => {
    const { theme, effect, layout: l } = lookOf(at);
    return `Ghost Keyboard at the foot of ${l.screen === PHONE ? 'a phone’s screen' : 'an unfolded foldable’s screen'}: ${l.says}, the ${themeName(theme)} theme, the ${effectOf(effect)[1]} backlight`;
  };

  // The keys only show: the whole keycap is the control (the button over it, for a keyboard and a screen reader).
  widen();
  const kbd = new GhostKbd(screen.lastChild, {
    ...optsOf(), theme: LOOKS[0][0], row: false, borders: true, hints: false, preview: false, autoCaps: false, interactive: false,
    backlight: LOOKS[0][1], backlightOptions: { strength: 'high' }, label: labelOf(),
  });
  const field = kbd.attach(screen.querySelector('.look__line'), { focusOnClick: false });

  /** What is showing, in words: on the key's two small legends, and (aloud) for a visitor who stepped to it. */
  const tell = (aloud) => {
    const { theme, effect, id, layout: l } = lookOf(at);
    box.dataset.theme = theme === GHOST ? 'ghost' : theme;
    box.dataset.effect = effect;
    box.dataset.layout = id;
    for (const [el, words] of [[names, l.name], [says, `${themeName(theme)} · ${effectOf(effect)[1]}`]]) {
      if (!el) continue;
      el.setAttribute('aria-live', aloud ? 'polite' : 'off');
      el.textContent = words;
    }
  };
  const show = (i, aloud = false) => {
    at = ((i % LOOKS.length) + LOOKS.length) % LOOKS.length;
    const { theme, effect } = lookOf(at);
    field.set('');
    widen();
    kbd.set({ ...optsOf(), theme, backlight: effect, backlightOptions: { strength: 'high' }, label: labelOf() });
    tell(aloud);
  };
  // The screen takes each theme too (and follows 'auto' when the system changes): its ground and the line's ink.
  kbd.on('theme', () => applyTheme(screen, kbd.opts.theme));
  tell(false);

  // A tap anywhere on the key: the next look, and its words typed once so its light is seen.
  const cap = root.closest('.keycap') || root;
  const onClick = (ev) => {
    if (ev.defaultPrevented) return;
    kbd.interrupt('page');
    show(at + 1, true);
    if (kbd.reduced) field.set(lookOf(at).layout.says); else kbd.type(lookOf(at).layout.says, { wpm: 90 });
  };
  cap.addEventListener('click', onClick);

  // Idle: the look's own words, for the light to follow; then the next look. Once through them all, and it rests.
  // Reduced motion, or the page's keys paused: nothing plays; the first look stands with its words written.
  // The line is cleared as the words start, not before the wait: a tap that lands while the loop is coming back
  // (the key scrolled into view under the finger) has begun the same words, and the loop then starts them over.
  kbd.loop(async (s) => {
    await s.wait(500);
    field.set('');
    await s.type(lookOf(at).layout.says, { wpm: 80 });
    await s.wait(1800);
    show(at + 1);
  }, { gap: 300, rounds: LOOKS.length, still: () => field.set(lookOf(at).layout.says) });

  // Sized again when the face changes, a frame later: the keyboard's own observer then lays the keys out in its turn.
  let ro = null, raf = 0;
  if ('ResizeObserver' in window) {
    ro = new ResizeObserver(() => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => { if (fit()) { widen(); kbd.set({ scale: optsOf().scale }); } });
    });
    ro.observe(box);
  }
  const destroy = kbd.destroy.bind(kbd);
  kbd.destroy = () => { ro?.disconnect(); cancelAnimationFrame(raf); cap.removeEventListener('click', onClick); destroy(); root.replaceChildren(); };
  return kbd;
}

export default mount;
