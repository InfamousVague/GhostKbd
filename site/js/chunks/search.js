/*
 * Settings search, on the face of a 2u keycap: the app's "Search settings" field and, under it, what a few typed
 * letters find, each with where it lives ("Ghostwriter › What it can read"). No settings pages, only the search.
 *
 * It searches as the app does (lib/settings/settings_search.dart): every word typed is found, in any order, each at
 * the start of a word. Sections and their parts come first, matched by their names and state lines; then the settings
 * on their pages, matched by their names, their hints and the names of the sections they sit in. Nothing is ranked:
 * the order is the order of Settings' own list, which is why "arrow" finds Layout's two cards (their footers speak of
 * an arrow) before Row tools' Arrow keys. The app lists up to 40; a key has room for two (four where it stands in a
 * tall row, beside the look key), and says how many there are.
 *
 * Not every setting can be found: the search reads sections, cards and rows, so the theme editor (a page of its
 * own) and rows that show only while a switch is on are not in it. The page's words say "finds a setting", not "every".
 *
 * The index below is lib/settings/panes.dart read by hand, as the app's own pageEntries reads it (each card's title
 * with its footer, each row's label with its hint, each page's heading with its line), for one phone: every plugin
 * added (Ghost.md installed with it) and nothing else touched, so no AI key yet, no speech model downloaded, nothing
 * typed and no app profiles. A setting added to the app has to be added here.
 *
 * Left alone it types four words whose first result plainly matches them (ripple, slang, heatmap, github), once
 * through, and rests until the key is scrolled back to. The field is a real one: the visitor's own keyboard types into it, and
 * the typing stops for them. The GhostKbd here draws no keys; it stands unseen behind the face and keeps the idle
 * typing (on screen only, never under prefers-reduced-motion, out of a visitor's way).
 *
 *   <article class="keycap keycap--2u"> … <div class="keycap__body" data-chunk="search"></div> … </article>
 *   import { mount } from './chunks/search.js';
 *   mount(document.querySelector('[data-chunk="search"]'));     // the .keycap__body; returns the GhostKbd
 *
 * Needs css/chunks/search.css after site.css and kbd.css.
 */
import { GhostKbd, icon } from '../kbd.js';

const IDLE = ['ripple', 'slang', 'heatmap', 'github'];     // each one's first result shows the word that was typed
const MOST = 4;         // results on the key, where it has the height; two on most
const LIMIT = 40;       // results in the app (searchSettings' limit)
const HOLD = 2800;      // a finished word and what it found stay this long

// --- the index: Settings' sections, their parts, and what their pages say -------------------------------------------

/** A section: its id, its name, its state line, its page's entries ([name, hint]; null where it has no page), its parts. */
const S = (id, label, summary, entries = [], parts = []) => ({ id, label, summary, entries, parts });

// The AI every AI plugin shares: a part of AI, Mockingbird, Ghostwriter and Rewrite alike. Its settings are listed once.
const YOUR_AI = S('ai-provider', 'Your AI', 'Add a key', [
  ['AI', 'Mockingbird and Ghostwriter share this. Requests go straight from your phone to the provider with your own key; Ghost Keyboard has no server in between.'],
  ['Claude', 'Key from console.anthropic.com'],
  ['OpenAI', 'Key from platform.openai.com'],
  ['Gemini', 'Key from aistudio.google.com'],
  ['Mistral', 'Key from console.mistral.ai'],
  ['Groq', 'Key from console.groq.com'],
  ['DeepSeek', 'Key from platform.deepseek.com'],
  ['OpenRouter', 'Key from openrouter.ai/keys'],
  ['Custom', 'Any OpenAI-compatible https endpoint: Ollama, LM Studio, vLLM.'],
  ['Claude key', 'Kept in this app’s private storage on your phone. Copy settings in Developer never includes it.'],
  ['Model', 'Using claude-opus-5-5 until you pick another. Type a model’s id and press enter, or load the list.'],
  ['Load Claude’s models'],
  ['Test it', 'Sends one tiny request.'],
]);

const SECTIONS = [
  S('setup', 'Setup', 'Ready: you’re typing with Ghost Keyboard', [
    ['Ready', 'You’re typing with Ghost Keyboard.'],
    ['Steps'],
    ['1. Turn on Ghost Keyboard', 'In your phone’s keyboard list.'],
    ['2. Switch to Ghost Keyboard', 'Make it the keyboard you type with.'],
    ['Watch the walkthrough again'],
  ]),
  S('typing', 'Typing', 'Autocorrect, swipe, adaptive keys', [
    ['Autocorrect', 'Fixes a misspelled word as you finish it; backspace straight after puts yours back.'],
    ['Swipe typing', 'Draw a word across the letters; its other guesses wait in the row.'],
    ['Adaptive keys', 'Learns where you aim and the keys you slip onto, and moves their edges to fit.'],
    ['Capitals at sentence starts'],
    ['Slide the space bar to move the cursor', 'Sideways by characters; up or down by lines.'],
    ['Slide backspace to delete words', 'Slide left from backspace: each stretch takes a word.'],
  ], [
    S('autocorrect', 'Autocorrect', 'English', [
      ['Correct on its own', 'Off: it only suggests.'],
      ['Suggestions above the keys'],
      ['Texting slang', 'Leaves lmfao, ngl, ts, sybau and the like alone, and suggests them.'],
      ['Languages', 'They work together, so a message can mix them.'],
      ['English'], ['Español'], ['Français'], ['Deutsch'], ['Italiano'], ['Português'],
      ['Learned words', 'Words it leaves alone. Undoing a fix adds the word here; tap one to forget it.'],
    ]),
    S('adaptive', 'Adaptive keys', '0 keys tuned, 0 slips', [
      ['Show the heatmap on the keyboard', 'Red glows are your slips; a dot on a line is where a key now listens.'],
      ['Learned so far'],
      ['Forget what it learned'],
    ]),
    S('stats', 'Typing stats', 'Nothing typed today', [
      ['Today', 'Accuracy is how much of what you typed stood: a backspace straight after a key, or an autocorrect fix, is a slip.'],
      ['Speed'], ['Accuracy'], ['Words'], ['Fixed by autocorrect'], ['Words swiped'],
      ['Last 7 days', 'Words typed each day.'],
      ['Last 30 days'],
      ['Count my typing'],
      ['Reset stats'],
    ]),
    S('typing-more', 'More typing', 'Full stops, long press, the { } key', [
      ['Double space types a full stop'],
      ['Long-press delay'],
      ['The { } key', 'Pairs leave the cursor inside; long-press a key on the { } page for its compounds (== !== => ->…).'],
      ['Code symbols, pairs and snippets'],
    ]),
  ]),
  S('look', 'Look & feel', 'Dark · normal keys', [
    ['Light or dark'],
    ['Key height', 'Condensed takes about a fifth less of the screen: shorter rows, smaller letters and a slimmer row above.'],
  ], [
    S('colours', 'Colours', 'Ghost'),                      // the theme editor: a page the search does not read
    S('backlight', 'Backlight', 'Reactive', [
      ['Effect'],
      ['Reactive', 'The key you press glows, then fades.'],
      ['Ripple', 'A ring of light runs out from each key.'],
      ['Heatmap', 'Keys warm with use and cool when left.'],
      ['Rainbow wave', 'A slow hue wave across the board; presses flare.'],
      ['Starlight', 'Keys twinkle at random; presses flare.'],
      ['Breathing', 'The board breathes softly; presses flare.'],
      ['Colour', 'Primary is your theme’s own colour; with the heatmap it runs cool to hot.'],
      ['Brightness'],
      ['Fade'],
    ]),
    S('keys', 'Keys', 'hints', [
      ['Number row'],
      ['Symbol hints', 'Each key’s long-press character in its corner.'],
      ['Popup on key press', 'The key you’re pressing, large above your finger.'],
      ['Key borders'],
      ['Toolbar', 'Settings and hide above the keys. Long-press { } opens settings either way.'],
    ]),
    S('layout', 'Layout', 'Split when unfolded', [
      ['Unfolded and on tablets', 'For screens 600 points wide or more: a foldable’s inner screen, a tablet, most phones on their side. Docked, tap the arrow on the empty side to move the keys across.'],
      ['Split', 'Two halves, a phone’s key size each, with the middle open for your thumbs.'],
      ['Full width', 'One keyboard from edge to edge, with wide keys.'],
      ['Docked left', 'A phone-sized keyboard under your left thumb.'],
      ['Centred', 'A phone-sized keyboard in the middle.'],
      ['Docked right', 'A phone-sized keyboard under your right thumb.'],
      ['On a phone', 'The keys narrow toward one thumb. Tap the arrow on the empty side to switch hands. Open the preview below to see it.'],
      ['One-handed'],
    ]),
    S('keypress', 'Key press & sound', 'Vibrate', [
      ['Vibrate'],
      ['Strength'],
      ['Sound'],
    ]),
  ]),
  S('ai', 'AI', 'Add an AI key', [
    ['Add an AI key', 'Your AI key, shared by the AI plugins: Mockingbird, Ghostwriter and Rewrite. Your own key, straight to the provider.'],
  ], [
    YOUR_AI,
    S('profile', 'Typing profile', 'Import and export', [
      ['Import a typing profile…'],
      ['Export', 'Saved where you choose; nothing is uploaded.'],
      ['Include my message samples', 'Leave off unless you trust where the file goes.'],
      ['Export my typing profile…'],
    ]),
  ]),
  S('rowtools', 'Row tools', '7 of 8 on', [
    ['Arrow keys', 'Left, up, down and right at the end of the row, always there; hold one to keep moving.'],
    ['Clipboard history', 'Your last 25 copies, a tap away. Never a password manager’s.'],
    ['Undo and redo', 'Back and forward arrows.'],
    ['Emoji', 'A panel and search; :shortcodes and emoji for the word you type.'],
    ['GIFs', 'Search GIPHY and send one.'],
    ['Markdown', 'Bold, code, links and lists, in notes apps.'],
    ['Markdown in every app'],
    ['Terminal keys', 'Esc, Tab, Ctrl, Alt and arrows, in terminal apps.'],
    ['Terminal keys in every app'],
    ['Incognito switch', 'Type without the keyboard learning or keeping anything.'],
    ['Skin tone'],
    ['Emoji for the word you type'],
    ['Clear clipboard history'],
    ['GIPHY key', 'Only if the GIF panel asks for one: paste a key from developers.giphy.com and press enter.'],
    ['Fewer settings'],                                   // More settings, read open, as the app's search reads it
  ]),
  S('apps', 'Per-app profiles', 'The same in every app', [
    ['Add a profile', 'The apps you typed in lately with Ghost Keyboard. Only their names are kept, on this phone.'],
    ['No apps to add yet', 'Type something in an app with Ghost Keyboard, then come back.'],
  ]),
  S('market', 'Plugins', '6 added · browse the Market', null),     // the Market: a screen of its own
  S('dictation', 'Dictation', 'The model is not downloaded', [
    ['Not ready', 'The speech model is not on this phone yet.'],
    ['What it needs'],
    ['Microphone', 'Only while the mic is open.'],
    ['Speech model', 'Models run on this phone: nothing you say leaves it. Tap one to use it; it downloads first if it isn’t here. Bigger is more accurate and slower.'],
    ['Base, English', 'Quick and accurate for English. The one it starts with.  60 MB'],
    ['Tiny, all languages', 'The fastest and smallest; rougher on accents and names.  32 MB'],
    ['Base, all languages', 'The everyday pick for languages other than English.  60 MB'],
    ['Small, all languages', 'Noticeably better in every language; a few seconds slower.  190 MB'],
    ['Small, English', 'The most accurate English that stays quick on a phone.  190 MB'],
    ['Large v3 Turbo, all languages', 'Near the best whisper does, in any language. Slow on most phones.  574 MB'],
    ['Language', 'This model hears English only. Pick an “all languages” model above to dictate in others.'],
    ['Detect it'], ['English'], ['Español'], ['Français'], ['Deutsch'], ['Italiano'], ['Português'], ['Nederlands'],
    ['Polski'], ['Svenska'], ['Türkçe'], ['Русский'], ['Українська'], ['العربية'], ['हिन्दी'], ['日本語'], ['한국어'],
    ['中文'], ['Tiếng Việt'], ['Bahasa Indonesia'],
    ['Downloaded'],
    ['Remove Dictation'],
  ]),
  S('passwords', 'Password managers', 'Logins above the keys', [
    ['Set up', 'Ghost Keyboard never sees your passwords: Android draws your manager’s chips and fills the field itself. Needs Android 11 or later.'],
    ['Pick your manager as Android’s autofill service', 'Settings › Passwords & accounts › Autofill service (the name differs a little between phones), then NordPass, 1Password, Bitwarden…'],
    ['Open Android settings'],
    ['Then tap a login field', 'Its logins appear in the row above the keys. Tap one to fill it.'],
    ['Remove Password managers'],
  ]),
  S('mockingbird', 'Mockingbird', 'Add an AI key', [
    ['Add an AI key', 'Keeps samples of what you type, on this phone, and trains a profile of how you write. Ghostwriter and In my voice write with it.'],
    ['Learn my style', 'Off removes Mockingbird; add it again from Plugins.'],
  ], [
    YOUR_AI,
    S('ai-style', 'Your style', '0 samples', [
      ['Train now'],
      ['Keep samples of what I type', 'Never from passwords, addresses, names, searches or incognito.'],
      ['Retrain by itself', 'At most daily, after 50 new samples.'],
      ['Delete samples and profile'],
    ]),
  ]),
  S('ghostwriter', 'Ghostwriter', 'Add an AI key', [
    ['Add an AI key', 'Type <<what you want>> anywhere; close it with >> or press enter, and the AI writes it as you.'],
    ['Fill <<requests>>', 'Off removes Ghostwriter; add it again from Plugins.'],
  ], [
    YOUR_AI,
    S('ai-filling', 'Filling', 'On >>, typed out', [
      ['Fill as soon as I type >>', 'Off: tap Fill, or press enter, on a request.'],
      ['Type the answer out', 'Keys press, light and sound as it types. Off: it appears at once.'],
      ['How it works'],
      ['<<engineering standup tldr>>', 'Something to write: it writes it, as you.'],
      ['<<why did the build fail>>', 'A question: it answers from what it can read. Facts it can’t find become [blanks].'],
      ['Never', 'In password fields or while incognito.'],
    ]),
    S('ai-sources', 'What it can read', 'messages, notes, the web', [
      ['My past messages', 'Your samples that share the request’s words.'],
      ['The web', 'Public facts, with your provider’s own search.'],
      ['My recent clipboard', 'Your last few copies.'],
      ['Ghost.md notes', 'Ghost.md on this phone is asked first, with nothing going online (it can be turned off in Ghost.md, Settings › AI › Ghost Keyboard). Signed in, notes are also searched online when the phone has none that match.'],
      ['Search my notes', 'Ghost.md is on this phone.'],
      ['Sign in to search online', 'In your browser; your password stays on Ghost.md’s page.'],
      ['GitHub commits', 'A classic token with the repo scope, or a fine-grained one with Contents: read, from github.com/settings/tokens. Kept on your phone.'],
      ['Search my commits', 'Messages that match the request, from your repositories and organizations.'],
      ['Check the token'],
    ]),
  ]),
  S('rewrite', 'Rewrite', 'Add an AI key', [
    ['Add an AI key', 'Tap Rewrite in the row above the keys, then how: it works on what you selected, or on the whole field, and replaces it.'],
    ['Translate into', 'Any language, written however you like: Spanish, Deutsch, 日本語. Press enter to keep it.'],
    ['How it works'],
    ['Fix', 'Spelling, grammar and punctuation. Nothing else changes.'],
    ['Shorter', 'The same thing in fewer words.'],
    ['Formal, Casual, Friendlier', 'The tone changes; the meaning and the facts stay.'],
    ['→ Spanish', 'Translated, keeping names, links and code as they are.'],
    ['Never', 'In password fields or while incognito.'],
    ['Remove Rewrite'],
  ], [YOUR_AI]),
  S('ghostmd', 'Ghost.md', 'Notes, tags and saving', [
    ['How it works'],
    ['[[ links', 'In Ghost.md, type [[ and your notes’ titles fill the row; tap one to finish the link, or New to make one.'],
    ['# tags', 'Type # and a letter for the tags you use.'],
    ['Blocks', 'Callout, Table, Math, Diagram, Today, ☐ and — insert Ghost.md’s own blocks; Voice note opens Ghost.md’s recorder.'],
    ['Save to Ghost.md', 'In any other app, select text: Save to Ghost.md sends it there as a note.'],
    ['Where titles come from', 'The links and tags you type in Ghost.md are remembered on this phone. When Ghost.md shares its whole list with the keyboard, every note shows.'],
    ['Remove Ghost.md'],
  ]),
  S('about', 'About', 'Up to date', [
    ['Ghost Keyboard', 'You have the latest version.'],   // the app's heading carries its version number too
    ['Updates'],
    ['Check for updates'],
    ['Update by itself', 'When the app opens and twice a day.'],
    ['Watch the walkthrough again'],
    ['Made with'],
    ['Glacier', 'The UI kit, from Ghost.md.'],
    ['whisper.cpp', 'Speech to text on the phone (MIT).'],
    ['Phosphor Icons and Lucide', 'The icons (MIT, ISC).'],
    ['wordfreq', 'Autocorrect’s word list (CC BY-SA 4.0).'],
  ]),
];

// --- the search (settings_search.dart) --------------------------------------------------------------------------------

/** Lower case, curly quotes made straight, cut into words at anything that is not a letter or a digit. */
const words = (text) => text.toLowerCase().replace(/[‘’]/g, "'").replace(/[“”]/g, '"').split(/[^\p{L}\p{N}]+/u).filter(Boolean);

/**
 * Everything that can be found, in the app's order: {name, where, have}. A section says the sections it sits in and its
 * state line; a setting says the way to its page. A page's own name is not a setting on it, and a setting is listed
 * once for each section id, as searchSettings keeps them (so the shared Your AI part gives its settings once).
 */
const INDEX = (() => {
  const sections = [], settings = [], seen = new Set();
  const visit = (list, above) => {
    for (const s of list) {
      const path = [...above, s.label];
      sections.push({ name: s.label, where: [above.join(' › '), s.summary].filter(Boolean).join(' · '), have: words(`${s.label} ${s.summary}`) });
      for (const [label, detail = ''] of s.entries || []) {
        const id = `${s.id}|${label}`;
        if (label === s.label || seen.has(id)) continue;
        seen.add(id);
        settings.push({ name: label, where: path.join(' › '), have: words(`${label} ${detail} ${path.join(' ')}`) });
      }
      visit(s.parts, path);
    }
  };
  visit(SECTIONS, []);
  return { sections, settings };
})();

/** What `query` finds: sections first, then settings, up to the app's 40. Every word typed starts a word. */
export function search(query) {
  const want = words(query);
  if (!want.length) return [];
  const hit = (it) => want.every((w) => it.have.some((h) => h.startsWith(w)));
  return [...INDEX.sections.filter(hit), ...INDEX.settings.filter(hit)].slice(0, LIMIT).map(({ name, where }) => ({ name, where }));
}

// --- the chunk --------------------------------------------------------------------------------------------------------

export function mount(root) {
  root.classList.add('sr');
  root.innerHTML =
    `<label class="sr__field">${icon('search', { cls: 'sr__ico' })}<span class="sr__box">` +
    '<input class="sr__input" type="search" placeholder="Search settings" aria-label="Search Ghost Keyboard’s settings" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" enterkeyhint="search">' +
    '<span class="sr__ghost" aria-hidden="true"></span></span><span class="sr__count" aria-hidden="true"></span></label>' +
    '<ul class="sr__hits" aria-label="What it finds"></ul><span class="gk-vh" role="status"></span><div class="sr__driver" aria-hidden="true"></div>';
  const input = root.querySelector('.sr__input'), ghost = root.querySelector('.sr__ghost'), count = root.querySelector('.sr__count');
  const list = root.querySelector('.sr__hits'), status = root.querySelector('[role="status"]');

  // No keys are drawn: the keyboard is only the hand that types while nobody else does.
  const kbd = new GhostKbd(root.querySelector('.sr__driver'), {
    fragment: 'bar', theme: 'auto', cog: false, backlight: null, autoCaps: false, hardKeys: false, interactive: false,
    label: 'Settings search, typing by itself',
  });
  kbd.attach(ghost, { focusOnClick: false });

  let query = '', hits = [], rows = MOST, mine = false, told = 0;

  function draw() {
    const q = query.trim();
    const shown = hits.slice(0, rows);
    list.textContent = '';
    count.textContent = hits.length > shown.length ? `${shown.length} of ${hits.length}` : '';
    if (q && !hits.length) {
      const li = document.createElement('li');
      li.className = 'sr__none';
      li.textContent = `Nothing called “${q}”.`;
      list.append(li);
    }
    for (const h of shown) {
      const li = document.createElement('li'), name = document.createElement('span'), where = document.createElement('span');
      li.className = 'sr__hit';
      name.className = 'sr__name';
      name.textContent = h.name;
      where.className = 'sr__where';
      where.textContent = h.where;
      li.append(name, where);
      list.append(li);
    }
  }

  /** Searches for `text`. A visitor's own search is said aloud, once they pause; the idle typing's never is. */
  function find(text, theirs) {
    query = text;
    hits = query.trim() ? search(query) : [];
    draw();
    clearTimeout(told);
    if (!theirs) return;
    const q = query.trim();
    told = setTimeout(() => {
      status.textContent = !q ? '' : !hits.length ? `Nothing called ${q}.` : `${hits.length} found. First: ${hits[0].name}${hits[0].where ? `, ${hits[0].where}` : ''}.`;
    }, 600);
  }

  // As many results as the key has the height for, four at most (search.css says how short a result may be).
  function fit() {
    const least = parseFloat(getComputedStyle(list).getPropertyValue('--sr-min')) || 17;
    const n = Math.max(1, Math.min(MOST, Math.floor((list.clientHeight + 0.5) / least)));
    list.style.setProperty('--sr-n', n);
    if (n !== rows) { rows = n; draw(); }
  }
  fit();
  if ('ResizeObserver' in window) new ResizeObserver(fit).observe(list);

  // What the keyboard types is shown by the ghost line (with its caret) and kept in the real field under it.
  kbd.on('input', (e) => {
    if (input.value !== e.text) input.value = e.text;
    find(e.text, false);
  });

  async function script(s) {
    root.classList.add('is-playing');
    for (const word of IDLE) {
      while (s.field.text) { await s.wait(36); await s.press('\b', { hold: 0 }); }
      await s.wait(520);
      await s.type(word, { wpm: 44 });
      await s.wait(HOLD);
    }
  }

  /** The idle typing, from an empty field. Under prefers-reduced-motion: one search, there already. */
  function play() {
    mine = true;
    clearTimeout(told);
    status.textContent = '';
    kbd.field.clear();
    kbd.loop(script, { resume: false, rounds: 1, gap: 0, still: (k) => k.field.set(IDLE[IDLE.length - 1]) });
  }

  /** A visitor is in the field: the typing stops, and what it had typed makes way for theirs. */
  function take() {
    kbd.interrupt('field');
    root.classList.remove('is-playing');
    if (mine) { mine = false; kbd.field.clear(); }
  }

  input.addEventListener('focus', take);
  input.addEventListener('input', () => { take(); find(input.value, true); });
  // What a visitor found stays while they can see it. The typing starts over only on their next visit to the key
  // (scrolled away and back), and never under prefers-reduced-motion.
  kbd.on('visible', ({ visible }) => { if (visible && !mine && !kbd.reduced && document.activeElement !== input) play(); });

  play();
  return kbd;
}

export default mount;
