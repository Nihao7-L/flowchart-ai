#!/usr/bin/env node
/**
 * Probe: does the adapter / Excalidraw converter RESIZE containers?
 *
 * Feed the raw LLM scene (dumped from backend.log) into convertScene() and
 * compare input width/height with output width/height. If sizes grow, the
 * layout engine's node model cannot hold and rendered boxes overlap.
 *
 * ASCII only on purpose: this file must survive editors that mangle CJK.
 */
import * as esbuild from 'esbuild';
import { mkdirSync, unlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const TOOLS_DIR = dirname(fileURLToPath(import.meta.url));

const DOM_STUB = `
(function () {
  var g = globalThis;
  var noop = function () {};
  var ctx = new Proxy({}, {
    get: function (_t, k) {
      if (k === 'measureText') return function () { return { width: 0, actualBoundingBoxAscent: 0, actualBoundingBoxDescent: 0 }; };
      if (k === 'createLinearGradient' || k === 'createRadialGradient') return function () { return { addColorStop: noop }; };
      if (k === 'getImageData') return function () { return { data: new Uint8ClampedArray(4) }; };
      return noop;
    },
    set: function () { return true; }
  });
  var makeEl = function () {
    return {
      style: {}, dataset: {}, children: [], childNodes: [],
      classList: { add: noop, remove: noop, toggle: noop, contains: function () { return false; } },
      setAttribute: noop, getAttribute: function () { return null; }, removeAttribute: noop,
      appendChild: function (c) { return c; }, removeChild: function (c) { return c; },
      insertBefore: function (c) { return c; }, addEventListener: noop, removeEventListener: noop,
      getContext: function () { return ctx; },
      getBoundingClientRect: function () { return { x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0 }; },
      focus: noop, blur: noop, remove: noop, contains: function () { return false; },
      querySelector: function () { return null; }, querySelectorAll: function () { return []; },
      attachShadow: function () { return makeEl(); }
    };
  };
  if (!g.document) {
    g.document = {
      documentElement: makeEl(), body: makeEl(), head: makeEl(),
      createElement: function () { return makeEl(); },
      createElementNS: function () { return makeEl(); },
      createTextNode: function () { return makeEl(); },
      getElementById: function () { return null; },
      querySelector: function () { return null; }, querySelectorAll: function () { return []; },
      addEventListener: noop, removeEventListener: noop,
      fonts: { add: noop, check: function () { return true; }, forEach: noop, ready: Promise.resolve() }
    };
  }
  if (!g.window) g.window = g;
  if (!g.navigator) g.navigator = { userAgent: 'node', platform: 'node', languages: ['zh-CN'], maxTouchPoints: 0 };
  if (!g.location) g.location = { href: 'http://localhost/', search: '', hash: '', pathname: '/', origin: 'http://localhost' };
  if (g.devicePixelRatio === undefined) g.devicePixelRatio = 1;
  if (!g.matchMedia) g.matchMedia = function () { return { matches: false, media: '', addEventListener: noop, removeEventListener: noop, addListener: noop, removeListener: noop }; };
  if (!g.ResizeObserver) g.ResizeObserver = function () { return { observe: noop, unobserve: noop, disconnect: noop }; };
  if (!g.IntersectionObserver) g.IntersectionObserver = function () { return { observe: noop, unobserve: noop, disconnect: noop, takeRecords: function () { return []; } }; };
  if (!g.MutationObserver) g.MutationObserver = function () { return { observe: noop, disconnect: noop, takeRecords: function () { return []; } }; };
  if (!g.FontFace) g.FontFace = function (f, s) { this.family = f; this.source = s; this.load = function () { return Promise.resolve(this); }; };
  if (!g.getComputedStyle) g.getComputedStyle = function () { return { getPropertyValue: function () { return ''; }, font: '', fontFamily: '' }; };
  if (!g.requestAnimationFrame) g.requestAnimationFrame = function () { return 0; };
  if (!g.cancelAnimationFrame) g.cancelAnimationFrame = noop;
  if (!g.HTMLElement) g.HTMLElement = function () {};
  if (!g.Element) g.Element = function () {};
  if (!g.SVGElement) g.SVGElement = function () {};
  if (!g.Image) g.Image = function () { this.width = 0; this.height = 0; };
  if (!g.localStorage) g.localStorage = { getItem: function () { return null; }, setItem: noop, removeItem: noop, clear: noop, key: function () { return null; }, length: 0 };
  if (g.URL && !g.URL.createObjectURL) g.URL.createObjectURL = function () { return 'blob:stub'; };
})();
`;

const ENTRY = `
import { convertScene } from '../src/lib/sceneAdapter';
import { readFileSync } from 'node:fs';

const raw = JSON.parse(readFileSync(SCENE_PATH, 'utf8'));
const input = raw.elements;
const r = convertScene(input);
console.log('fatal=' + r.fatal + ' dropped=' + r.dropped.length + ' in=' + input.length + ' out=' + r.elements.length);

const byId = {};
for (const e of r.elements) byId[e.id] = e;

let grew = 0, shrank = 0, same = 0;
const rows = [];
for (const src of input) {
  const out = byId[src.id];
  if (!out) continue;
  if (src.type === 'frame') {
    rows.push(['FRAME', src.id, 'in ' + src.x + ',' + src.y + ' ' + src.width + 'x' + src.height,
               'out ' + out.x + ',' + out.y + ' ' + out.width + 'x' + out.height]);
    continue;
  }
  if (src.type !== 'rectangle' && src.type !== 'ellipse' && src.type !== 'diamond') continue;
  const dw = out.width - src.width, dh = out.height - src.height;
  if (dw > 0.01 || dh > 0.01) grew++;
  else if (dw < -0.01 || dh < -0.01) shrank++;
  else same++;
  if (dw !== 0 || dh !== 0) {
    const label = src.label && src.label.text ? src.label.text.replace(/\\n/g, '|') : '';
    rows.push(['SHAPE', src.id + ' [' + label + ']', src.width + 'x' + src.height, (out.width + 'x' + out.height), 'd=' + dw + ',' + dh]);
  }
}
console.log('sizes: grew=' + grew + ' shrank=' + shrank + ' same=' + same);
for (const row of rows) console.log('  ' + row.join(' | '));

console.log('--- labels (text elements attached to shapes) ---');
for (const e of r.elements) {
  if (e.type !== 'text' || !e.containerId) continue;
  const box = byId[e.containerId];
  console.log('  text ' + JSON.stringify(e.text) + ' font=' + e.fontSize
    + ' -> x=' + e.x + ' y=' + e.y + ' w=' + e.width + ' h=' + e.height
    + ' | container ' + (box ? box.width + 'x' + box.height : '?'));
}
`;

const outDir = join(TOOLS_DIR, '..', 'node_modules', '.cache');
mkdirSync(outDir, { recursive: true });
const outfile = join(outDir, 'probe-scene-fit.bundle.cjs');

try {
  await esbuild.build({
    stdin: { contents: ENTRY, resolveDir: TOOLS_DIR, loader: 'ts' },
    bundle: true,
    platform: 'node',
    format: 'cjs',
    outfile,
    loader: { '.css': 'empty' },
    banner: { js: DOM_STUB },
    external: ['react', 'react-dom'],
    define: {
      'process.env.NODE_ENV': '"development"',
      SCENE_PATH: JSON.stringify(join(outDir, 'llm-case02.json').replace(/\\/g, '/')),
    },
    logLevel: 'warning',
  });
  await import(pathToFileURL(outfile).href);
} finally {
  try {
    unlinkSync(outfile);
  } catch {
    /* ignore */
  }
}
