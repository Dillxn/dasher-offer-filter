#!/usr/bin/env node
/*
 * Draws the app's pictures from the website's 3D models (tools/art/sprites.js, through headless Chromium) into
 * app/src/main/res/drawable-nodpi/art_<name>.webp, writes where each picture's points landed to tools/art/sprites.json
 * and the app's constants for them to app/src/main/java/com/local/dasherfilter/ArtSprites.java.
 *   node tools/art/render-sprites.cjs <offer-filter-site checkout> [name ...]
 * Needs Playwright's Chromium, and Python 3 with Pillow for WebP. The pictures are committed: only a change to the
 * models, the lights or a picture's framing needs this run again.
 */
const fs = require('fs'), path = require('path'), http = require('http'), os = require('os');
const {execFileSync} = require('child_process');
const {chromium} = require('playwright');

const here = __dirname, repo = path.resolve(here, '..', '..');
const site = path.resolve(process.argv[2] || path.join(repo, '..', 'offer-filter-site'));
const only = process.argv.slice(3);
const RES = path.join(repo, 'app', 'src', 'main', 'res', 'drawable-nodpi');
const JSON_OUT = path.join(here, 'sprites.json');
const JAVA_OUT = path.join(repo, 'app', 'src', 'main', 'java', 'com', 'local', 'dasherfilter', 'ArtSprites.java');

if (!fs.existsSync(path.join(site, 'assets', 'models.js'))) {
  console.error('No website checkout at ' + site + ' (its assets/models.js): pass its path first.');
  process.exit(2);
}

const serve = () => new Promise(resolve => {
  const roots = {'/site/': site, '/art/': here};
  const types = {'.html': 'text/html', '.js': 'text/javascript'};
  const server = http.createServer((req, res) => {
    const url = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const prefix = Object.keys(roots).find(p => url.startsWith(p));
    const base = prefix && roots[prefix], file = base && path.resolve(base, '.' + url.slice(prefix.length - 1));
    if (!file || !file.startsWith(base + path.sep) || !fs.existsSync(file)) { res.writeHead(404).end(); return; }
    res.writeHead(200, {'Content-Type': types[path.extname(file)] || 'application/octet-stream'});
    fs.createReadStream(file).pipe(res);
  });
  server.listen(0, '127.0.0.1', () => resolve(server));
});

// WebP from PNG with Pillow: lossy for the soft shading, its alpha kept whole (no fringes on light or dark pages).
const WEBP = `
import sys
from PIL import Image
src, dst, quality = sys.argv[1], sys.argv[2], int(sys.argv[3])
im = Image.open(src).convert('RGBA')
im.save(dst, 'WEBP', quality=quality, alpha_quality=100, method=6, exact=False)
`;

(async () => {
  const server = await serve();
  const browser = await chromium.launch({args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist']});
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'offer-art-'));
  const script = path.join(tmp, 'webp.py');
  fs.writeFileSync(script, WEBP);
  const base = `http://127.0.0.1:${server.address().port}/art/sprites.html`;
  try {
    const probe = await browser.newPage();
    await probe.goto(base + '?name=sun');
    const names = await probe.evaluate(() => OfferSprites.names);
    await probe.close();
    const todo = only.length ? only : names;
    for (const n of todo) if (!names.includes(n)) throw Error('no picture named ' + n);
    const meta = fs.existsSync(JSON_OUT) ? JSON.parse(fs.readFileSync(JSON_OUT, 'utf8')) : {};
    for (const name of todo) {
      const page = await browser.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(String(e)));
      await page.goto(base + '?name=' + encodeURIComponent(name));
      const result = await page.waitForFunction(() => window.result, null, {timeout: 60000}).then(h => h.jsonValue());
      await page.close();
      if (result.error || errors.length) throw Error(name + ': ' + (result.error || errors.join('\n')));
      const png = path.join(tmp, name + '.png'), webp = path.join(RES, 'art_' + name + '.webp');
      fs.writeFileSync(png, Buffer.from(result.png.split(',')[1], 'base64'));
      fs.mkdirSync(RES, {recursive: true});
      execFileSync('python3', ['-I', script, png, webp, name === 'icon' ? '95' : '92']);
      delete result.png;
      meta[name] = result;
      console.log(name.padEnd(22), (result.w + 'x' + result.h).padEnd(9), fs.statSync(webp).size + ' bytes');
    }
    const sorted = {};
    for (const n of names) if (meta[n]) sorted[n] = meta[n];
    fs.writeFileSync(JSON_OUT, JSON.stringify(sorted, null, 1) + '\n');
    fs.writeFileSync(JAVA_OUT, java(sorted));
    console.log('wrote', path.relative(repo, JSON_OUT), 'and', path.relative(repo, JAVA_OUT));
  } finally {
    fs.rmSync(tmp, {recursive: true, force: true});
    await browser.close();
    server.close();
  }
})().catch(e => { console.error(e.message || e); process.exitCode = 1; });

/** The app's constants for the pictures: each one's size and its points, in its own pixels. */
function java(meta) {
  const f = v => (Math.round(v * 100) / 100).toFixed(2) + 'f';
  const lines = [];
  for (const [name, m] of Object.entries(meta)) {
    if (name === 'icon') continue;
    const p = m.points, origin = p.origin || p.anchor;
    const unit = p.unit ? Math.hypot(p.unit[0] - origin[0], p.unit[1] - origin[1]) : 0;
    const extra = Object.keys(p).filter(k => !['anchor', 'unit', 'origin'].includes(k) || k === 'origin' && p.anchor !== origin);
    const args = [`R.drawable.art_${name}`, m.w, m.h, f(p.anchor[0]), f(p.anchor[1]), f(unit)];
    const points = extra.map(k => `${f(p[k][0])}, ${f(p[k][1])}`);
    const constant = name.toUpperCase();
    lines.push(`    /** ${extra.length ? 'Its points: ' + extra.join(', ') + '. ' : ''}*/`);
    lines.push(`    static final Art.Sprite ${constant} = new Art.Sprite(${args.join(', ')}${points.length ? ', ' + points.join(', ') : ''});`);
  }
  return `package com.local.dasherfilter;

/**
 * The app's pictures from the website's 3D models, written by tools/art/render-sprites.cjs with the pictures
 * (res/drawable-nodpi/art_*.webp); do not edit. Each one's size in its own pixels (made for 640 dpi), the point it is
 * placed by, how many of its pixels make one unit of its model there, and its other points, in order, x then y.
 */
final class ArtSprites {
    private ArtSprites() {}

${lines.join('\n')}
}
`;
}
