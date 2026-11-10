// Swipe typing, on the face of a key: the top row of letters, a finger's trail drawing a word along it, the word's
// letters lighting in turn, and the word landing on the line above. Draw your own and it is matched as the app
// matches it (Autocorrect.swipe), against the top row's own words (below) in place of the phone's whole dictionary;
// the other guesses are shown beside the word, as the app's row keeps them.
//
//   <article class="keycap keycap--3u"> … <div class="keycap__body" data-chunk="swipe"></div> … </article>
//   import { mount } from './chunks/swipe.js';  mount(body)  → the GhostKbd
//
// Needs css/chunks/swipe.css beside site.css and kbd.css.

import { GhostKbd } from '../kbd.js';

const ROW = [...'qwertyuiop'];
// What the loop draws: each is the first word the app's decoder gives for its exact path (key centre to key centre)
// with the whole English dictionary and all three rows of keys. Checked by running Autocorrect.swipe's sums over en.tsv.
const PLAYED = ['write', 'your', 'poetry'];

// Every word in the app's English list that is spelled with the top row alone (assets/dict/en.tsv: 461 of its 40,000,
// two letters or more), each with its frequency (Zipf) as the list has it. With only the top row on the key these are
// the words a path along it can mean on the phone too: a letter of any other word lies more than 1.25 keys from the
// row's middle line, which is as far as Autocorrect.swipe looks. A path that dips to the row's lower edge would, on
// the phone, also meet words with letters from the row below; the key's caption says the phone has the whole list.
const WORDS = (
  'to 7.43 you 6.98 it 6.95 or 6.54 we 6.54 your 6.53 up 6.39 out 6.38 were 6.34 our 6.14 two 6.10 too 5.95 ' +
  'put 5.66 top 5.57 yet 5.54 power 5.52 try 5.50 per 5.45 true 5.40 pretty 5.38 report 5.32 quite 5.29 ' +
  'type 5.27 re 5.23 property 5.11 poor 5.09 europe 5.04 wrote 5.04 write 5.03 eye 5.02 ii 5.02 tour 4.99 ' +
  'wow 4.96 pre 4.95 pro 4.94 trip 4.90 peter 4.88 pop 4.87 prior 4.85 tree 4.85 worry 4.84 twitter 4.83 ' +
  'require 4.78 port 4.73 proper 4.72 upper 4.72 writer 4.69 route 4.67 et 4.66 quiet 4.65 pure 4.64 iii 4.63 ' +
  'eu 4.62 row 4.60 territory 4.60 error 4.55 tip 4.55 quit 4.54 wet 4.54 quote 4.51 tie 4.51 tower 4.50 ' +
  'pet 4.45 yo 4.45 output 4.43 wire 4.43 op 4.42 priority 4.41 root 4.41 poetry 4.40 pot 4.40 yep 4.39 ' +
  'pope 4.38 pp 4.38 rep 4.35 reporter 4.35 rio 4.35 equity 4.30 toy 4.30 wore 4.29 pit 4.27 rip 4.26 ' +
  'terror 4.26 outer 4.25 pipe 4.25 roy 4.25 ye 4.25 er 4.24 terry 4.24 perry 4.22 ep 4.21 poet 4.21 pie 4.19 ' +
  'pr 4.19 tweet 4.17 tier 4.16 torture 4.15 peer 4.14 pepper 4.14 owe 4.13 pete 4.12 potter 4.11 ore 4.10 ' +
  'pour 4.10 euro 4.09 riot 4.09 wi 4.09 petty 4.07 te 4.07 toe 4.07 rope 4.06 yup 4.06 retire 4.05 ip 4.04 ' +
  'pt 4.04 puerto 4.04 rt 4.04 tire 4.02 wipe 4.01 puppy 4.00 pity 3.98 porter 3.98 prey 3.98 po 3.97 wwe 3.97 ' +
  'pee 3.96 wit 3.96 pierre 3.95 troy 3.95 prep 3.94 trio 3.94 pi 3.92 yu 3.92 ur 3.91 opt 3.90 tory 3.90 ' +
  'peru 3.89 wee 3.87 woo 3.87 youre 3.86 ot 3.85 ti 3.85 utter 3.85 que 3.84 prototype 3.83 pier 3.82 tee 3.82 ' +
  'wu 3.81 ie 3.80 puppet 3.80 rey 3.79 yr 3.79 pitt 3.78 queue 3.78 quo 3.78 trout 3.78 ou 3.77 prop 3.77 ' +
  'queer 3.76 tu 3.76 retro 3.73 tr 3.73 ty 3.73 pe 3.72 rory 3.72 tow 3.72 ut 3.72 oi 3.71 tore 3.71 tri 3.71 ' +
  'pottery 3.70 rot 3.70 wo 3.70 iq 3.69 purity 3.69 ri 3.69 troop 3.69 ir 3.68 piper 3.68 wii 3.68 otto 3.67 ' +
  'ripe 3.67 rp 3.67 tutor 3.67 poop 3.66 wr 3.66 yi 3.66 torque 3.65 ow 3.64 pup 3.64 query 3.64 rite 3.63 ' +
  'ro 3.62 ui 3.62 yuri 3.62 ee 3.61 rupert 3.61 tt 3.61 wwii 3.61 poorer 3.60 poppy 3.59 ew 3.58 erie 3.57 ' +
  'oo 3.57 tor 3.57 rue 3.56 witty 3.56 poe 3.55 poo 3.54 ieee 3.52 pep 3.52 rye 3.52 weir 3.52 iot 3.51 ' +
  'porto 3.51 tyre 3.51 equip 3.50 por 3.50 router 3.50 ru 3.50 eur 3.49 tit 3.48 yoo 3.48 rewrite 3.47 ' +
  'wei 3.46 ooo 3.44 pow 3.44 pu 3.44 roe 3.44 petite 3.43 repertoire 3.43 tp 3.43 trey 3.43 rotor 3.42 ' +
  'trooper 3.42 turret 3.42 io 3.41 pew 3.41 etiquette 3.40 pepe 3.40 rr 3.40 err 3.39 peep 3.39 roi 3.39 ' +
  'ipo 3.37 otter 3.37 weep 3.37 www 3.37 wt 3.36 yer 3.36 pip 3.35 rowe 3.35 quieter 3.34 wp 3.34 eerie 3.33 ' +
  'ww 3.33 eq 3.32 prettier 3.32 qui 3.32 proprietor 3.31 woe 3.31 typo 3.30 writ 3.30 ey 3.29 iu 3.29 ppp 3.29 ' +
  'qi 3.29 tito 3.29 pore 3.28 qu 3.28 rupture 3.28 yee 3.28 orr 3.27 petit 3.27 tote 3.27 potty 3.26 ter 3.26 ' +
  'typewriter 3.26 uri 3.26 troupe 3.25 terrier 3.24 toro 3.24 tpp 3.24 tue 3.24 wept 3.24 proto 3.23 ' +
  'ripper 3.23 ryu 3.23 uw 3.23 wto 3.23 ire 3.22 pty 3.22 terri 3.22 qt 3.21 trot 3.21 upto 3.21 yt 3.21 ' +
  'tori 3.19 ret 3.18 tout 3.18 ei 3.16 ito 3.16 pryor 3.16 tot 3.16 putt 3.15 toto 3.15 wwi 3.15 ere 3.14 ' +
  'eyre 3.13 porte 3.13 qpr 3.13 rw 3.13 rei 3.12 tw 3.12 ott 3.11 oy 3.11 rower 3.11 rut 3.11 topper 3.11 ' +
  'tre 3.11 eo 3.10 itt 3.10 repo 3.10 roo 3.10 tort 3.10 trope 3.10 piety 3.09 pri 3.09 qr 3.09 quot 3.09 ' +
  'rout 3.09 pry 3.08 roper 3.07 terre 3.07 epi 3.06 pw 3.06 rupee 3.04 ue 3.04 pietro 3.03 priory 3.03 ' +
  'toot 3.03 tru 3.03 ute 3.03 wot 3.03 yew 3.03 erp 3.02 tutu 3.02 oop 3.01 piero 3.01 titty 3.01 wut 3.01 ' +
  'yeo 3.01 yeti 3.01 pei 3.00 upi 3.00 peri 2.99 ritter 2.99 teri 2.99 ori 2.98 petri 2.98 repertory 2.98 ' +
  'erupt 2.97 petrie 2.97 ry 2.97 truer 2.97 oreo 2.96 pte 2.96 witt 2.96 oe 2.95 popper 2.95 petro 2.94 ' +
  'tut 2.94 pique 2.93 ppi 2.93 toi 2.92 trotter 2.92 wie 2.92 iit 2.91 oro 2.91 petr 2.91 putter 2.91 ' +
  'rote 2.91 wetter 2.91 itu 2.90 perpetuity 2.90 pq 2.90 iw 2.89 poirot 2.89 putty 2.89 retort 2.89 torre 2.89 ' +
  'torrey 2.89 pti 2.88 tou 2.88 tripp 2.88 wort 2.88 wy 2.88 eww 2.87 pruitt 2.87 pur 2.87 puree 2.87 wer 2.87 ' +
  'wop 2.87 rui 2.86 trippy 2.86 tryout 2.86 wry 2.86 epp 2.85 qe 2.85 trier 2.85 opp 2.84 ure 2.84 poi 2.83 ' +
  'popeye 2.83 pout 2.83 quito 2.83 toure 2.83 wor 2.83 pto 2.82 puri 2.82 rte 2.82 yip 2.82 pero 2.81 ' +
  'retiree 2.81 tet 2.81 wiper 2.81 outro 2.80 ree 2.80 ryo 2.80 trite 2.80 ewe 2.79 propriety 2.79 trp 2.79 ' +
  'truro 2.79 wip 2.79 yoy 2.79 pewter 2.78 popup 2.78 priori 2.78 rpi 2.78 yue 2.78 opie 2.77 ppe 2.77 ' +
  'trott 2.77 tui 2.77 wye 2.77 yui 2.77 req 2.76 tur 2.76 pieter 2.75 purr 2.75 rewrote 2.75 uo 2.75 qq 2.74 ' +
  'twit 2.74 uti 2.74 wipeout 2.74 pere 2.73 retweet 2.73 tye 2.73 yow 2.73 '
).trim().split(' ').reduce((list, s, i, all) => (i % 2 ? list : [...list, [s, +all[i + 1]]]), []);

const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);

/** `pts` as `n` points evenly spaced along its length (Autocorrect.resample). */
function resample(pts, n) {
  let total = 0;
  for (let i = 1; i < pts.length; i++) total += dist(pts[i - 1], pts[i]);
  if (total <= 0) return Array(n).fill(pts[0]);
  const step = total / (n - 1), out = [pts[0]];
  let acc = 0, i = 1, prev = pts[0];
  while (i < pts.length && out.length < n) {
    const cur = pts[i], d = dist(prev, cur);
    if (acc + d >= step && d > 0) {
      const t = (step - acc) / d;
      prev = [prev[0] + t * (cur[0] - prev[0]), prev[1] + t * (cur[1] - prev[1])];
      out.push(prev);
      acc = 0;
    } else { acc += d; prev = cur; i++; }
  }
  while (out.length < n) out.push(pts[pts.length - 1]);
  return out;
}

/**
 * The likeliest words for a finger's `path` over the letters, best first, as Autocorrect.swipe finds them: a word
 * starts near where the finger came down and ends near where it lifted (1.1 keys), every letter of it passes within
 * 1.25 keys of the path in order, and its ideal path's mean distance from the finger's, in key widths, is weighed
 * against how common the word is.
 */
export function decode(path, keys, keyW, words = WORDS) {
  if (path.length < 2 || !(keyW > 0)) return [];
  const n = 40, user = resample(path, n);
  const near = (p) => Object.entries(keys).map(([c, k]) => [c, dist(p, k) / keyW]).filter((e) => e[1] <= 1.1).sort((a, b) => a[1] - b[1]).slice(0, 4).map((e) => e[0]);
  const starts = near(user[0]), ends = near(user[n - 1]);
  const scored = [];
  for (const [word, zipf] of words) {
    if (!starts.includes(word[0]) || !ends.includes(word[word.length - 1])) continue;
    // The word's keys, a doubled letter once ("hello" glides h-e-l-o).
    const pts = [...word].filter((c, i) => c !== word[i - 1]).map((c) => keys[c]);
    if (pts.some((p) => !p)) continue;
    let at = 0;
    if (!pts.every((p) => { while (at < n && dist(user[at], p) > keyW * 1.25) at++; return at < n; })) continue;
    const ideal = resample(pts, n);
    let d = 0;
    for (let i = 0; i < n; i++) d += dist(user[i], ideal[i]);
    scored.push([word, zipf * 0.55 - (d / (n * keyW)) * 6]);
  }
  return scored.sort((a, b) => b[1] - a[1]).map((s) => s[0]);
}

export function mount(root) {
  root.classList.add('swipe');
  root.innerHTML =
    '<div class="swipe__top"><span class="gk-vh">Swiped so far: </span><div class="keycap__field swipe__line"></div>' +
    '<span class="swipe__more" aria-hidden="true"></span>' +
    '<span class="swipe__note" role="status"></span></div><div class="keycap__keys swipe__keys"></div>';
  const note = root.querySelector('.swipe__note'), more = root.querySelector('.swipe__more');
  const kbd = new GhostKbd(root.lastChild, {
    // One row, never so small that a key is under a finger's height on a phone (minDp keeps the row 44px tall there).
    fragment: { keys: ROW, fit: 'contain', minDp: 0.74, maxDp: 1.25 },
    theme: 'auto', bare: true, borders: true, hints: false, preview: false, autoCaps: false, swipe: true,
    longPressMs: 2 ** 31 - 1,                                           // no long-press panels: the face would clip them
    label: 'The top row of Ghost Keyboard’s letters. Draw a word along them',
  });
  const field = kbd.attach(root.querySelector('.swipe__line'));
  /** The decoder's other guesses, beside the word, as the app's row keeps them. */
  const others = (list) => { more.textContent = list.length ? `or ${list.join(', ')}` : ''; };
  const keysEl = kbd.el.querySelector('.gk-keys');

  let mine = false;       // the line holds the visitor's words, not the loop's
  let last = '';          // the word just swiped with the space put before it, while backspace would take it whole
  let noted = 0;
  const say = (text) => {                                             // in the line's place, for a moment
    clearTimeout(noted);
    note.textContent = text;
    root.classList.toggle('swipe--noted', !!text);
    if (text) noted = setTimeout(say, 2600, '');
  };
  // A page scrolls through the keys until a visitor has used them; from then, and for five seconds after they last
  // did, a finger that starts on a letter draws (touch-action is read when the next touch begins). A finger or a
  // mouse leaves the focus on the keys, where the loop would wait for ever, so the rest takes that away too; someone
  // typing on their own keyboard keeps it (and the focus ring, which a finger's focus goes without).
  let idle = 0, pointed = false;
  const point = (on) => { pointed = on; root.classList.toggle('swipe--pointed', on); };
  const hold = (on) => { kbd.opts.touchAction = on ? 'none' : 'pan-y'; if (keysEl) keysEl.style.touchAction = kbd.opts.touchAction; };
  const rest = () => { hold(false); if (pointed && document.activeElement === kbd.el) kbd.el.blur(); };
  const arm = () => { clearTimeout(idle); if (kbd.opts.touchAction === 'none') idle = setTimeout(rest, 5000); };
  const takeOver = () => { if (!mine) { mine = true; field.clear(); } hold(true); arm(); say(''); others([]); };
  kbd.el.addEventListener('pointerdown', () => { point(true); clearTimeout(idle); });
  kbd.el.addEventListener('blur', () => point(false));
  for (const type of ['pointerup', 'pointercancel']) kbd.el.addEventListener(type, arm);

  kbd.on('swipe', ({ source, path, keyWidth }) => {
    if (source !== 'pointer') return;
    takeOver();
    const keys = {};
    for (const k of kbd.geometry.keys) if (k.type === 'char') keys[k.char] = [k.cx, k.cy];
    const [word, ...alts] = decode(path.map((p) => [p.x, p.y]), keys, keyWidth);
    if (!word) { say('No top-row word fits that path'); return; }
    // The space the app puts before a swiped word, and its letters lit in turn (DevKeyboardService.onSwipe).
    const b = field.before.slice(-1);
    last = (b && !/\s/.test(b) && !'([{"\'`'.includes(b) ? ' ' : '') + word;
    field.insert(last);
    others(alts.slice(0, 2));
    kbd.lightWord(word);
  });
  kbd.on('key', (ev) => {
    if (ev.source === 'script' || ev.source === 'ghost') return;
    if (ev.source === 'keyboard') point(false);
    takeOver();
    const was = last;
    last = '';
    if (!was) return;
    // Straight after a swiped word a letter starts a new one, and backspace takes the whole word.
    if (ev.type === 'char' && /^[\p{L}\p{N}]$/u.test(ev.char)) field.insert(' ');
    else if (ev.type === 'delete' && field.before.endsWith(was)) { ev.preventDefault(); field.backspace(was.length); }
  });

  kbd.loop(async (s) => {
    s.field.clear();
    await s.wait(700);
    for (const word of PLAYED) {
      await s.swipe(word, { ms: 300 + word.length * 120 });
      await s.wait(1000);
    }
    await s.wait(1600);
  }, {
    still: (k) => { if (!mine) k.field.set(PLAYED.join(' ')); },
    onResume: () => { mine = false; last = ''; hold(false); say(''); others([]); },
  });
  return kbd;
}

export default mount;
