// Renders site/og.png (the share image) from the page's own stylesheets. Needs the site served on 127.0.0.1:8917 and a
// Playwright install; the import path below is one machine's npx cache. Usage: node og-image.mjs ../site/og.png
import { chromium } from '/Users/matt/.npm/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs';
const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1200, height: 630 }, colorScheme: 'dark', deviceScaleFactor: 1 })).newPage();
await page.goto('http://127.0.0.1:8917/index.html', { waitUntil: 'load' });
// The page's own stylesheet and keycap, with no script: the share image is the hero, set for a card.
await page.setContent(`<!doctype html><html lang="en" data-theme="dark"><head><meta charset="utf-8"><base href="http://127.0.0.1:8917/">
<link rel="stylesheet" href="css/site.css"><link rel="stylesheet" href="css/kbd.css"><link rel="stylesheet" href="css/chunks/ghostwriter.css">
<style>
  body { width: 1200px; height: 630px; overflow: hidden; display: grid; grid-template-columns: 1fr; align-content: center; padding: 0 72px; box-sizing: border-box; }
  .brand { display: flex; align-items: center; gap: 12px; font-weight: 600; font-size: 22px; letter-spacing: -.01em; margin-bottom: 30px; }
  .brand svg { width: 30px; height: 30px; color: var(--accent); }
  h1 { font-size: 76px; line-height: .98; letter-spacing: -.045em; font-weight: 600; margin: 0 0 44px; max-width: 13ch; }
  .wrapkeys { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 48px; }
  .keys { width: 470px; }
  .hero__keys .keycaps__row .keycap { min-block-size: 190px; }
  .line { padding: 0 14px; font-size: 18px; line-height: 1.45; height: 2.9em; }
  .line span { color: var(--accent-ink); }
  .chip { margin: 0 14px; display: inline-flex; align-items: center; height: 34px; padding: 0 14px; border-radius: 18px; background: #16181A; font-size: 15px; width: fit-content; }
  .meta { color: var(--ink-2); font-size: 22px; margin-top: 6px; }
</style></head><body>
<div class="wrapkeys">
  <div>
    <div class="brand"><svg viewBox="0 0 24 24" fill="currentColor"><path fill-rule="evenodd" d="M12 3a7 7 0 0 0-7 7v10l2.33-1.75L9.67 20 12 18.25 14.33 20l2.34-1.75L19 20V10a7 7 0 0 0-7-7ZM9.5 9.25a1.25 1.25 0 1 0 0 2.5 1.25 1.25 0 0 0 0-2.5Zm5 0a1.25 1.25 0 1 0 0 2.5 1.25 1.25 0 0 0 0-2.5Z"/></svg>Ghost Keyboard</div>
    <h1>The Android keyboard that writes as you.</h1>
    <p class="meta">Your own AI model and key. Free, no account, no ads.</p>
  </div>
  <div class="keycaps hero__keys keys"><div class="keycaps__row">
    <div class="keycap keycap--1u hero__side"><span class="keycap__face"><span class="keycap__body"><span class="keycap__glyph keycap__glyph--mono">&lt;&lt;</span></span></span></div>
    <div class="keycap keycap--3u"><div class="keycap__face"><p class="keycap__legend">ghostwriter</p>
      <div class="keycap__body"><div class="line"><span>&lt;&lt;decline politely&gt;&gt;</span></div><div class="chip">✦ Fill: decline politely</div></div>
      <p class="keycap__caption" style="grid-column: 1 / -1">Type what you want, in any app.</p></div></div>
    <div class="keycap keycap--1u hero__side"><span class="keycap__face"><span class="keycap__body"><span class="keycap__glyph keycap__glyph--mono">&gt;&gt;</span></span></span></div>
  </div></div>
</div>
</body></html>`, { waitUntil: 'load' });
await page.waitForTimeout(300);
await page.screenshot({ path: process.argv[2] });
await browser.close();
