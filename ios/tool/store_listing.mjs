// Fills the App Store listing of the iOS app from docs/store/ios: the version's text (metadata.md) and the screenshots.
// It uploads no build and submits nothing. node ios/tool/store_listing.mjs [text] [shots]
// The API client and its key are Matt's (~/Development/Apps/ghost-md-app-store-pack/tools/asc.mjs).
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
const { asc } = await import(`${homedir()}/Development/Apps/ghost-md-app-store-pack/tools/asc.mjs`);
const DOCS = new URL('../../docs/store/ios/', import.meta.url).pathname;
const VERSION = readFileSync(new URL('../../pubspec.yaml', import.meta.url), 'utf8').match(/^version:\s*([\d.]+)/m)[1];
const what = process.argv.slice(2);
const doing = (x) => what.length === 0 || what.includes(x);

/** The indented block under a heading of metadata.md, its four-space indent taken off. */
function block(heading) {
  const md = readFileSync(`${DOCS}metadata.md`, 'utf8');
  const at = md.indexOf(`## ${heading}`);
  const rest = md.slice(md.indexOf('\n', at) + 1);
  const end = rest.search(/^## /m);
  const lines = (end < 0 ? rest : rest.slice(0, end)).split('\n').filter((l) => l.startsWith('    ') || l.trim() === '');
  return lines.map((l) => l.slice(4)).join('\n').trim();
}

const app = (await asc('GET', '/v1/apps?filter[bundleId]=com.infamousvague.ghostkbd')).data[0];
const version = (await asc('GET', `/v1/apps/${app.id}/appStoreVersions?filter[platform]=IOS&include=appStoreVersionLocalizations`));
const v = version.data.find((x) => x.attributes.appStoreState === 'PREPARE_FOR_SUBMISSION');
if (!v) throw new Error('no version in Prepare for Submission');
const loc = version.included.find((x) => x.type === 'appStoreVersionLocalizations' && x.attributes.locale === 'en-US');

if (doing('text')) {
  await asc('PATCH', `/v1/appStoreVersions/${v.id}`, { data: { type: 'appStoreVersions', id: v.id, attributes: { versionString: VERSION, copyright: `${new Date().getFullYear()} Matt Wisniewski` } } });
  await asc('PATCH', `/v1/appStoreVersionLocalizations/${loc.id}`, { data: { type: 'appStoreVersionLocalizations', id: loc.id, attributes: { description: block('Description'), keywords: block('Keywords'), promotionalText: block('Promotional text') } } });
  const info = (await asc('GET', `/v1/apps/${app.id}/appInfos?include=appInfoLocalizations`));
  const i = info.data[0], il = info.included.find((x) => x.type === 'appInfoLocalizations' && x.attributes.locale === 'en-US');
  await asc('PATCH', `/v1/appInfoLocalizations/${il.id}`, { data: { type: 'appInfoLocalizations', id: il.id, attributes: { subtitle: block('Subtitle') } } });
  await asc('PATCH', `/v1/appInfos/${i.id}`, { data: { type: 'appInfos', id: i.id, relationships: { primaryCategory: { data: { type: 'appCategories', id: 'UTILITIES' } }, secondaryCategory: { data: { type: 'appCategories', id: 'DEVELOPER_TOOLS' } } } } });
  console.log(`text: version ${VERSION}, description ${block('Description').length} chars, keywords, promotional text, subtitle, categories`);
}

if (doing('shots')) {
  const SETS = { 'iphone-6.9': 'APP_IPHONE_67', 'ipad-13': 'APP_IPAD_PRO_3GEN_129' };
  const sets = (await asc('GET', `/v1/appStoreVersionLocalizations/${loc.id}/appScreenshotSets`)).data;
  for (const [folder, type] of Object.entries(SETS)) {
    let set = sets.find((s) => s.attributes.screenshotDisplayType === type);
    if (!set) set = (await asc('POST', '/v1/appScreenshotSets', { data: { type: 'appScreenshotSets', attributes: { screenshotDisplayType: type }, relationships: { appStoreVersionLocalization: { data: { type: 'appStoreVersionLocalizations', id: loc.id } } } } })).data;
    // The folder is the whole set: what is there already goes first.
    for (const old of (await asc('GET', `/v1/appScreenshotSets/${set.id}/appScreenshots?limit=20`)).data) await asc('DELETE', `/v1/appScreenshots/${old.id}`);
    for (const name of readdirSync(`${DOCS}screenshots/${folder}`).filter((f) => f.endsWith('.png')).sort()) {
      const bytes = readFileSync(`${DOCS}screenshots/${folder}/${name}`);
      const shot = (await asc('POST', '/v1/appScreenshots', { data: { type: 'appScreenshots', attributes: { fileName: name, fileSize: bytes.length }, relationships: { appScreenshotSet: { data: { type: 'appScreenshotSets', id: set.id } } } } })).data;
      for (const op of shot.attributes.uploadOperations) {
        const res = await fetch(op.url, { method: op.method, headers: Object.fromEntries(op.requestHeaders.map((h) => [h.name, h.value])), body: bytes.subarray(op.offset, op.offset + op.length) });
        if (!res.ok) throw new Error(`${name}: upload ${res.status}`);
      }
      await asc('PATCH', `/v1/appScreenshots/${shot.id}`, { data: { type: 'appScreenshots', id: shot.id, attributes: { uploaded: true, sourceFileChecksum: createHash('md5').update(bytes).digest('hex') } } });
      console.log(`${type}: ${name}`);
    }
  }
}
