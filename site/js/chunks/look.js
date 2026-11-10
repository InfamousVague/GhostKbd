/*
 * look: the keyboard's colours and its backlight, on the face of a keycap.
 *
 * Five keys of the real board (q w e r t) stand on a small plate in the theme's ground colour, under the backlight as
 * Backlight.kt works it out. Left alone, a quiet loop types on them so the light has something to follow and then
 * steps to the next pairing of a theme and an effect; the key's sub-legend names what is showing ("Glacier ·
 * Ripple"). There is no panel of controls: the whole key is the one control, and a tap (or Enter or Space on it)
 * steps to the next pairing.
 *
 * The pairings go once through the app's five themes (catalogue.dart themePresets, KeyboardTheme.PRESETS: Ghost,
 * Glacier, Midnight, Paper, Retro) and its six backlight effects, in the order Settings › Backlight lists them.
 *
 * What is true of the app here, and what is a choice:
 *   - Key borders are ON (Settings › Look & layout › Key borders; off as installed), so a preset's key colour shows.
 *   - The backlight's brightness is High and its colour Primary (the app starts at Medium); it lights the legends
 *     only, never the keys, as on the phone.
 *   - Ghost follows the page's light or dark, as the app's Ghost preset follows the phone's (theme 'auto').
 *
 *   import { mount } from './chunks/look.js';
 *   mount(document.querySelector('[data-chunk="look"]'));
 *
 * mount(root, {status}) fills `root` (a .keycap__body) and returns the GhostKbd. `status` is the element that is told
 * the theme and effect in words; left out, it is the [data-look-status] element on the same keycap face, if there is
 * one. Styles: css/chunks/look.css.
 */
import { GhostKbd, applyTheme, THEME_NAMES, EFFECTS } from '../kbd.js';

/** Ghost is the app's own look. It follows the page's light or dark. */
const GHOST = 'auto';
/** [theme, effect id]: each theme at least once, each effect once, in the effects' own order. */
const PAIRINGS = [[GHOST, 'reactive'], ['glacier', 'ripple'], ['retro', 'heatmap'], ['midnight', 'rainbow'], ['paper', 'starlight'], [GHOST, 'breathe']];
/** Words that need only these five keys, one for each pairing. Nothing shows them; they are there for the light to run over. */
const WORDS = ['tree', 'were', 'tweet', 'wet', 'ewer', 'twee'];

// The fragment as kbd.js lays it out: five key units of 40.9dp and 3dp of edge, one 60dp row and 2dp under it.
const DESIGN_W = 5 * 40.9 + 3;
const DESIGN_H = 62;
const MAX_DP = 1.3;

export function mount(root, { status = null } = {}) {
  root.innerHTML =
    '<div class="look"><div class="look__plate"><div class="look__keys"></div></div></div>' +
    '<button type="button" class="look__next" aria-label="Look and feel: show the next theme and backlight"></button>';
  const box = root.firstChild;
  const plate = box.firstChild;
  const says = status || root.closest('.keycap__face')?.querySelector('[data-look-status]') || null;

  // The plate is as large as the face lets five keys be.
  const size = () => {
    const pad = parseFloat(getComputedStyle(box).getPropertyValue('--look-pad')) || 0;
    const W = box.clientWidth, H = box.clientHeight;
    if (!W) return;
    const dp = Math.max(0.5, Math.min(MAX_DP, (W - pad * 2) / DESIGN_W, (H - pad * 2) / DESIGN_H));
    plate.style.width = Math.floor(DESIGN_W * dp + pad * 2) + 'px';
  };
  size();

  let at = 0;
  const effectOf = (id) => EFFECTS.find((e) => e[0] === id);

  // The keys only show: the whole keycap is the control (the button over it, for a keyboard and a screen reader).
  const kbd = new GhostKbd(plate.firstChild, {
    fragment: { keys: [...'qwert'], minDp: 0.5, maxDp: MAX_DP },
    theme: PAIRINGS[0][0], bare: true, borders: true, hints: false, preview: false, autoCaps: false, interactive: false,
    backlight: PAIRINGS[0][1], backlightOptions: { strength: 'high' },
    label: 'Five keys of Ghost Keyboard under its backlight: q w e r t',
  });

  /** What is showing, in words: on the key's sub-legend, and (aloud) for a visitor who stepped to it. */
  const tell = (aloud) => {
    const [theme, effect] = PAIRINGS[at];
    const words = `${theme === GHOST ? THEME_NAMES.ghost : THEME_NAMES[theme]} · ${effectOf(effect)[1]}`;
    box.dataset.theme = theme === GHOST ? 'ghost' : theme;
    box.dataset.effect = effect;
    if (says) { says.setAttribute('aria-live', aloud ? 'polite' : 'off'); says.textContent = words; }
  };
  const show = (i, aloud = false) => {
    at = ((i % PAIRINGS.length) + PAIRINGS.length) % PAIRINGS.length;
    const [theme, effect] = PAIRINGS[at];
    kbd.setTheme(theme);
    kbd.setBacklight(effect, { strength: 'high' });
    tell(aloud);
  };
  // The plate is the keyboard's ground, so it takes the theme too (and follows 'auto' when the system changes).
  kbd.on('theme', () => applyTheme(plate, kbd.opts.theme));
  applyTheme(plate, PAIRINGS[0][0]);
  tell(false);

  // A tap anywhere on the key: the next pairing, and a word typed once so its light is seen.
  const cap = root.closest('.keycap') || root;
  const onClick = (ev) => {
    if (ev.defaultPrevented) return;
    kbd.interrupt('page');
    show(at + 1, true);
    if (!kbd.reduced) kbd.type(WORDS[at], { wpm: 40 });
  };
  cap.addEventListener('click', onClick);

  // Idle: a word, slowly, for the light to follow; then the next pairing. Once through them all, and it rests.
  // Reduced motion: nothing plays; the keys show the first pairing's resting light.
  kbd.loop(async (s) => {
    await s.wait(500);
    await s.type(WORDS[at], { wpm: 30 });
    await s.wait(1800);
    show(at + 1);
  }, { gap: 300, rounds: PAIRINGS.length, still: () => {} });

  // Sized again when the face changes, a frame later: the keyboard's own observer then lays the keys out in its turn.
  let ro = null, raf = 0;
  if ('ResizeObserver' in window) {
    ro = new ResizeObserver(() => { cancelAnimationFrame(raf); raf = requestAnimationFrame(size); });
    ro.observe(box);
  }
  const destroy = kbd.destroy.bind(kbd);
  kbd.destroy = () => { ro?.disconnect(); cancelAnimationFrame(raf); cap.removeEventListener('click', onClick); destroy(); root.replaceChildren(); };
  return kbd;
}

export default mount;
