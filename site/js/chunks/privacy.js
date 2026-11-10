/*
 * Where a request goes, on the face of a keycap: one line.
 *
 *   six keys  < t l d r >  ─────────▸  the AI you chose
 *
 * The six keys type a request. Nothing travels the line while they do. When the request is closed with >> (and only
 * then) a packet carrying a key goes from the keys straight to "the AI you chose" and comes back. There is no Ghost
 * server in the picture because there is none in the app (AiClient.kt: "straight from the phone to that provider,
 * nothing in between").
 *
 * What the picture says, and where the app says it:
 *   - a request is sent when its closing >> is typed, "Fill as soon as I type >>" being on as installed
 *     (KeyboardPrefs GHOSTWRITER_AUTO), with the person's own key in the request's header (AiClient.kt).
 *   - what goes with it, as installed (Ghostwriter.prompt, Mockingbird.relevant): the request, the text before and
 *     after it in the field, the style profile, the past messages that share its words and the latest few from the
 *     same app, and the Ghost.md notes that match. The key's one line of words says so while the packet is out, so
 *     the picture is never read as "only the request goes".
 *   - the answer comes back on the same connection and replaces the request, typed out (GHOSTWRITER_TYPING).
 *
 * It is a picture of GHOSTWRITER only. Mockingbird sends writing samples to the same AI when it trains, with no
 * request being closed (DevKeyboardService.flushDraft, MOCKINGBIRD_AUTO_TRAIN on as installed); that is said in the
 * page's words and its list of everything that leaves the phone, and the line here speaks of a request, not of
 * "nothing ever".
 *
 * It is a DIAGRAM: nothing is sent from this page and no AI is asked. No answer is made up either; when the packet
 * is back the request is simply taken off the line (the Ghostwriter key shows answers).
 *
 *   <div class="keycap__body" data-chunk="privacy"></div>
 *   <p class="keycap__caption" data-privacy-says>Ghostwriter sends nothing until you close a request with &gt;&gt;.</p>
 *   import { mount } from './chunks/privacy.js';
 *   mount(body)  → the GhostKbd of the six keys
 *
 * The key's own caption (the [data-privacy-says] element on the same face) is the one line of words; without one the
 * chunk writes its own under the picture. Needs css/chunks/privacy.css beside site.css and kbd.css.
 */
import { GhostKbd, icon } from '../kbd.js';

const ASK = 'tldr';                                   // from Ghostwriter.kt's own example, "engineering standup tldr"
// Plain keys, without the long-press alternatives the board's own < and > carry: a panel has no room on a keycap.
const key = (text) => ({ type: 'char', text });
const KEYS = ['<', ...ASK, '>'].map(key);

// Ghostwriter.CLOSED: a request just closed before the cursor.
const CLOSED = /<<(?=[^<>\n]*\p{L})([^<>\n]{2,400})>>$/u;
// The request picked out in the accent. That is this page's emphasis: the keyboard does not colour an app's text.
const MARKS = [{ re: /<<[^<>\n]*(?:>>)?/, cls: 'privacy__tag' }];

// The one line of words. Each is true of the app as installed (see the head of this file).
export const SAYS = {
  rest: 'Ghostwriter sends nothing until you close a request with >>.',
  sent: 'Sent to your AI: the request, the text around it, your style profile, recent and matching messages, matching notes.',
  back: 'The answer comes back the same way, and is typed over the request.',
};

const OUT = 900;        // the packet, keys to the AI, and the same back
const THINK = 700;      // at the AI: on the phone, as long as the provider takes
const HOLD = 2600;      // "the answer comes back…" stays this long

export function mount(root) {
  root.classList.add('privacy');
  root.innerHTML =
    '<div class="privacy__flow" role="group" aria-label="Where a request goes: from these keys straight to the AI you chose, once it is closed">' +
      '<div class="privacy__typing">' +
        '<span class="gk-vh">Typed so far: </span><div class="privacy__line"></div>' +
        '<div class="privacy__keys"></div>' +
      '</div>' +
      '<div class="privacy__wire" aria-hidden="true"><i class="privacy__tip"></i></div>' +
      '<div class="privacy__ai">the AI you chose</div>' +
    '</div>' +
    '<span class="gk-vh" role="status"></span>';

  const $ = (sel) => root.querySelector(sel);
  const wire = $('.privacy__wire'), ai = $('.privacy__ai'), status = root.lastChild;
  let says = root.closest('.keycap__face')?.querySelector('[data-privacy-says]') || null;
  if (!says) { says = document.createElement('p'); says.className = 'privacy__says'; root.insertBefore(says, status); }

  const kbd = new GhostKbd($('.privacy__keys'), {
    fragment: { keys: KEYS, minDp: 0.86, maxDp: 1.1 },
    theme: 'auto', bare: true, borders: true, hints: false, preview: false, autoCaps: false, hardKeys: 'shown',
    label: 'Six keys of Ghost Keyboard: a request goes between << and >>',
  });
  const field = kbd.attach($('.privacy__line'), { marks: MARKS, focusOnClick: false });

  let flight = 0;         // which request is out; a newer one ends an older one's steps
  let busy = false;       // a request is out (the app starts no second one meanwhile: aiJob.running)

  const say = (what) => { says.textContent = SAYS[what]; root.dataset.says = what; };
  const nap = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  /** The packet with the key in it travels the line, which is lit while it does. */
  function travel(ms, back = false) {
    wire.classList.add('is-live');
    clearTimeout(wire._dim);
    wire._dim = setTimeout(() => wire.classList.remove('is-live'), ms + 80);
    if (kbd.reduced) return;
    const dot = document.createElement('i');
    dot.className = `privacy__dot${back ? ' privacy__dot--back' : ''}`;
    dot.style.setProperty('--privacy-ms', `${ms}ms`);
    dot.innerHTML = icon('key');
    wire.append(dot);
    setTimeout(() => dot.remove(), ms + 60);
  }

  /** Nothing on the line, and nothing waited for. */
  function ground() {
    ++flight;
    busy = false;
    ai.classList.remove('is-asked');
    for (const dot of wire.querySelectorAll('.privacy__dot')) dot.remove();
    clearTimeout(wire._dim);
    wire.classList.remove('is-live');
  }

  /** A request closed: out to the AI with the key, and back. */
  async function request(tag, by) {
    const my = ++flight;
    busy = true;
    say('sent');
    if (by !== 'script') status.textContent = `${SAYS.sent} It goes from your phone straight to the AI you chose, with your key.`;
    travel(OUT);
    await nap(OUT);
    if (my !== flight) return;
    ai.classList.add('is-asked');
    wire.classList.add('is-live');
    clearTimeout(wire._dim);
    await nap(THINK);
    if (my !== flight) return;
    ai.classList.remove('is-asked');
    travel(OUT, true);
    await nap(OUT);
    if (my !== flight) return;
    // The answer replaces the request. Here the request is only taken away: no AI was asked, and none is made up.
    const at = field.text.lastIndexOf(tag);
    if (at >= 0) field.replace(at, at + tag.length, '');
    say('back');
    if (by !== 'script') status.textContent = SAYS.back;
    await nap(HOLD);
    if (my !== flight) return;
    busy = false;
    say('rest');
  }

  // The closing >> is the request ("Fill as soon as I type >>" is on as installed).
  let by = 'script';
  kbd.on('user', () => { by = 'visitor'; });
  kbd.on('input', (e) => {
    if (e.why !== 'insert' || busy) return;
    const m = CLOSED.exec(field.before);
    if (m) request(m[0], by);
  });
  // The page's keys paused: a packet on its way is put away with the rest.
  kbd.on('motion', ({ paused }) => { if (paused) { ground(); say('rest'); } });

  say('rest');
  kbd.loop(async (s) => {
    by = 'script';
    ground();
    say('rest');
    s.field.clear();
    await s.wait(900);
    await s.type(`<<${ASK}`, { wpm: 110, seed: 11 });
    await s.type('>>', { wpm: 300, humanize: false });        // the same key twice, quickly
    await s.wait(OUT + THINK + OUT + HOLD);
  }, {
    gap: 500,
    // With reduced motion nothing plays: an open request stands on the line, and the two > are the visitor's to press.
    still: () => { ground(); field.set(`<<${ASK}`); say('rest'); },
  });

  return kbd;
}

export default mount;
