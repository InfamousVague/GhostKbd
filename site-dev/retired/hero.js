// RETIRED from the site on 2026-11-09: the page's hero no longer holds a letter board (the owner's direction: a
// marketing page with small chunks, not a demo of the keyboard). Kept here for the bench page beside it.

/*
 * The hero chunk: the letters of Ghost Keyboard with the row above them, on the face of the page's widest keycap, and
 * the line of text they type into.
 *
 *   <div class="keycap__body" data-chunk="hero"></div>       mount(root) fills it; css/chunks/hero.css sizes it
 *
 * Left alone it types a message with a slip in it, which autocorrect fixes on the space, then <<standup tldr>>, which
 * Ghostwriter replaces, typed out key by key with the keys lighting. A visitor's tap on a key, a suggestion or the
 * line, or a key of their own keyboard once it has the focus, stops the loop and the keys are theirs; it starts again
 * six seconds after they leave it alone.
 *
 * What it does is the app's (DevKeyboardService.kt, Autocorrect.kt, Ghostwriter.kt), by the app's own rules:
 *   - space or . , ! ? after a word runs autocorrect on it; backspace straight after puts back what was typed, and so
 *     does the word in quotes in the row, and a word put back is kept and not fixed again;
 *   - the row: the typed word in quotes when it is not a word, the fix or the word in bold, then the commonest words
 *     it begins; three or more, and the bold one sits in the middle; a tap types the word and a space;
 *   - <<request>>: "✦ Fill: …" while it is open, filled as soon as >> closes it (or on Enter, or a tap on Fill),
 *     "✦ Writing “…”…", then "✦ Typing…" with Skip; any key while it types writes the rest at once.
 *
 * What this page cannot carry is said plainly rather than faked. The app's dictionary is 40,000 words a language;
 * here are its 600 commonest, less four no hero should offer (so the completions are the app's own, as far as they
 * go), and in APP the app's answer for a few slips and for the words the loop types, each taken from Autocorrect.kt
 * run over the app's own en.tsv. A word this page does not know is left alone. And there is no AI here: the answer to <<standup tldr>> is a sample,
 * and any other request gets a chip in the row saying so.
 */

import { GhostKbd, typeInto } from '../../site/js/kbd.js';

// --- the words ------------------------------------------------------------------------------------------------------

/** assets/dict/en.tsv's commonest words, commonest first (without shit, fuck, fucking and sex). */
const WORDS = `
the to and of a in i is for that you it on with this was be as are have at he not by but from my or we an your all
so his they me if one can will just like about up out what has when more do no were who had it's their there her
which time get been would she new people how don't some also them now other i'm its our than good only after first
him into know see two make over think any then could back these us want because go well said way most much very
where even should may here need really did right work year years being day too going before off why made still take
got many never those life say world down great through you're last s that's while best such love man home long look
something use can't same used both every am come part state three around between always better find help high little
old since another does own things under during game i've thing give house place school again next each mr without
against didn't end found must show big feel sure team ever family keep might please put money free second someone
away left number city days lot name night play until company doing few he's let real called different having set
thought done however getting god government group looking public top women business care start system times week
already anything case nothing person today change enough everything full live making point read there's told yet bad
doesn't four hard mean once support tell including music power seen states stop water based believe call head men
national small took white came far job side though try went yes actually american later less line order party run
says service country open season thank children everyone general they're trying united using area black d following
law makes together war whole car face five kind maybe per president story working course games health hope important
least means news within able book early friends i'll information local oh post t thanks video young ago others
social talk court fact given guys half hand isn't level mind often single become body coming control death food guy
hours office pay problem south true we're almost history known large lost m research room several started taking
university win wrong along anyone else girl john matter pretty remember air bit friend hit needs nice playing
probably saying understand yeah york class close comes i'd idea international looks past possible wanted b cause due
happy human members months move question r series wait woman ask community data late leave north saw special watch
won't c either future light low million morning police short stay taken age buy deal rather reason red report soon
third turn whether among check development form further heart minutes myself services yourself act although asked
child fire fun living major media phone players art behind building easy gonna market near non plan political quite
six talking west works according available e education final former front kids list ready sometimes son street
wasn't bring college current example experience heard london meet program type baby chance father march process
she's song study word across action clear gave gets himself month outside self students words board cost cut dr
field held instead main moment mother road seems thinking town wants de department energy fight fine force hear
issue played points price re rest results running shows space summer term wife america beautiful date goes killed
land miss project
`.trim().split(/\s+/);
const TOP = new Set(WORDS);

/** assets/dict/en_contractions.tsv: dont → don't. Each is looked up by its spelling without the apostrophe. */
const CONTRACTIONS = new Map(`
ain't aren't can't couldn't could've didn't doesn't don't hadn't hasn't haven't he'd here's he's how'd how's i'd
i'll i'm isn't it'd it'll i've might've must've she's shouldn't should've that'd that'll that's there'd there'll
there's they'd they'll they're they've wasn't weren't we've what's when's where'd where's who'd who'll who's who've
why'd why's won't wouldn't would've y'all you'd you'll you're you've
`.trim().split(/\s+/).map((w) => [w.replace(/'/g, ''), w]));

/** Autocorrect.suggest's answer where this page's 600 words would get it wrong: typed | is a word | the fix | the row. */
const APP = new Map(`
teh|1|the|the teh tehran
hte|0|the|the
tge|0|the|the
adn|0|and|and adnan
waht|0|what|what
whst|0|what|what
taht|0|that|that
wiht|0|with|with
jsut|0|just|just
yuo|0|you|you
yiu|0|you|you
thsi|0|this|this
knwo|0|know|know
liek|0|like|like
abotu|0|about|about
becuase|0|because|because
thier|1|their|their thier thierry
recieve|1|receive|receive recieve recieved
freind|0|friend|friend
wierd|0|weird|weird
untill|1|until|until untill
tomorow|0|tomorrow|tomorrow
helko|0|hello|hello
wirk|0|work|work
keybkard|0|keyboard|keyboard
th|1||th the that this they
ther|1|the|the ther there there's therefore
there|1||there there's therefore thereby thereafter
te|1||te team tell term test
ten|1||ten tend tennis tension tennessee
mi|1||mi might mind million minutes
min|1||min mind minutes mine minister
minu|0|mini|mini minutes minute minus minuscule
minut|0|minute|minute minutes
minutw|0|minute|minute
minutws|0|minutes|minutes
`.trim().split('\n').map((line) => { const [typed, word, fix, out] = line.split('|'); return [typed, { word: word === '1', fix: fix || null, out: out.split(' ') }]; }));

/** What Ghostwriter writes here: samples, since the page has no AI. On the phone the person's own AI writes it. */
const SAMPLES = [
  [/stand-?up|tl;?dr/i, 'Shipped waitlist events and the sync fix. Docs today.'],
  [/declin/i, 'Thanks for asking, but I can’t make this one. Next time?'],
];

const CLOSED = /<<(?=[^<>\n]*\p{L})([^<>\n]{2,400})>>$/u;       // Ghostwriter.CLOSED
const FILL_OPEN = /<<([^<>\n]{0,400})$/;                        // DevKeyboardService.FILL_OPEN
const WORD_ENDS = '.,!?';

const capitalI = (w) => (w === 'i' || w.startsWith("i'") ? 'I' + w.slice(1) : w);
/** The typed word's capitals on another word: all, first, or none (Autocorrect.recase). */
const recase = (typed, w) => (typed.length > 1 && typed === typed.toUpperCase() ? w.toUpperCase()
  : typed[0] !== typed[0].toLowerCase() ? w[0].toUpperCase() + w.slice(1) : w);
/** A request as a chip shows it: one line, cut short. */
const preview = (text) => { const line = text.replace(/\s+/g, ' ').trim(); return line.length > 28 ? line.slice(0, 27) + '…' : line; };

// --- the chunk ------------------------------------------------------------------------------------------------------

/**
 * Fills `root` (the keycap's .keycap__body) with the line and the keys, and starts the loop. Returns the GhostKbd.
 * `opts` go to the keyboard as they are ({borders: true} gives every key a face).
 */
export function mount(root, opts = {}) {
  root.classList.add('hero-chunk');
  root.innerHTML = '<div class="hero-chunk__line"><div class="hero-chunk__text"></div></div><div class="hero-chunk__keys"></div>';
  const kbd = new GhostKbd(root.lastChild, {
    // The three rows of letters under the row above the keys: 44 + 3 x 60 + 2 = 226dp tall, which hero.css allows for.
    fragment: { rows: [0, 1, 2], fit: 'contain', maxDp: 1.3 },
    row: true, theme: 'auto', bare: true, enter: 'fill',
    label: 'The letters of Ghost Keyboard, and the row above them',
    ...opts,
  });

  let fix = null;                 // the last autocorrection: {typed, fixed, sep} (lastCorrection)
  let job = null;                 // a <<request>> being written: {request, tag, none}
  let typing = null;              // its answer being typed out: {text, at}
  let playing = false;            // the loop has the keys
  const kept = new Set();         // words put back after a fix: not fixed again (learned words)
  const f = kbd.attach(root.firstChild.firstChild, { placeholder: 'Message' });

  /** What the app's dictionary says of a word. `word` is null where this page cannot say. */
  function look(lower) {
    const mine = kept.has(lower);
    if (!mine && APP.has(lower)) return APP.get(lower);
    const con = CONTRACTIONS.get(lower);
    const fixed = mine ? null : lower === 'i' ? 'I' : con ? capitalI(con) : null;
    const word = mine || TOP.has(lower) || !!con;
    const out = new Set(fixed ? [fixed] : []);
    if (word) out.add(capitalI(con || lower));
    for (const w of WORDS) { if (out.size >= 5) break; if (w !== lower && w.startsWith(lower)) out.add(capitalI(w)); }
    return { word: word || null, fix: fixed, out: [...out] };
  }

  /** The word being typed: the letters and apostrophes before the cursor, unless they are part of something code-shaped. */
  function current() {
    const m = /[\p{L}']+$/u.exec(f.before);
    if (!m || !/\p{L}/u.test(m[0])) return '';
    const prev = f.before[m.index - 1];
    if (prev && /[\p{N}@#/._\\:$`-]/u.test(prev)) return '';
    return m[0].replace(/^'+/, '');
  }

  /** The word before the cursor, corrected and followed by `sep`, if there is a better one. True when it typed them. */
  function correctLastWord(sep) {
    const typed = current();
    // camelCase, iOS: a capital after the first letter is deliberate. In the middle of a word: it goes on, leave it.
    if (!typed || typed.length > 24 || /\p{Lu}/u.test(typed.slice(1)) || /^[\p{L}\p{N}']/u.test(f.after)) return false;
    const found = look(typed.toLowerCase()).fix;
    const fixed = found && recase(typed, found);
    if (!fixed || fixed === typed) return false;
    fix = { typed, fixed, sep };
    f.replace(f.caret - typed.length, f.caret, fixed + sep);
    return true;
  }

  /** Puts back the word a fix replaced, and keeps it. False when the fix is no longer just before the cursor. */
  function undo(keepSep) {
    const c = fix;
    fix = null;
    const tail = c ? c.fixed + c.sep : '';
    if (!c || !f.before.endsWith(tail)) return false;
    kept.add(c.typed.toLowerCase());
    f.replace(f.caret - tail.length, f.caret, keepSep ? c.typed + c.sep : c.typed);
    return true;
  }

  /** The request the cursor is on, or just after (Ghostwriter.tagAt). */
  function tagAt() {
    for (const m of f.text.matchAll(/<<(?=[^<>\n]*\p{L})([^<>\n]{2,400})>>/gu)) {
      if (f.caret > m.index && f.caret <= m.index + m[0].length) return { request: m[1].trim(), tag: m[0] };
    }
    return null;
  }

  /** Writes the rest of an answer at once: Skip, or any key while it types. */
  function skip() {
    const t = typing;
    if (!t) return;
    typing = null;
    kbd.cancel();
    const got = f.text.slice(t.at, f.caret);
    if (t.text.startsWith(got)) f.insert(t.text.slice(got.length));
    refresh();
  }

  /** Fills the request the cursor is on. `step` is the loop's own steps while it plays, else the keyboard. */
  async function fill(step = kbd) {
    const t = tagAt();
    if (!t) return;
    const text = SAMPLES.find(([re]) => re.test(t.request))?.[1];
    const mine = (job = { ...t, none: !text });
    refresh();
    if (!text) return;
    await step.wait(900);
    if (job !== mine) return;
    job = null;
    // The last copy of the tag that starts before the cursor, the cursor in it or at its end: typed on past, it stays.
    const at = f.text.lastIndexOf(t.tag, f.caret - 1);
    if (at < 0 || f.caret <= at || at + t.tag.length < f.caret) { refresh(); return; }
    const now = (typing = { text, at });
    f.replace(at, at + t.tag.length, '');
    await step.ghostType(text);
    if (typing === now) { typing = null; refresh(); }
  }

  /** The row above the keys, the first that applies (DevKeyboardService.refreshRow). */
  function refresh() {
    const items = [];
    const chip = (id, label, onTap, bold = false) => items.push({ id, label, chip: true, fill: true, bold, onTap, disabled: !onTap });
    const wide = (id, label, onTap, bold = false) => items.push({ id, label, wide: true, bold, onTap });
    const tag = tagAt(), open = FILL_OPEN.exec(f.before), word = current();
    if (typing) {
      chip('ai-typing', '✦ Typing…');
      items.push({ id: 'ai-skip', label: 'Skip', chip: true, bold: true, onTap: skip });
    } else if (job) {
      // The one line here that is the page's and not the app's: there is no AI behind this page.
      if (job.none) chip('ai-none', '✦ No AI on this page. Try <<standup tldr>>', () => {
        const { tag: old } = job, at = f.text.lastIndexOf(old);
        job = null;
        if (at >= 0) { f.replace(at, at + old.length, '<<standup tldr>>'); fill(); } else refresh();
      });
      else chip(`ai-run-${job.request}`, `✦ Writing “${preview(job.request)}”…`);
      items.push({ id: 'ai-x', label: '✕', ariaLabel: 'Dismiss', chip: true, onTap: () => { job = null; refresh(); } });
    } else if (tag) chip(`fill-${tag.request}`, `✦ Fill: ${preview(tag.request)}`, () => fill(), true);
    else if (open) {
      const request = open[1].trim();
      chip('fill-open', request.length < 2 ? '✦ Ghostwriter: say what to write, then >>' : `✦ Fill: ${preview(request)}`, () => { if (request.length >= 2) f.insert('>>'); });
    } else if (fix) {
      wide(`keep-${fix.typed}`, `“${fix.typed}”`, () => undo(true));
      wide(`fixed-${fix.fixed}`, fix.fixed, () => { fix = null; refresh(); }, true);
    } else if (word) {
      const s = look(word.toLowerCase());
      if (s.word === false) wide(`typed-${word}`, `“${word}”`, () => { kept.add(word.toLowerCase()); f.insert(' '); });
      s.out.forEach((w, i) => {
        const shown = recase(word, w);
        // A suggestion tapped replaces the word being typed, and a space follows.
        wide(`s-${i}-${shown}`, shown, () => { if (current() === word) f.replace(f.caret - word.length, f.caret, shown + ' '); }, i === 0 && !!(s.fix || s.word));
      });
      // Gboard's arrangement: with three suggestions, the pick sits in the middle.
      if (items.length >= 3 && items[0].bold) items.splice(0, 2, items[1], items[0]);
    }
    kbd.setRow(items);
  }

  // Every key goes through here before it types: the loop's, a finger's, the visitor's own keyboard's.
  kbd.on('key', (e) => {
    if (e.source === 'ghost' || !['char', 'space', 'delete', 'enter'].includes(e.type)) return;
    skip();
    if (job?.none) job = null;
    if (e.type === 'enter') { if (!job && tagAt()) fill(); return; }
    if (e.type === 'delete') { if (undo(false)) e.preventDefault(); return; }
    const sep = e.type === 'space' ? ' ' : e.char.length === 1 && WORD_ENDS.includes(e.char) ? e.char : null;
    if (sep && correctLastWord(sep)) e.preventDefault(); else fix = null;
  });

  kbd.on('input', (e) => {
    if (e.why === 'caret') fix = null;
    // A request just closed before the cursor is filled at once, as the app does with "Fill as soon as I type >>" on.
    if (e.why === 'insert' && !playing && !job && !typing && CLOSED.test(f.before)) fill(); else refresh();
  });

  kbd.loop(async (s) => {
    playing = true;
    fix = job = typing = null;
    kept.clear();
    f.clear();
    await s.wait(700);
    await s.type('Be there in ten minutws', { wpm: 50 });
    await s.wait(420);
    await s.press(' ');                       // the space that ends the word: autocorrect looks at it here
    await s.wait(2400);
    fix = null;
    f.clear();
    await s.wait(600);
    await s.type('<<standup tldr>>', { wpm: 58 });
    await fill(s);
    await s.wait(3200);
  }, {
    gap: 400,
    // Reduced motion: the fix as it stands just after the space, and nothing moves.
    still: () => { f.set('Be there in ten minutes '); fix = { typed: 'minutws', fixed: 'minutes', sep: ' ' }; refresh(); },
    // A visitor takes over: an answer half typed lands whole, a request still being written is theirs to fill.
    onHandOver: () => { playing = false; skip(); job = null; refresh(); },
  });

  // Focus coming to the keys or the row (a Tab, as much as a tap) is a visitor too: the row stops changing under them.
  kbd.el.addEventListener('focusin', () => kbd.interrupt('focus'));

  refresh();
  return kbd;
}

// --- the headline ---------------------------------------------------------------------------------------------------

/** Characters with nothing to see and no place for a line to break: one starts each ending of the headline (below). */
const JOINERS = [0x2060, 0x200C, 0x200D].map((c) => String.fromCharCode(c));

/**
 * Types the end of the hero's headline live, and keeps changing it:
 *
 *   <h1 class="display">Type it. <span data-phrases="&lt;&lt;standup tldr&gt;&gt;.|&lt;&lt;decline politely&gt;&gt;.">&lt;&lt;ask for it&gt;&gt;.</span></h1>
 *   typeHeadline(h1.querySelector('span'));
 *
 * `el` holds the tagline's ending as the page wrote it, which is typed first and is what a screen reader hears; the
 * other endings come from `phrases` (an array) or el's data-phrases, split on |. With none, the ending is typed once.
 * The headline keeps the height of its tallest ending, so nothing under it moves; the typing stops while the headline
 * is off screen; with reduced motion the ending is simply there. `opts` go to typeInto. Returns {el, cancel()}.
 */
export function typeHeadline(el, phrases = null, opts = {}) {
  const first = el.textContent.trim();
  const list = [first, ...(phrases || (el.dataset.phrases || '').split('|')).map((p) => String(p).trim()).filter((p) => p && p !== first)];
  const many = list.length > 1;
  const block = el.closest('h1, h2, h3, p') || el.parentElement;
  let handle = null, io = null, ro = null, width = -1, raf = 0;

  /** Room for the tallest ending: each is tried in an unseen copy of the headline, as wide as the headline is now. */
  const reserve = () => {
    const w = block.getBoundingClientRect().width;
    if (Math.abs(w - width) < 0.5) return;
    width = w;
    el.setAttribute('data-hero-tail', '');
    const copy = block.cloneNode(true), tail = copy.querySelector('[data-hero-tail]');
    copy.removeAttribute('id');
    copy.setAttribute('aria-hidden', 'true');
    // A tenth of an em narrower: typed, the ending is in two runs and can set a hair wider than it does whole.
    copy.style.cssText = `position:absolute;left:0;top:0;visibility:hidden;pointer-events:none;margin:0;min-height:0;width:${w - 0.1 * parseFloat(getComputedStyle(block).fontSize)}px`;
    block.after(copy);
    let tallest = 0;
    for (const p of list) { tail.textContent = p; tallest = Math.max(tallest, copy.getBoundingClientRect().height); }
    copy.remove();
    block.style.minHeight = Math.ceil(tallest) + 'px';
  };
  // typeInto rubs an ending out only as far as the next one differs, then types the next one whole, which doubles what
  // they share ("<<<<standup"). Each ending here starts with a different unseen character, so each is rubbed out whole.
  const apart = list.map((p, i) => JOINERS[i === list.length - 1 && i % 2 === 0 ? 2 : i % 2] + p);
  const start = () => { handle = typeInto(el, many ? apart : list[0], { wpm: 62, hold: 2400, loop: many, caret: many ? true : 'typing', whenVisible: false, label: first, ...opts }); };

  if (many) {
    reserve();
    // A frame later: reserve() sets the height of the element this observer watches.
    if ('ResizeObserver' in window) { ro = new ResizeObserver(() => { cancelAnimationFrame(raf); raf = requestAnimationFrame(reserve); }); ro.observe(block); }
  }
  if ('IntersectionObserver' in window) {
    // On screen it types, from the page's own ending; off screen it stops where it is.
    io = new IntersectionObserver((entries) => {
      handle?.cancel();
      handle = null;
      if (!entries[entries.length - 1].isIntersecting) return;
      start();
      if (!many) io.disconnect();                // one ending is typed once
    }, { threshold: 0.2 });
    io.observe(block);
  } else start();

  return { el, cancel() { io?.disconnect(); ro?.disconnect(); cancelAnimationFrame(raf); handle?.cancel(); } };
}
