// Ghostwriter, on the face of a key three units wide: one line of text, the row above the keys, and six keys of the
// top row. A <<request>> is typed on the line; as it closes the row says "✦ Writing “…”…", and then the answer is typed
// over it a few characters a beat with the last one's key lighting, as DevKeyboardService.typeTick does on the phone.
// A fact it could not know is left as a short [blank]. A tap on the key goes to the next request.
//
// With data-ghostwriter="row" on the body (the page's hero) there are no letter keys: the line and the row only. The
// keycaps beside it in the same row of keys, marked data-press="open" and data-press="close", are then its << and >>:
// each goes down as the script types its two characters, and a tap on either is a tap on the key.
//
// It is SCRIPTED: no AI is asked here, and the answers below are written by hand. On the phone the request goes to
// the AI the person chose, with their own key. The key's caption has to say so.
//
//   <article class="keycap keycap--3u"> … <div class="keycap__body" data-chunk="ghostwriter"></div> … </article>
//   import { mount } from './chunks/ghostwriter.js';  mount(body)  → the GhostKbd of the six keys
//
// Needs css/chunks/ghostwriter.css beside site.css and kbd.css.

import { GhostKbd } from '../kbd.js';

// The requests are the app's own examples (Ghostwriter.kt's prompt, the catalogue). The answers are made up for this
// page, with a [blank] where the phone would have had nothing to go on.
export const REQUESTS = [
  { ask: 'engineering standup tldr', answer: 'Shipped the search fix. Today: the retry bug. Demo is [day].' },
  { ask: 'decline politely', answer: "Thanks, but I can't make it. Could we do [when] instead?" },
  { ask: 'weekly update for the team', answer: 'Search is faster and exports are fixed. Next week: [what is next].' },
];

const KEYS = ['e', 'r', 't', 'y', 'u', 'i'];

// Ghostwriter.CLOSED and DevKeyboardService.FILL_OPEN: a request just closed before the cursor, and one still open.
const CLOSED = /<<(?=[^<>\n]*\p{L})([^<>\n]{2,400})>>$/u;
const OPEN = /<<([^<>\n]{0,400})$/;
// A request and a [blank] on the line, picked out in the accent. That is this page's emphasis: the keyboard does not
// colour another app's text.
const MARKS = [{ re: /<<[^<>\n]*(?:>>)?/, cls: 'ghostwriter__tag' }, { re: /\[[^[\]\n]{1,24}\]/, cls: 'ghostwriter__blank' }];

/** A request as a chip shows it: one line, cut short (DevKeyboardService.preview). */
function preview(text) {
  const line = text.replace(/\s+/g, ' ').trim();
  return line.length > 28 ? line.slice(0, 27) + '…' : line;
}

export function mount(root) {
  const rowOnly = root.dataset.ghostwriter === 'row';
  root.classList.add('ghostwriter');
  root.classList.toggle('ghostwriter--row', rowOnly);
  // The line is a picture of a message being typed, not a field anyone can enter: a screen reader is told what was
  // asked and what was written by the status line, once a visitor has tapped, and the animated line is left out.
  root.innerHTML =
    '<div class="ghostwriter__line" aria-hidden="true"></div>' +
    '<div class="ghostwriter__row"></div>' +
    '<div class="keycap__keys ghostwriter__keys"></div>' +
    '<span class="gk-vh" role="status"></span>' +
    '<button type="button" class="ghostwriter__next" aria-label="Ghostwriter, a sample: show the next request and its answer"></button>';
  const [lineEl, rowEl, keysEl, status] = root.children;

  // Both only play: the whole key is the one control (the button over it, for a keyboard and a screen reader).
  const bar = new GhostKbd(rowEl, {
    fragment: { width: 300, minDp: rowOnly ? 1 : 0.82, maxDp: rowOnly ? 1.1 : 1 }, theme: 'auto', bare: true, cog: false, interactive: false, backlight: null,
    label: 'The row above the keys',
  });
  // The hero has no letter keys: the typing hand is a keyboard that draws nothing, behind the face.
  const kbd = new GhostKbd(keysEl, rowOnly ? {
    fragment: 'bar', theme: 'auto', bare: true, cog: false, backlight: null, autoCaps: false, hardKeys: false, interactive: false,
    label: 'Ghostwriter, typing by itself',
  } : {
    fragment: { keys: KEYS, fit: 'contain', maxDp: 1.05 },
    theme: 'auto', bare: true, borders: true, hints: false, preview: false, autoCaps: false, interactive: false,
    label: 'Six keys of Ghost Keyboard’s top row, lighting as Ghostwriter types',
  });
  const field = kbd.attach(lineEl, { marks: MARKS, focusOnClick: false });

  // The keycaps beside this one that are its << and >> (the hero's row of three keys).
  const board = root.closest('.keycaps__row');
  const caps = { open: board?.querySelector('[data-press="open"]') || null, close: board?.querySelector('[data-press="close"]') || null };
  /** Types `pair` (<< or >>) with its keycap, if the page has one, held down while it does. */
  const typePair = async (s, pair, cap) => {
    cap?.classList.add('is-down');
    try { await s.type(pair, { wpm: 300, humanize: false }); await s.wait(cap ? 140 : 0); }
    finally { cap?.classList.remove('is-down'); }
  };

  let at = 0;             // which request
  let phase = null;       // 'writing': the answer is on its way; 'typing': it is being typed; null: neither
  let answered = false;   // with reduced motion: the answer is showing, not the request

  // The row as DevKeyboardService.aiRow and contextual() fill it. The chips are not buttons here: Skip and ✕ do nothing.
  const chip = (id, label, more) => ({ id, label, chip: true, disabled: true, ...more });
  function row() {
    let items = [];
    if (phase === 'typing') items = [chip('ai-typing', '✦ Typing…', { fill: true }), chip('ai-skip', 'Skip', { bold: true })];
    else if (phase === 'writing') items = [chip('ai-run', `✦ Writing “${preview(REQUESTS[at].ask)}”…`, { fill: true }), chip('ai-x', '✕')];
    else {
      const closed = CLOSED.exec(field.before), open = OPEN.exec(field.before);
      if (closed) items = [chip('fill', `✦ Fill: ${preview(closed[1])}`, { bold: true, fill: true })];
      else if (open) {
        const ask = open[1].trim();
        items = [chip('fill-open', ask.length < 2 ? '✦ Ghostwriter: say what to write, then >>' : `✦ Fill: ${preview(ask)}`, { fill: true })];
      }
    }
    bar.setRow(items);
    bar.el.setAttribute('aria-label', ['The row above the keys', ...items.map((i) => i.label)].join(': '));
  }
  // "Fill as soon as I type >>" is on as installed: the closing >> starts the request.
  kbd.on('input', () => {
    if (!phase && !kbd.reduced && CLOSED.test(field.before)) phase = 'writing';
    row();
  });

  const script = async (s) => {
    const r = REQUESTS[at];
    phase = null;
    field.clear();
    await s.wait(600);
    await typePair(s, '<<', caps.open);
    await s.type(r.ask, { wpm: 130, seed: at + 7 });
    await typePair(s, '>>', caps.close);                  // the same key twice, quickly
    await s.wait(1200);                                   // "Writing…": in the app, as long as the AI takes
    phase = 'typing';
    field.clear();                                        // the answer replaces the request
    await s.ghostType(r.answer);
    phase = null;
    row();
    await s.wait(3400);
    at = (at + 1) % REQUESTS.length;
  };
  // With reduced motion nothing plays. The request stands with its Fill chip (as with "Fill as soon as I type >>"
  // off); a tap puts the answer there at once (as with "Type the answer out" off), the next tap the next request.
  const still = () => {
    phase = null;
    field.set(answered ? REQUESTS[at].answer : `<<${REQUESTS[at].ask}>>`);
  };
  // Once round the requests, then it rests until it is scrolled back to or tapped (kbd.loop's rounds).
  const play = () => kbd.loop(script, { gap: 0, resume: false, rounds: REQUESTS.length, still });

  const next = () => {
    if (kbd.reduced && !answered) answered = true;
    else { answered = false; at = (at + 1) % REQUESTS.length; }
    const r = REQUESTS[at];
    status.textContent = `A sample. Asked: ${r.ask}. Written: ${r.answer}`;
    play();
  };
  // The key itself, legend and caption too; outside a keycap, the chunk. And its << and >> keys, where it has them.
  const cap = root.closest('.keycap') || root;
  const taps = [cap, caps.open, caps.close].filter(Boolean);
  const onClick = (ev) => { if (!ev.defaultPrevented) next(); };
  for (const el of taps) el.addEventListener('click', onClick);

  const destroy = kbd.destroy.bind(kbd);
  kbd.destroy = () => { for (const el of taps) el.removeEventListener('click', onClick); bar.destroy(); destroy(); };

  row();
  play();
  return kbd;
}

export default mount;
