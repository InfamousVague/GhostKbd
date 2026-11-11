// The row above the keys, on the face of a space bar: a line of text with a caret, and under it the keyboard's own
// row (PluginRow.kt) with the arrow keys pinned at its end. The arrows move the caret, and run while held (once, 400 ms,
// then every 50 ms); mid-word the row offers autocorrect's words for the letters before the caret, and one tapped
// replaces them with a space after it; between words it holds the row's tools, here the clipboard's: the last copy as
// a bold chip that pastes, and Clipboard, which opens the history. Left alone it types a few words, takes a
// suggestion, and walks the caret back and forth with the arrows.
//
//   <article class="keycap keycap--space"> … <div class="keycap__body" data-chunk="row"></div> … </article>
//   import { mount } from './chunks/row.js';  mount(body)  → the GhostKbd
//
// Needs css/chunks/row.css beside site.css and kbd.css. The row is as it is with Settings › Row tools › Arrow keys
// and the clipboard plugin on, and no other row tool.

import { GhostKbd } from '../kbd.js';

const CLIP = 'ghostkbd.com';                   // what was "just copied": short, so the row's chips are whole on a key's face
const TYPED = 'Get it fro', PICK = 'from', STILL = 'Get it from ';

// What the row offers for a word, worked out ahead with the app's own Autocorrect.suggest over its English list
// (assets/dict/en.tsv, slang on, nothing learned) and laid out as DevKeyboardService.refreshRow lays it out:
// word/items, * the bold one, " the typed word in quotes. Here: every beginning of the words this line can hold and
// of the words offered for them, and the pieces a pick mid-word leaves behind. The page does not carry the 40,000
// words, so any other word gets what the app shows before its dictionary has loaded: the word in quotes, alone.
const OFFERS = new Map(`
a/and,*a,as,are,at ac/actually,*ac,act,according,across
ack/acknowledge,*ack,acknowledged,acknowledging,acknowledges an/and,*an,any,another,anything
and/andrew,*and,andy,android,anderson ar/are,*ar,around,area,art are/area,*are,areas,aren't,arena
at/attention,*at,attack,attempt,attorney att/attention,*att,attack,attempt,attorney
atta/attack,*atta,attacks,attached,attacked attac/",*attack,attacks,attached,attacked,attacking
attach/attached,*attach,attachment,attachments,attaching attache/",*attached,attaches attached/*attached
attack/attacks,*attack,attacked,attacking,attacker attacke/",*attacks,attacked,attacker,attackers
attacked/*attacked attacker/*attacker,attackers attacki/",*attack,attacking attackin/",*attacking
attacking/*attacking attacks/*attacks atte/",*ate,attention,attempt,attend,attempts
attem/",attempt,attempts,attempted,attempting attemp/",*attempt,attempts,attempted,attempting
attempt/attempts,*attempt,attempted,attempting atten/",*attend,attention,attended,attending,attendance
attent/",attention,attentive,attentions attenti/",attention,attentive,attentions
attentio/",*attention,attentions attention/*attention,attentions atto/",*ato,attorney,attorneys,attorney's
attor/",*actor,attorney,attorneys,attorney's attorn/",attorney,attorneys,attorney's
attorne/",*attorney,attorneys,attorney's attorney/attorneys,*attorney,attorney's c/can,*c,could,can't,come
ck/*ck e/even,*e,every,each,end et/etc,*et,ethnic,ethics,eternal f/for,*f,from,first,find
fi/first,*fi,find,five,fire fin/find,*fin,final,fine,finally find/finding,*find,finds,findings,finder
fir/first,*fir,fire,firm,fired firs/firs,*fire,first,firstly,firsthand
first/firstly,*first,firsthand,firsts,firstborn fo/for,*fo,found,four,following
for/form,*for,former,force,forward fr/from,*fr,free,friends,friend fre/",*fee,free,french,freedom,fresh
free/freedom,*free,freeze,freely,freezing fri/friends,*fri,friend,friday,friendly
frie/",*fire,friends,friend,friendly,friendship frien/",*friend,friends,friendly,friendship,friend's
friend/friends,*friend,friendly,friendship,friend's friends/friendship,*friends,friendships
fro/from,*fro,front,frontend,frozen from/*from fron/",*from,front,frontend,frontier,fronts
front/frontend,*front,frontier,fronts,frontal fronte/",*front,frontend,fronted fronten/",*frontend
frontend/*frontend froz/",*from,frozen,froze froze/*froze,frozen frozen/*frozen g/get,*g,good,go,going
ge/get,*ge,getting,general,gets gen/general,*gen,generally,generation,gender
gene/general,*gene,generally,generation,generated gener/",*gene,general,generally,generation,generated
genera/general,*genera,generally,generation,generated general/generally,*general,generals,general's,generalized
get/getting,*get,gets,gettin,getaway geta/",*get,getaway getaw/",getaway getawa/",getaway getaway/*getaway
gets/*gets gett/",*get,getting,gettin,getty,gettysburg getti/",*gettin,getting gettin/*gettin,getting
getting/*getting go/good,*go,going,got,god goi/",*go,going,goin,goings goin/going,*goin,goings
going/*going,goings goo/good,*goo,google,goods,goodbye good/goods,*good,goodbye,goodness,goodwill
i/in,*I,is,it,if if/ifs,*if,ifa,ifc,iffy in/into,*in,including,information,international
is/isn't,*is,issue,issues,island it/it's,*it,its,itself,items it'/",*it,it's,it'll,it'd it's/*it's
ite/",*its,items,item,iteration,iterations item/*item,items items/*items its/*its,itself itse/",*its,itself
itsel/",*itself itself/*itself k/know,*k,keep,kind,known m/my,*m,me,more,make o/of,*o,on,or,one
om/omg,*om,omega,omar,omfg r/really,*r,right,real,read ro/room,*ro,road,role,round
rom/roman,*rom,rome,romantic,romance t/the,*t,to,that,this ta/take,*ta,talk,taking,taken
tac/tactics,*tac,tackle,tactical,tackles tack/tackle,*tack,tackles,tackling,tackled tt/ttyl,*tt,ttys,ttc
tta/",*ta ttac/",*tac ttack/",*track
`.trim().split(/\s+/).map((e) => e.split('/')).map(([word, items]) => [word, items.split(',')]));

const isLetter = (c) => /\p{L}/u.test(c);
const isUpper = (c) => c !== c.toLowerCase();

/** The letters (and apostrophes) just before the caret: the word being typed (DevKeyboardService.currentWord). */
function currentWord(before) {
  before = before.slice(-48);
  let start = before.length;
  while (start > 0 && (isLetter(before[start - 1]) || before[start - 1] === "'")) start--;
  if (start > 0 && /[\p{L}\p{N}@#/._\-\\:$`]/u.test(before[start - 1])) return '';
  return before.slice(start).replace(/^'+/, '');
}

/** The typed word's capitals on a suggestion: all, first, or none (Autocorrect.recase). */
function recase(typed, word) {
  if (typed.length > 1 && [...typed].every((c) => !isLetter(c) || isUpper(c))) return word.toUpperCase();
  return isUpper(typed[0]) ? word[0].toUpperCase() + word.slice(1) : word;
}

/** A clip as a chip shows it: one line, cut short (DevKeyboardService.preview). */
function preview(text) {
  const line = text.replace(/\s+/g, ' ').trim();
  return line.length > 28 ? line.slice(0, 27) + '…' : line;
}

export function mount(root) {
  root.classList.add('rowc');
  root.innerHTML =
    '<div class="keycap__field rowc__line" tabindex="0" role="textbox" aria-readonly="true" aria-label="A message being typed. With it focused, your own arrow keys move the caret as the row\'s do."></div>' +
    '<div class="rowc__bar"></div>';
  const line = root.firstChild;
  const kbd = new GhostKbd(root.lastChild, {
    // Never much under a phone's own row: the arrows stay a finger wide on a narrow key.
    fragment: { width: 360, minDp: 0.86, maxDp: 1.15 }, theme: 'auto', bare: true, arrows: true, cog: false, autoCaps: false,
    label: 'The row above the keys: suggestions, the clipboard and four arrow keys',
  });
  const field = kbd.attach(line, { placeholder: 'Message' });
  const strip = kbd.el.querySelector('.gk-strip');
  const arrow = (dir) => kbd.el.querySelector(`.gk-arrow[data-dir="${dir}"]`);
  const item = (id) => [...strip.children].find((v) => v.dataset.id === id);

  // The clipboard plugin's state (ClipHistory.kt): the history, the copy still fresh, and whether the row shows it.
  let clips, fresh, showing;
  const copy = () => { clips = [CLIP]; fresh = CLIP; showing = false; };

  /** A suggestion tapped: it replaces the word being typed, and a space follows (pickSuggestion). */
  const pick = (word) => { const typed = currentWord(field.before); field.replace(field.caret - typed.length, field.caret, word + ' '); };
  const paste = (text) => { fresh = null; showing = false; field.insert(text); };
  const show = (on) => { showing = on; refresh(); };

  /** What the row shows, the first that applies: the clipboard's history, the word's suggestions, the tools (refreshRow). */
  function refresh() {
    const word = showing ? '' : currentWord(field.before);
    let items;
    if (showing) {
      items = [{ id: 'clips-back', icon: 'back', ariaLabel: 'Back', onTap: () => show(false) }];
      if (!clips.length) items.push({ id: 'clips-none', label: 'Nothing copied yet', disabled: true });
      else items.push(...clips.map((c) => ({ id: 'clip-' + c, label: preview(c), chip: true, onTap: () => paste(c) })),
        { id: 'clips-clear', label: 'Clear', onTap: () => { clips = []; fresh = null; refresh(); } });
    } else if (word) {
      items = (OFFERS.get(word.toLowerCase()) || ['"']).map((it, i) => it === '"'
        // The typed word in quotes: kept as it is, and finished with a space (keepTyped).
        ? { id: 'typed-' + word, label: `“${word}”`, wide: true, onTap: () => field.insert(' ') }
        : ((w) => ({ id: `s-${i}-${w}`, label: w, wide: true, bold: it[0] === '*', onTap: () => pick(w) }))(recase(word, it.replace('*', ''))));
    } else {
      items = fresh ? [{ id: 'fresh', label: preview(fresh), bold: true, chip: true, ariaLabel: `Paste ${fresh}`, onTap: () => paste(fresh) }] : [];
      // On the phone the row scrolls; on a key's face a chip cut off by the arrows reads as a mistake. Where there is
      // no room for both chips whole (a phone's narrow key), the fresh copy alone is shown.
      const dp = kbd.geometry?.dp || 1;
      if (!fresh || strip.clientWidth >= 196 * dp) items.push({ id: 'clips', label: 'Clipboard', chip: true, onTap: () => show(true) });
    }
    // A row button that had the focus is gone with the old row (a visitor's own tap or key press changed it): the
    // focus goes to the line, so it is never dropped to the page.
    const had = strip.contains(document.activeElement);
    kbd.setRow(items);
    if (had && !strip.contains(document.activeElement)) line.focus({ preventScroll: true });
  }
  // Focus coming into the key (a Tab, as much as a tap) is a visitor: the idle loop makes way, so the row stops
  // changing under the button they are on.
  root.addEventListener('focusin', () => kbd.interrupt('focus'));
  copy();
  kbd.on('input', refresh);
  kbd.on('layout', refresh);
  refresh();

  // The line takes the focus, and then the visitor's own arrow keys are the row's: each press is that key's.
  const down = (el, ms) => { el.classList.add('is-down'); setTimeout(() => el.classList.remove('is-down'), ms); };
  line.addEventListener('keydown', (ev) => {
    if (ev.ctrlKey || ev.metaKey || ev.altKey) return;
    if (ev.key === 'Escape') { line.blur(); return; }
    const dir = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' }[ev.key];
    if (!dir) return;
    ev.preventDefault();
    arrow(dir).click();
    down(arrow(dir), 110);
  });
  // Scrolled away with the focus still in the line, the loop would wait for ever: the line lets go.
  kbd.on('visible', ({ visible }) => { if (!visible && document.activeElement === line) line.blur(); });

  // The idle loop's own fingers: an arrow tapped, an arrow held (PluginRow.repeating), a row item tapped.
  const lift = () => { for (const el of kbd.el.querySelectorAll('.is-down')) el.classList.remove('is-down'); };
  const step = (dir) => field.move(dir === 'left' ? -1 : 1);
  async function tap(s, dir) {
    arrow(dir).classList.add('is-down');
    step(dir);
    try { await s.wait(120); } finally { lift(); }
  }
  async function hold(s, dir, till) {
    arrow(dir).classList.add('is-down');
    try { for (let first = true; field.caret !== till; first = false) { step(dir); await s.wait(first ? 400 : 50); } } finally { lift(); }
  }

  kbd.loop(async (s) => {
    copy();
    field.clear();
    await s.wait(1100);
    await s.type(TYPED, { wpm: 62 });
    await s.wait(800);
    const offer = item(`s-0-${PICK}`);
    if (offer) { offer.classList.add('is-down'); try { await s.wait(170); } finally { lift(); } pick(PICK); }
    await s.wait(1300);
    for (let i = 0; i < 3; i++) { await tap(s, 'left'); await s.wait(300); }
    await s.wait(500);
    await hold(s, 'left', 0);
    await s.wait(800);
    await hold(s, 'right', field.text.length);
    await s.wait(2200);
  }, { gap: 1200, onHandOver: lift, still: () => { copy(); field.set(STILL); } });

  return kbd;
}
