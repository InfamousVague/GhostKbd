/*
 * The terminal chunk: Terminal mode's keys and three of the developer page's pairs, over a two-line terminal.
 *
 * What it shows is the app's (DevKeyboardService.tools and sendWithModifiers, Layouts.dev):
 *   - the row above the keys in an SSH or Termux app, with Terminal keys switched on in Settings › Row tools: Esc,
 *     Tab, Ctrl, Alt and the arrows. On the phone the row runs on to | ~ / - && > past its edge; a key's face has
 *     no room for them, and a row that is cut off reads as a mistake, so they are left out here;
 *   - Ctrl and Alt are sticky: on (their chip in medium weight) until the next letter or digit, which is sent with
 *     them as a key press, and then both are let go. Ctrl, then C, is Ctrl+C;
 *   - Tab, Esc and the arrows are sent as key presses;
 *   - ( ) [ ] { } on the developer page type the pair and leave the caret inside.
 * What the terminal does with those keys is a shell's and is played here by a few lines: ^C and a new prompt, Tab
 * finishing the one command that starts that way (or listing those that do), up and down through the history.
 *
 *   <div class="keycap__body" data-chunk="terminal"></div>      mount(root) fills it; css/chunks/terminal.css lays it out
 */

import { GhostKbd } from '../kbd.js';

const HOST = 'box:~';
const LOGIN = '$ ssh box';
const COMMANDS = ['cargo', 'cat', 'cd', 'curl', 'docker', 'echo', 'git', 'grep', 'htop', 'journalctl', 'kubectl', 'ls', 'make', 'ssh', 'sudo', 'systemctl', 'tail', 'tmux', 'vim'];
const KEYS = [['esc', 'Esc'], ['tab', 'Tab'], ['ctrl', 'Ctrl'], ['alt', 'Alt'], ['left', '←', 'Left'], ['up', '↑', 'Up'], ['down', '↓', 'Down'], ['right', '→', 'Right']];
/** A body this tall (px) has room for the terminal above the keys; a shorter one puts them side by side. */
const TALL = 190;

export function mount(root) {
  root.classList.add('tm');
  root.innerHTML =
    '<div class="tm-screen" role="group" aria-label="A terminal, signed in to a server over SSH">' +
    '<div class="tm-line tm-above"></div>' +
    `<div class="tm-line"><span class="tm-ps"><span class="tm-host">${HOST}</span>$ </span><span class="tm-in"></span></div>` +
    '</div><div class="keycap__keys tm-keys"></div>';
  const above = root.querySelector('.tm-above');
  const prompt = root.querySelector('.tm-ps');

  const kbd = new GhostKbd(root.lastChild, {
    // At a phone's own scale and no larger, so the row's eight chips are whole beside the terminal on a wide key.
    fragment: { keys: ['c', { gap: 0.5 }, '( )', '[ ]', '{ }'], page: 'dev', fit: 'contain', maxDp: 1 },
    row: true, cog: false, theme: 'auto', bare: true, borders: true, preview: false,
    autoCaps: false, doubleSpacePeriod: false, enter: 'run',
    label: 'Ghost Keyboard’s terminal keys, with the C key and three pair keys',
  });
  const field = kbd.attach(root.querySelector('.tm-in'));
  const strip = kbd.el.querySelector('.gk-strip');

  // --- the shell's side ---------------------------------------------------------------------------------------------

  const hist = ['tail -f app.log'];
  let at = hist.length;
  /** The line above: what the shell said, or (with `typed`) the prompt and what was typed at it. */
  const say = (text, typed = false) => { above.replaceChildren(...(typed ? [prompt.cloneNode(true)] : []), text); };
  /** The line being typed goes up, with `mark` after it, and a new prompt stands under it. */
  const fresh = (mark = '') => { say(field.text + mark, true); field.clear(); at = hist.length; };
  const recall = (d) => { at = Math.max(0, Math.min(hist.length, at + d)); field.set(hist[at] ?? ''); };
  const complete = () => {
    const word = field.before;
    if (!word || /\s/.test(word)) return;
    const hits = COMMANDS.filter((c) => c.startsWith(word));
    if (hits.length === 1) field.replace(0, field.caret, hits[0] + ' ');
    else if (hits.length) say(hits.join('  '));
  };

  // --- the keyboard's side ------------------------------------------------------------------------------------------

  const mod = { ctrl: false, alt: false };
  const act = {
    esc() {}, tab: complete,
    ctrl() { hold('ctrl', !mod.ctrl); }, alt() { hold('alt', !mod.alt); },
    left() { field.move(-1); }, right() { field.move(1); }, up() { recall(-1); }, down() { recall(1); },
  };
  const items = KEYS.map(([id, label, ariaLabel]) => ({ id, label, ariaLabel, chip: true, onTap: act[id] }));
  /**
   * The chips as this chunk needs them. A sticky key's chip is changed where it stands (setRow would build the row
   * again, dropping the focus and the scroll). The row is a toolbar and ONE tab stop: Left, Right, Home and End move
   * between its chips, so a keyboard visitor passes the key in two presses of Tab, not ten.
   */
  let stop = KEYS[0][0];
  const dress = () => {
    for (const el of strip.children) {
      const id = el.dataset.id;
      if (id in mod) { el.classList.toggle('gk-bold', mod[id]); el.setAttribute('aria-pressed', String(mod[id])); }
      el.tabIndex = id === stop ? 0 : -1;
    }
  };
  strip.setAttribute('role', 'toolbar');
  strip.setAttribute('aria-label', 'Terminal keys');
  strip.addEventListener('focusin', (ev) => { const id = ev.target.dataset?.id; if (id && id !== stop) { stop = id; dress(); } });
  strip.addEventListener('keydown', (ev) => {
    const chips = [...strip.children], i = chips.indexOf(ev.target);
    const step = { ArrowRight: 1, ArrowLeft: -1 }[ev.key];
    const to = ev.key === 'Home' ? 0 : ev.key === 'End' ? chips.length - 1 : step && i >= 0 ? (i + step + chips.length) % chips.length : -1;
    if (to < 0 || ev.altKey || ev.ctrlKey || ev.metaKey) return;
    ev.preventDefault();
    chips[to].focus();
  });
  function hold(name, on) {
    mod[name] = on;
    items.find((i) => i.id === name).bold = on;
    dress();
  }
  /** The key after a sticky Ctrl or Alt: sent with them, and both let go (sendWithModifiers). */
  const chord = (k) => {
    if (mod.ctrl && !mod.alt) {
      if (k === 'c') fresh('^C');
      else if (k === 'u') field.replace(0, field.caret, '');
      else if (k === 'a') field.caret = 0;
      else if (k === 'e') field.caret = field.text.length;
      else if (k === 'l') say('');
    }
    hold('ctrl', false);
    hold('alt', false);
  };

  kbd.setRow(items);
  dress();
  kbd.on('layout', dress);
  kbd.on('key', (e) => {
    if (e.type === 'enter') { if (field.text) hist.push(field.text); fresh(); }
    else if (e.type === 'char' && (mod.ctrl || mod.alt) && /^[a-z0-9[\]\\]$/i.test(e.char)) { e.preventDefault(); chord(e.char.toLowerCase()); }
  });

  // --- room: beside the keys on a short key, above them on a tall one -----------------------------------------------

  const ro = 'ResizeObserver' in window ? new ResizeObserver(() => root.classList.toggle('tm--tall', root.clientHeight >= TALL)) : null;
  ro?.observe(root);

  // --- left alone, it plays one short line -------------------------------------------------------------------------

  const reset = () => { hold('ctrl', false); hold('alt', false); field.clear(); at = hist.length; say(LOGIN); };
  /** The loop's own finger on a chip of the row: it goes down, comes up, and does what a tap does. */
  const tap = async (s, id) => {
    const el = strip.querySelector(`[data-id="${id}"]`);
    el?.classList.add('is-down');
    try { await s.wait(150); } finally { el?.classList.remove('is-down'); }
    act[id]();
  };
  reset();
  kbd.loop(async (s) => {
    reset();
    await s.wait(800);
    await s.type('jou', { wpm: 48 });
    await s.wait(380);
    await tap(s, 'tab');
    await s.wait(320);
    await s.type('-f', { wpm: 48 });
    await s.wait(950);
    await tap(s, 'ctrl');
    await s.wait(520);
    await s.press('c', { hold: 120 });
    await s.wait(950);
    await s.type('echo $', { wpm: 52 });
    const pair = kbd.keyRect('(¦)');
    if (pair) await s.tapAt(pair.cx, pair.cy, { hold: 130 });
    await s.wait(300);
    await s.type('date', { wpm: 52 });
    await s.wait(2400);
  }, {
    gap: 500,
    still: () => { field.set('journalctl -f'); fresh('^C'); field.set('echo $(date)', 11); },
  });

  const destroy = kbd.destroy.bind(kbd);
  kbd.destroy = () => { ro?.disconnect(); destroy(); };
  return kbd;
}

export default mount;
