/*
 * Dictation (the Dictation plugin), on the face of a 1.5u keycap: the mic key, and beside it a line of text.
 *
 * Tapping the key, or leaving it alone for a moment, plays what the phone does (DevKeyboardService.onMic): the
 * listening panel takes the keyboard's place, a dot in the accent with a ring that swells with the voice and
 * "Listening… tap to finish" (KeyboardView.drawDictation); a second tap ends the take and the panel says
 * "Writing it down…" while whisper works; then the words are in the field, all at once, as commitText puts them
 * (onDictated). A take ended inside a quarter of a second writes nothing, and one ended part-way writes what had been
 * said by then (Dictation.start: the whole take is transcribed).
 *
 * A take ends on the SECOND TAP. Since 0.16.0 it has no time limit, its words arrive a piece at each pause, and it
 * ends by itself only after 45 seconds with nobody speaking (Dictation.IDLE_STOP_MS). This sample is a short take:
 * its words still come all at once, as a short take's do. So the
 * loop plays that second tap on the listening panel, and a visitor who starts a sample and never taps again is told,
 * in the line itself, that the sample has ended and why, rather than shown a take that ends itself.
 *
 * This page never asks for the microphone, and the key's caption says so. The sentences are written out below, and
 * the levels are made from their words. Two of them carry words from the hint whisper is given on the phone (Dictation.PROMPT: npm, Kotlin, async,
 * JSON), which is why they come out spelled as a developer writes them.
 *
 *   import { mount } from './chunks/dictation.js';
 *   mount(document.querySelector('[data-chunk="dictation"]'));      // the .keycap__body; returns the GhostKbd
 */
import { GhostKbd } from '../kbd.js';

const SAMPLES = [
  'Running late, start without me.',
  'Rebase onto main and run npm test again.',
  'Make the fetch async and cache the JSON.',
];

const IDLE = 0, LISTENING = 1, TRANSCRIBING = 2;
const TICK = 100;       // the phone reads the microphone a tenth of a second at a time, and says how loud each was
const SHORTEST = 250;   // a take under a quarter of a second is nothing (Dictation.start)
const QUIET = 2400;     // a visitor who does not tap again: this long after the sample's last word, the sample is ended for them
const OVER = 'Sample over. On the phone it listens until you tap again, and writes your words down at each pause.';
const THINK = 1100;     // "Writing it down…"
const REST = 3200;      // a sentence stays this long before the key taps itself again

/**
 * A sentence as the microphone hears it: how loud each tenth of a second is (0..1), and how many words are over by
 * then. A word is a swell or two, a comma or a full stop a breath.
 */
function spoken(text) {
  const words = text.split(' ');
  let seed = 7;
  for (const ch of text) seed = (seed * 31 + ch.codePointAt(0)) >>> 0;
  const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
  const levels = [0.05, 0.08], heard = [0, 0];
  words.forEach((word, i) => {
    const beats = Math.min(4, Math.max(1, Math.round(word.replace(/\W/g, '').length / 2.4)));
    for (let b = 0; b < beats; b++) { levels.push(0.45 + 0.5 * rnd()); heard.push(i); }
    const breath = /[,.]$/.test(word) ? 2 : 1;
    for (let b = 0; b < breath; b++) { levels.push(0.04 + 0.1 * rnd()); heard.push(i + 1); }
  });
  return { words, levels, heard };
}

export function mount(root) {
  root.classList.add('dictation');
  root.innerHTML = '<p class="keycap__field dictation__field"></p><div class="keycap__keys dictation__keys"></div><span class="gk-vh" role="status"></span>';
  const [line, host, status] = root.children;

  const kbd = new GhostKbd(host, {
    fragment: { keys: ['mic'], fit: 'contain', align: 'start', maxDp: 1.6 },
    theme: 'auto', bare: true, borders: true, hints: false, preview: false, autoCaps: false, hardKeys: false,
    label: 'The dictation key of Ghost Keyboard',
  });
  const field = kbd.attach(line, { text: SAMPLES[0], follow: false, focusOnClick: false });

  let phase = IDLE, at = 0, by = 'loop', take = null, ms = 0, timer = 0;

  /*
   * The component makes the whole fragment one tab stop that types from a hardware keyboard, and a lone mic key has
   * nothing to type. Here the key itself is the stop: Enter or Space on it is a tap (the component's own click path).
   */
  function dress() {
    const el = kbd.el, key = el.querySelector('.gk-k-mic'), dp = kbd.geometry?.dp;
    el.removeAttribute('tabindex');
    el.removeAttribute('aria-roledescription');
    el.setAttribute('aria-label', kbd.opts.label);
    if (dp) root.style.setProperty('--dictation-dp', dp.toFixed(3) + 'px');     // the line starts where the panel's words do
    if (!key) return;
    key.tabIndex = 0;
    key.setAttribute('aria-label', phase === LISTENING ? 'A sample is playing. Finish the take' : 'Dictate: plays a sample. This page never uses your microphone');
    key.setAttribute('aria-pressed', String(phase === LISTENING));
  }
  kbd.on('layout', dress);
  kbd.on('theme', dress);

  const say = (words) => { if (by === 'visitor') status.textContent = words; };

  function start(who) {
    if (phase !== IDLE) return;
    by = who;
    at = (at + 1) % SAMPLES.length;
    take = spoken(SAMPLES[at]);
    ms = 0;
    phase = LISTENING;
    kbd.setDictation('listening');
    if (kbd.reduced) kbd.setLevel(0.5);                                 // the ring holds still
    dress();
    say('A sample is playing; your microphone is not used. Tap again to finish.');
    line.classList.remove('dictation__field--note');
    run();
  }

  /** The take's clock runs only while it can be seen: off screen, in a hidden tab or with the page's keys paused it is stopped. */
  function run() {
    clearInterval(timer);
    timer = 0;
    if (phase !== IDLE && kbd.visible && !document.hidden) timer = setInterval(tick, TICK);
  }
  kbd.on('visible', run);
  const onVis = () => run();
  document.addEventListener('visibilitychange', onVis);
  // The page's keys paused mid-take: the sample is put away, and the sentence that was there stays.
  kbd.on('motion', ({ paused }) => { if (paused && phase !== IDLE) { take.text = ''; land(true); } });

  /** The take is over: nothing if it was too short to be one, else whisper writes down what it heard. */
  function finish() {
    if (phase !== LISTENING) return;
    const said = take.heard[Math.min(take.heard.length - 1, Math.floor(ms / TICK))];
    let text = ms < SHORTEST ? '' : take.words.slice(0, said).join(' ');
    if (text && said < take.words.length) text = text.replace(/[,.]$/, '') + '.';
    take.text = text;
    if (!text) { land(); return; }
    ms = 0;
    phase = TRANSCRIBING;
    kbd.setDictation('transcribing');
    dress();
    say('Writing it down…');
  }

  /** The words go into the field whole, and the keys are back. */
  function land(quietly = false) {
    clearInterval(timer);
    timer = 0;
    phase = IDLE;
    kbd.setDictation(null);
    kbd.el.querySelector('.gk-dict')?.classList.remove('is-tapped');
    if (quietly) { at = (at + SAMPLES.length - 1) % SAMPLES.length; dress(); return; }
    if (take.over) {
      // Nobody tapped a second time. The phone would still be listening; the sample says so and stops.
      field.set(OVER);
      line.classList.add('dictation__field--note');
      say(OVER);
      at = (at + SAMPLES.length - 1) % SAMPLES.length;
    } else if (take.text) {
      field.set(take.text);
      line.classList.remove('is-new'); void line.offsetWidth; line.classList.add('is-new');
      say(`Dictated: ${take.text}`);
    } else {
      at = (at + SAMPLES.length - 1) % SAMPLES.length;                 // nothing was said: the same sentence next time
      say('Nothing was heard.');
    }
    dress();
  }

  function tick() {
    ms += TICK;
    if (phase === TRANSCRIBING) { if (ms >= THINK) land(); return; }
    const i = ms / TICK, n = take.levels.length;
    if (!kbd.reduced) kbd.setLevel(i < n ? take.levels[i] : 0.03);
    if (by === 'loop') {
      // The loop's own second tap, on the listening panel, as the sentence ends: that is what ends a take.
      if (ms === n * TICK) kbd.el.querySelector('.gk-dict')?.classList.add('is-tapped');
      if (ms >= n * TICK + 200) { kbd.el.querySelector('.gk-dict')?.classList.remove('is-tapped'); finish(); }
    } else if (ms >= n * TICK + QUIET) { take.over = true; land(); }
  }

  // The mic key, or a tap on the listening panel. The loop's own tap of the key is not a visitor's.
  let scripted = false;
  kbd.on('key', (e) => { if (e.type === 'mic') scripted = e.source === 'script'; });
  kbd.on('mic', () => {
    const own = scripted;
    scripted = false;
    if (own) return;
    if (phase === IDLE) start('visitor'); else finish();
  });

  dress();
  kbd.loop(async (s) => {
    while (phase !== IDLE) await s.wait(150);                          // a take a visitor began is still running
    await s.wait(REST);
    if (phase !== IDLE) return;
    const r = kbd.keyRect('mic');
    if (r) await s.tapAt(r.cx, r.cy, { hold: 150 });
    start('loop');
    while (phase !== IDLE) await s.wait(150);
  }, { gap: 0, still: () => {} });                                     // reduced motion: the sentence is there, the key waits

  const destroy = kbd.destroy.bind(kbd);
  kbd.destroy = () => { clearInterval(timer); document.removeEventListener('visibilitychange', onVis); destroy(); };

  return kbd;
}

export default mount;
