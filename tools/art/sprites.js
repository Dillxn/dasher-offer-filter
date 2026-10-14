/*
 * The app's pictures, drawn from the website's 3D models (offer-filter-site: assets/gl.js and assets/models.js) with
 * the website's lights, for tools/art/render-sprites.cjs. OfferSprites.render(name) draws one picture into a WebGL
 * canvas at twice its size, smooths it down, cuts it to what was drawn and answers
 *   {png, w, h, points: {name: [x, y], ...}, open}
 * every point a place in the picture's own pixels: where a point of the model landed. The pictures are made for a
 * 640 dpi screen (4 pixels to the dp) and decoded smaller on phones of fewer dots.
 */
(function (root) {
'use strict';
const {M, G, mix} = root.OfferGL;
const DEG = Math.PI / 180;
/** Drawn this many times larger, then smoothed down: finer edges than the multisampling alone. */
const SUPER = 2;

// The app's colours (Ui.java, and the flat drawings each picture replaces).
const APP = {accent: '#256ABF', warning: '#FAB219', mutedDay: '#65635D', mutedNight: '#898781',
  surfaceDay: '#FCFCFB', surfaceNight: '#1A1A19'};
// The chart's buildings, muted as the landscape's (DecisionChartView.facadeColor): day, night.
const FACADE = {keep: ['#78927D', '#557B63'], review: ['#BDAC85', '#998454'], decline: ['#AA8C81', '#87645E']};
// The chart's trees (DecisionChartView.treeColor): day, night.
const PINE = ['#32664F', '#9DD6B6'];
// The ground strip's houses and trees (Scenery): day, night.
const HOUSE = {wall: ['#DCD9CF', '#1C1C1B'], dark: ['#CBC7BB', '#151514'], lit: ['#F2E6C4', '#ECC27B']};
const TREE = {leaf: ['#D2DBC8', '#18201A'], trunk: ['#D3CEC2', '#1D1C1A']};

// The website's lights: the mascot's (its logo, tools/icons/render.html), the sun's, the moon's and the clouds'
// (scenery.js: sunAndMoon and the clouds), and the land's by day and by night (scenery.js: DAY and NIGHT).
const LIGHT = {
  mascot: {dir: [-.45, .5, .75], color: [.42, .41, .39], sky: [.86, .88, .92], ground: [.8, .76, .74], rim: [.45, .5, .58], gloss: [-.8, -.05, .55]},
  sun: {dir: [-.4, .6, .7], color: [.35, .3, .2], sky: [1, .96, .88], ground: [.92, .82, .66], rim: [1, .93, .7], gloss: [0, 0, 0]},
  moon: {dir: [-.45, .5, .75], color: [.42, .4, .36], sky: [.95, .93, .88], ground: [.62, .62, .68], rim: [.42, .45, .55], gloss: [-.7, .2, .6]},
  cloud: {dir: [-.3, .7, .65], color: [.32, .31, .29], sky: [.86, .87, .88], ground: [.7, .72, .78], rim: [.4, .42, .44], gloss: [0, 0, 0]},
  day: {dir: [.5, .55, .65], color: [.34, .32, .28], sky: [.8, .81, .8], ground: [.66, .65, .6], rim: [.25, .26, .24], gloss: [.6, .1, .7]},
  night: {dir: [.5, .55, .65], color: [.22, .25, .32], sky: [.86, .9, 1], ground: [.62, .66, .74], rim: [.2, .26, .36], gloss: [.6, .1, .7]}
};
const light = (r, l) => r.light(Object.assign({fog: '#ffffff', fogNear: 1e5, fogFar: 2e5}, l));
const rgb01 = hex => { const n = parseInt(hex.slice(1), 16); return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255]; };

/**
 * A renderer whose camera stands `dist` from `at`, raised `pitch` degrees, its picture w x h world units at `at`'s
 * distance, `k` pixels to the unit there (times SUPER while drawing).
 */
function frame(at, w, h, k, pitch, dist) {
  const r = root.OfferGL.create(document.getElementById('gl'), {preserve: true, alpha: true, antialias: true});
  r.size(Math.round(w * k * SUPER), Math.round(h * k * SUPER), 1);
  r.clear('#000000', 0);
  const p = pitch * DEG;
  r.camera({eye: [at[0], at[1] + dist * Math.sin(p), at[2] + dist * Math.cos(p)], at, fov: 2 * Math.atan(h / 2 / dist),
    near: dist / 20, far: dist * 20});
  return r;
}

/** Runs draw() with r.draw passing on only the calls whose index keep() allows. */
function only(r, keep, draw) {
  const real = r.draw;
  let k = 0;
  r.draw = (mesh, model, o) => { if (keep(k++)) real.call(r, mesh, model, o); };
  try { draw(); } finally { r.draw = real; }
}

/** The colour drawn where world point p lands, 0-1 a channel. */
function sample(r, p) {
  r.gl.finish();
  const q = r.project(p), px = new Uint8Array(4), gl = r.gl;
  gl.readPixels(Math.round(q[0] * r.dpr), r.canvas.height - 1 - Math.round(q[1] * r.dpr), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
  if (px[3] < 250) throw Error('nothing solid drawn at ' + p);
  return [px[0] / 255, px[1] / 255, px[2] / 255];
}

/**
 * The tint at which draw(tint) puts `target` where world point `at` lands (lit as it is, the face there shows the flat
 * drawing's colour, and its other faces are lit and shaded from it), found by drawing it a few times.
 */
function calibrate(r, draw, at, target) {
  const want = rgb01(target);
  let tint = want.slice();
  for (let pass = 0; pass < 6; pass++) {
    r.clear('#000000', 0);
    draw(tint);
    const got = sample(r, at);
    tint = tint.map((t, i) => t * want[i] / Math.max(.004, got[i]));
  }
  r.clear('#000000', 0);
  return tint;
}

/**
 * The picture smoothed down to its own size and cut to what was drawn (with a pixel round it), with where each named
 * world point landed in it. `open` names the sides a picture may run off (a building's foot, stretched in the app).
 */
function finish(r, points, open) {
  r.done(); r.gl.finish();
  const src = r.canvas, w = Math.round(src.width / SUPER), h = Math.round(src.height / SUPER);
  const small = document.createElement('canvas');
  small.width = w; small.height = h;
  const x = small.getContext('2d');
  x.imageSmoothingEnabled = true; x.imageSmoothingQuality = 'high';
  x.drawImage(src, 0, 0, w, h);
  const d = x.getImageData(0, 0, w, h).data;
  let x0 = w, y0 = h, x1 = -1, y1 = -1;
  for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
    if (d[(j * w + i) * 4 + 3] > 2) { if (i < x0) x0 = i; if (i > x1) x1 = i; if (j < y0) y0 = j; if (j > y1) y1 = j; }
  }
  if (x1 < 0) throw Error('nothing drawn');
  open = open || '';
  const cut = [x0 === 0 && !open.includes('left') && 'left', y0 === 0 && !open.includes('top') && 'top',
    x1 === w - 1 && !open.includes('right') && 'right', y1 === h - 1 && !open.includes('bottom') && 'bottom'].filter(Boolean);
  if (cut.length) throw Error('the picture runs off its ' + cut.join(', ') + ': frame it larger');
  x0 = Math.max(0, x0 - 1); y0 = Math.max(0, y0 - 1); x1 = Math.min(w - 1, x1 + 1); y1 = Math.min(h - 1, y1 + 1);
  const out = document.createElement('canvas');
  out.width = x1 - x0 + 1; out.height = y1 - y0 + 1;
  out.getContext('2d').drawImage(small, -x0, -y0);
  const at = {};
  for (const key in points) {
    const p = r.project(points[key]);
    at[key] = [Math.round((p[0] / SUPER - x0) * 100) / 100, Math.round((p[1] / SUPER - y0) * 100) / 100];
  }
  return {png: out.toDataURL('image/png'), w: out.width, h: out.height, points: at, open};
}

/* ---- The mascot (FilterHeroView): its body with the left arm, and its right arm apart, for the wave ---- */
// Pixels to the rim's radius: the design's 62 dp, 22.6 dp on screen in the band's largest ring (64 dp), 90 px at 640 dpi.
const MASCOT_K = 96;
const VIEW = 9 / Math.cos(12 * DEG);
const mascotFrame = k => frame([0, -.2, 0], 2.6, 2.2, k || MASCOT_K, 12, VIEW);
function colours(state, night) {
  const C = root.OfferModels.C;
  C.accent = state === 'on' ? APP.accent : state === 'paused' ? APP.warning : night ? APP.mutedNight : APP.mutedDay;
  C.surface = night ? APP.surfaceNight : APP.surfaceDay;
  return C;
}
/** Where the arms meet the glaze: on its front, a little forward of its sides, level with the model's origin. */
function shoulder() { const sh = root.OfferModels.coneR(0), z = .14; return [Math.sqrt(sh * sh - z * z), 0, z]; }
// art.mascot's draws, in order, with no ring and the happy face: the glaze, the sieve, the face, its shine, the cheeks,
// the right arm, the left arm.
const BODY = 0, SIEVE = 1, ARM = 5, ARM_LEFT = 6;

function mascotBody(state, night) {
  const C = colours(state, night), r = mascotFrame(), art = root.OfferModels.build(r);
  light(r, LIGHT.mascot);
  // The sieve has its holes only while on, as the flat drawing's dots; otherwise a plain plate across the mouth.
  const RIM_Y = 32 / 62, plate = state === 'on' ? null
    : r.mesh(G.lathe([[0, RIM_Y - .03], [.92, RIM_Y - .03], [.93, RIM_Y - .01]], 72, mix(C.surface, C.accent, .3)));
  // The face is the app's own, drawn over the picture (its moods and blinks), and the right arm its own picture.
  only(r, i => i === BODY || (i === SIEVE && !plate) || i === ARM_LEFT,
    () => art.mascot(M.trs([0, 0, 0]), {mood: 'happy', ring: 0}));
  if (plate) r.draw(plate, M.trs([0, 0, 0]), {spec: .2, rim: .2});
  return finish(r, {anchor: [0, 0, 0], unit: [1, 0, 0], shoulder: shoulder()});
}

function mascotArm(state, night) {
  colours(state, night);
  const r = mascotFrame(), art = root.OfferModels.build(r);
  light(r, LIGHT.mascot);
  const pass = keep => only(r, keep, () => art.mascot(M.trs([0, 0, 0]), {mood: 'happy', ring: 0}));
  // The glaze's depth alone first, so the arm comes out of it as on the whole mascot.
  r.gl.colorMask(false, false, false, false);
  pass(i => i === BODY);
  r.gl.colorMask(true, true, true, true);
  pass(i => i === ARM);
  // Placed as the body is, by the model's origin and its unit there; turned about the shoulder for the wave.
  return finish(r, {anchor: shoulder(), origin: [0, 0, 0], unit: [1, 0, 0]});
}

/* ---- The sky: the sun, the moon and a cloud (scenery.js's skyParts, lit as it lights them) ---- */
function sun() {
  // Its radius, 18 dp in the app: 72 px.
  const r = frame([0, 0, 0], 2.3, 2.3, 72, 0, 12);
  light(r, LIGHT.sun);
  r.draw(r.mesh(G.sphere(1, 20, '#f8c85a')), M.trs([0, 0, 0]), {rim: .7, spec: .25, shine: 18, fog: false});
  return finish(r, {anchor: [0, 0, 0], unit: [1, 0, 0]});
}

function moon() {
  // Its radius, 15 dp in the app: 60 px. The website's crescent: a plump tube along its middle, thickest at the lower
  // left and tapering to its two points, the bite toward its upper right.
  const r = frame([0, 0, 0], 2.3, 2.3, 60, 0, 12), pts = [];
  for (let k = 0; k <= 40; k++) { const a = (95 + 241 * k / 40) * DEG; pts.push([Math.cos(a) * .64, Math.sin(a) * .64, 0]); }
  light(r, LIGHT.moon);
  r.draw(r.mesh(G.tube(pts, u => .01 + .35 * Math.pow(Math.sin(Math.PI * u), .85), 14, '#f1e6c8', true)), M.trs([0, 0, 0]),
    {rim: .45, spec: .35, shine: 30, fog: false});
  return finish(r, {anchor: [0, 0, 0], unit: [1, 0, 0]});
}

function cloud(night) {
  // The middle puff's radius, at most 15 dp in the app: 60 px.
  const r = frame([.05, -.05, 0], 4.3, 2.5, 60, 0, 30), g = new G.Geo();
  g.add(G.sphere(1, 14, '#ffffff'));
  g.add(G.sphere(.7, 12, '#ffffff'), M.trs([-1.1, -.35, .05]));
  g.add(G.sphere(.78, 12, '#ffffff'), M.trs([1.15, -.3, .05]));
  g.add(G.box(2.25, .7, 1.1, .35, '#ffffff', 4), M.trs([.03, -.7, 0]));
  const mesh = r.mesh(g), model = M.trs([0, 0, 0], null, [1, 1, .7]);
  const draw = tint => r.draw(mesh, model, {rim: .35, spec: .05, shine: 12, fog: false, tint});
  light(r, night ? LIGHT.night : LIGHT.cloud);
  // By night the settings' sky has a dark cloud, as its flat one was.
  draw(night ? calibrate(r, draw, [0, .3, 0], APP.surfaceNight) : null);
  return finish(r, {anchor: [0, 0, 0], unit: [1, 0, 0]});
}

/* ---- The ground strip (Scenery): the car, a house and a tree ---- */
function car(night) {
  // The car is 92 of the model's 1/50 units long, the flat car's 40 dp: 87 px to the unit.
  const r = frame([0, .68, 0], 2.3, 1.75, 87, 12, 14), art = root.OfferModels.build(r);
  light(r, night ? LIGHT.night : LIGHT.day);
  art.car(M.trs([0, 0, 0]), {wheel: 0, lights: night ? 1 : 0});
  // Placed by where its near wheels meet the road.
  return finish(r, {anchor: [0, 0, 22 / 50], unit: [1, 0, 22 / 50]});
}

/** A seed for buildingParts that lights its first window and leaves its second dark, as the flat house's one window. */
function houseSeed() {
  for (let seed = 1; seed < 500; seed++) {
    let k = seed >>> 0;
    const lit = [];
    for (let c = 0; c < 2; c++) { k = (Math.imul(k, 1103515245) + 12345) >>> 0; lit.push(((k >> 16) % 9) < 4); }
    if (lit[0] && !lit[1]) return seed;
  }
  throw Error('no seed');
}

function house(night) {
  // One unit wide (the flat house's width, 22 dp at most): 88 px.
  const r = frame([0, .58, 0], 1.6, 1.6, 88, 8, 14), n = night ? 1 : 0;
  const b = root.OfferModels.buildingParts(1, .8, .8, {gable: true, seed: houseSeed(), cols: 2, rows: 1, win: [.36, .3]});
  // Its walls as buildingParts makes them, but rounded over six steps: buildingParts' one step leans each face's
  // normals into its corners, which at this size shades its front from white to grey corner to corner.
  const shell = new G.Geo();
  shell.add(G.box(1, .8, .8, .03, '#ffffff', 6), M.trs([0, .4, 0]));
  shell.add(G.extrude([[-.56, 0], [.56, 0], [0, .42]], .8 * 1.06, '#ffffff', '#ffffff'), M.trs([0, .8, 0]));
  const walls = r.mesh(shell), lit = r.mesh(b.lit), dark = r.mesh(b.dark), model = M.trs([0, 0, 0]);
  light(r, night ? LIGHT.night : LIGHT.day);
  const wallTint = calibrate(r, tint => r.draw(walls, model, {rim: .1, spec: .03, tint}), [0, .15, .4], HOUSE.wall[n]);
  r.draw(walls, model, {rim: .1, spec: .03, tint: wallTint});
  r.draw(dark, model, {rim: 0, spec: 0, tint: HOUSE.dark[n]});
  // By night its window glows, as the website's town's do.
  r.draw(lit, model, {rim: 0, spec: 0, tint: night ? [...rgb01(HOUSE.lit[1]), 1] : HOUSE.lit[0], glow: night ? 1 : 0});
  return finish(r, {anchor: [0, 0, .4], unit: [1, 0, .4]});
}

function tree(night) {
  // The website's lollipop tree, about 2 units tall (the flat tree's 25 dp at most): 50 px to the unit.
  const r = frame([0, 1.0, 0], 1.2, 2.3, 50, 8, 14), n = night ? 1 : 0, t = root.OfferModels.treeParts();
  const leaf = r.mesh(t.leaf), trunk = r.mesh(t.trunk), model = M.trs([0, 0, 0]);
  light(r, night ? LIGHT.night : LIGHT.day);
  const leafTint = calibrate(r, tint => r.draw(leaf, model, {rim: .2, spec: .04, tint}), [0, 1.35, 0], TREE.leaf[n]);
  const trunkTint = calibrate(r, tint => r.draw(trunk, model, {rim: .1, spec: 0, tint}), [0, .5, 0], TREE.trunk[n]);
  r.draw(trunk, model, {rim: .1, spec: 0, tint: trunkTint});
  r.draw(leaf, model, {rim: .2, spec: .04, tint: leafTint});
  return finish(r, {anchor: [0, 0, 0], unit: [1, 0, 0]});
}

/* ---- The chart (DecisionChartView): a building's top and a slice of its front, and a tree's crown ---- */
function block(group, night) {
  // A rounded box one unit wide (the chart's widest building, 21 dp: 84 px), seen almost square on and a little from
  // above, so its roof shows; the app stretches the slice of its front under the roof down to the street.
  const r = frame([0, 2.35, 0], 1.6, 1.7, 84, 6, 60), mesh = r.mesh(G.box(1, 3, .8, .15, '#ffffff', 6));
  const model = M.trs([0, 1.5, 0]), draw = tint => r.draw(mesh, model, {rim: .1, spec: .03, tint});
  light(r, night ? LIGHT.night : LIGHT.day);
  draw(calibrate(r, draw, [0, 2.2, .4], FACADE[group][night ? 1 : 0]));
  return finish(r, {anchor: [0, 3, .4], unit: [1, 3, .4], left: [-.5, 2.2, .4], right: [.5, 2.2, .4],
    capEnd: [0, 2.75, .4], slice: [0, 2.2, .4]}, 'bottom');
}

function pine(night) {
  // The flat tree's slim evergreen in the round: its two shelves where the flat one's are, a unit wide (12 dp at most:
  // 48 px), its crown three units tall; the app stretches it to each tree's height.
  const r = frame([0, 1.45, 0], 1.3, 3.3, 48, 6, 60);
  const profile = [[0, 3], [.3, 1.71], [.3, 1.71], [.15, 1.71], [.15, 1.71], [.43, .84], [.43, .84], [.24, .84],
    [.24, .84], [.5, 0], [.5, 0], [0, 0]];
  const mesh = r.mesh(G.lathe(profile, 32, '#ffffff')), model = M.trs([0, 0, 0]);
  const draw = tint => r.draw(mesh, model, {rim: .2, spec: .04, tint});
  light(r, night ? LIGHT.night : LIGHT.day);
  draw(calibrate(r, draw, [0, 1.2, .31], PINE[night ? 1 : 0]));
  return finish(r, {anchor: [0, 3, 0], base: [0, 0, 0], left: [-.5, 0, 0], right: [.5, 0, 0]});
}

/* ---- The launcher icon's foreground: the 108 dp vector's pale disc and ensō, with the 3D mascot in it ---- */
function icon() {
  // 432 px: the vector's 108 units at 4 px. Its group scales by 0.8 about (54, 56); the mascot's model origin stands
  // at (54, 54) in it and its rim's radius is 23.4 units.
  const size = 432 * SUPER, px = 4 * SUPER, k = 23.4 * .8 * 4;
  colours('on', false);
  const r = mascotFrame(k), art = root.OfferModels.build(r);
  light(r, LIGHT.mascot);
  art.mascot(M.trs([0, 0, 0]), {mood: 'happy', ring: 0});
  r.done(); r.gl.finish();
  const o = r.project([0, 0, 0]);
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const x = c.getContext('2d');
  x.setTransform(px * .8, 0, 0, px * .8, px * 54 * .2, px * 56 * .2);
  x.fillStyle = 'rgba(245, 250, 255, ' + (0xA6 / 255) + ')';
  x.fill(new Path2D('M27.5,54a26.5,26.5 0 1,0 53,0a26.5,26.5 0 1,0 -53,0Z'));
  x.lineCap = 'round';
  for (const [colour, width, d] of [['#256ABF', 5, 'M36.22,79.39A31,31 0 1,1 73.93,77.75'],
    ['#256ABF', 7, 'M24.87,43.4A31,31 0 0,1 77.75,34.07'], ['rgba(37, 106, 191, ' + (0xB3 / 255) + ')', 2.4, 'M73.93,77.75A31,31 0 0,1 62.02,83.94']]) {
    x.strokeStyle = colour; x.lineWidth = width; x.stroke(new Path2D(d));
  }
  x.setTransform(1, 0, 0, 1, 0, 0);
  x.drawImage(r.canvas, px * 54 - o[0] * r.dpr, px * (56 + (54 - 56) * .8) - o[1] * r.dpr);
  const out = document.createElement('canvas');
  out.width = out.height = 432;
  const y = out.getContext('2d');
  y.imageSmoothingEnabled = true; y.imageSmoothingQuality = 'high';
  y.drawImage(c, 0, 0, 432, 432);
  return {png: out.toDataURL('image/png'), w: 432, h: 432, points: {}, open: 'all'};
}

const SPRITES = {
  mascot_on: () => mascotBody('on', false), mascot_paused: () => mascotBody('paused', false),
  mascot_off: () => mascotBody('off', false), mascot_on_night: () => mascotBody('on', true),
  mascot_paused_night: () => mascotBody('paused', true), mascot_off_night: () => mascotBody('off', true),
  arm_on: () => mascotArm('on', false), arm_paused: () => mascotArm('paused', false),
  arm_off: () => mascotArm('off', false), arm_off_night: () => mascotArm('off', true),
  sun, moon, cloud: () => cloud(false), cloud_night: () => cloud(true),
  car: () => car(false), car_night: () => car(true), house: () => house(false), house_night: () => house(true),
  tree: () => tree(false), tree_night: () => tree(true),
  block_keep: () => block('keep', false), block_review: () => block('review', false),
  block_decline: () => block('decline', false), block_keep_night: () => block('keep', true),
  block_review_night: () => block('review', true), block_decline_night: () => block('decline', true),
  pine: () => pine(false), pine_night: () => pine(true),
  icon
};

root.OfferSprites = {
  names: Object.keys(SPRITES),
  render(name) {
    if (!SPRITES[name]) throw Error('no picture named ' + name);
    return SPRITES[name]();
  }
};
})(window);
