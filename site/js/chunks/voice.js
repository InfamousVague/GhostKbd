/*
 * "In my voice" (the Mockingbird plugin), on the face of a 2u keycap.
 *
 * A stiff sentence sits selected in a line of text; under it is the keyboard's row above the keys, and with a
 * selection that row offers one chip, "✦ In my voice" (DevKeyboardService.tools). Tapping it, or leaving the key
 * alone for a moment, plays what the phone does: the row says "✦ Writing “In my voice”…" with a ✕ (aiRow, a job
 * running), then the sentence is rubbed out and typed again as you, the row saying "✦ Typing…" with Skip (placeAnswer
 * and startTyping, "Type the answer out" being on by default). A little later the next sentence is selected.
 *
 * Mockingbird keeps two voices, how you text and how you write, and picks by the app the field is in
 * (Mockingbird.rewrite: chat apps are texting, everything else writing). The sentences here take turns, and the
 * key's hint (an element with data-voice-kind, if the page gives one) says which is showing.
 *
 * Nothing is sent anywhere and no AI is asked: the four sentences and their rewrites are written out below, and the
 * key's caption says they are samples.
 *
 *   import { mount } from './chunks/voice.js';
 *   mount(document.querySelector('[data-chunk="voice"]'));      // the .keycap__body; returns the GhostKbd
 */
import { GhostKbd } from '../kbd.js';

/* One person's two voices: lower case and no full stops in a chat, plain short sentences everywhere else. */
const SAMPLES = [
  { chat: true, stiff: 'I will be there in ten minutes.', mine: 'there in 10' },
  { chat: false, stiff: 'Please find the report attached.', mine: "Report's attached." },
  { chat: true, stiff: 'I am unable to attend tonight.', mine: "can't make tonight, sorry" },
  { chat: false, stiff: 'I apologise for the late reply.', mine: 'Sorry, slow reply.' },
];

const SELECTED = [{ re: /[\s\S]+/, cls: 'voice__sel' }];
const OFFERED = 0, WRITING = 1, TYPING = 2, DONE = 3;
const REST = 2600;      // a selected sentence waits this long before the key taps its own chip
const THINK = 900;      // "Writing…": the answer is on its way
const HOLD = 2600;      // the rewrite stays this long before the next sentence is selected

export function mount(root) {
  root.classList.add('voice');
  root.innerHTML = '<p class="keycap__field voice__field"></p><div class="voice__row"></div><span class="gk-vh" role="status"></span>';
  const [line, rowHost, status] = root.children;
  const kindEl = root.closest('.keycap')?.querySelector('[data-voice-kind]') || null;

  // Only the row above the keys. Its ground is painted by voice.css on the host, so the chips line up with the legend.
  const kbd = new GhostKbd(rowHost, {
    fragment: { kind: 'bar', width: 328, minDp: 1, maxDp: 1.1 },     // never under a phone's own 44dp row
    theme: 'auto', bare: true, cog: false, backlight: null, autoCaps: false, hardKeys: false,
    label: 'The row above the keys, with a sentence selected',
  });
  const fieldOpts = { text: '', caret: false, follow: false, focusOnClick: false, marks: null };
  const field = kbd.attach(line, fieldOpts);

  let at = 0, phase = DONE, token = 0;

  /** The whole sentence selected (no caret, as on the phone), or plain text with the caret after it. */
  function select(on) {
    fieldOpts.marks = on ? SELECTED : null;
    fieldOpts.caret = !on;
  }

  // The row's buttons come and go. Someone working it from their own keyboard keeps their place in it.
  let keep = false;
  document.addEventListener('focusin', () => { keep = false; });
  function row(items) {
    const active = document.activeElement;
    let keyed = false;
    try { keyed = !!active && kbd.el.contains(active) && active.matches(':focus-visible'); } catch { /* an old browser */ }
    if (keyed) keep = true;
    kbd.setRow(items);
    if (keep && (!document.activeElement || document.activeElement === document.body)) {
      kbd.el.querySelector('.gk-strip button')?.focus({ preventScroll: true });
    }
  }

  /** Sentence `i`, selected, with the chip under it. */
  function offer(i, fresh = true) {
    ++token;
    kbd.cancel();                                                      // a wait or a typing of ours still under way
    at = ((i % SAMPLES.length) + SAMPLES.length) % SAMPLES.length;
    const it = SAMPLES[at];
    phase = OFFERED;
    select(true);
    field.set(it.stiff);
    if (fresh) { line.classList.remove('is-new'); void line.offsetWidth; line.classList.add('is-new'); }
    if (kindEl) kindEl.textContent = it.chat ? 'texting' : 'writing';
    row([{ id: 'voice', label: '✦ In my voice', bold: true, chip: true, onTap: () => { run('visitor'); } }]);
  }

  /** The rewrite is in the field: the row empties, and after a while the next sentence is selected. */
  function land(it, by) {
    const my = ++token;
    kbd.cancel();                                                      // Skip: the typing stops, and the rest is written at once
    phase = DONE;
    select(false);
    field.set(it.mine);
    row([]);
    if (by === 'visitor') status.textContent = `Rewritten as you, ${it.chat ? 'texting' : 'writing'}: ${it.mine}`;
    return kbd.wait(HOLD).then((ok) => { if (ok && my === token) offer(at + 1); });
  }

  /** The chip, tapped: writing, then typed out as you. */
  async function run(by) {
    if (phase !== OFFERED) return;
    const my = ++token, it = SAMPLES[at];
    phase = WRITING;
    row([
      { id: 'ai-run', label: '✦ Writing “In my voice”…', chip: true, fill: true, disabled: true },
      // Stopped, the sentence is still selected, so the chip is offered again.
      { id: 'ai-x', label: '✕', ariaLabel: 'Stop writing', chip: true, onTap: () => offer(at, false) },
    ]);
    if (!(await kbd.wait(THINK)) || my !== token) return;
    phase = TYPING;
    select(false);
    field.set('');
    row([
      { id: 'ai-typing', label: '✦ Typing…', chip: true, fill: true, disabled: true },
      { id: 'ai-skip', label: 'Skip', bold: true, chip: true, onTap: () => { land(it, 'visitor'); } },
    ]);
    await kbd.ghostType(it.mine);
    if (my !== token) return;
    await land(it, by);
  }

  /** The key taps its own chip: the chip goes down for a moment, as under a finger. */
  async function tap(s) {
    const chip = kbd.el.querySelector('[data-id="voice"]');
    chip?.classList.add('is-down');
    try { await s.wait(170); } finally { chip?.classList.remove('is-down'); }
  }

  offer(0, false);
  kbd.loop(async (s) => {
    if (phase !== OFFERED) offer(phase === DONE ? at + 1 : at);
    await s.wait(REST);
    await tap(s);
    await run('loop');
    await s.wait(0);                                                   // ends here when a visitor has taken over
  }, { gap: 0, rounds: SAMPLES.length, still: () => { if (phase !== OFFERED) offer(at, false); } });

  return kbd;
}

export default mount;
