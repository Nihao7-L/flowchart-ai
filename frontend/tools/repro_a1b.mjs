#!/usr/bin/env node
/**
 * A1b 复现探针（临时）：模拟「时序图」类输入（未知元素类型 + 悬空箭头绑定），
 * 看 convertScene 的产物里是否仍残留会把 Excalidraw 渲染期搞崩的坏数据。
 * 用法（frontend/ 目录下）：node tools/repro_a1b.mjs
 */
import * as esbuild from 'esbuild';
import { mkdirSync, unlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const TOOLS_DIR = dirname(fileURLToPath(import.meta.url));

const DOM_STUB = `
(function () {
  var g = globalThis; var noop = function () {};
  var ctx = new Proxy({}, { get: function (_t, k) {
    if (k === 'measureText') return function () { return { width: 0, actualBoundingBoxAscent: 0, actualBoundingBoxDescent: 0 }; };
    if (k === 'createLinearGradient' || k === 'createRadialGradient') return function () { return { addColorStop: noop }; };
    if (k === 'getImageData') return function () { return { data: new Uint8ClampedArray(4) }; };
    return noop;
  }, set: function () { return true; } });
  var makeEl = function () { return { style: {}, dataset: {}, children: [], childNodes: [],
    classList: { add: noop, remove: noop, contains: function () { return false; }, toggle: noop },
    setAttribute: noop, getAttribute: function () { return null; }, removeAttribute: noop,
    appendChild: noop, removeChild: noop, insertBefore: noop,
    addEventListener: noop, removeEventListener: noop, dispatchEvent: function () { return true; },
    focus: noop, blur: noop, click: noop, getContext: function () { return ctx; },
    getBoundingClientRect: function () { return { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0 }; },
    querySelector: function () { return null; }, querySelectorAll: function () { return []; }, contains: function () { return false; } }; };
  if (!g.document) g.document = { documentElement: makeEl(), body: makeEl(), head: makeEl(),
    createElement: function () { return makeEl(); }, createElementNS: function () { return makeEl(); },
    createTextNode: function () { return makeEl(); }, getElementById: function () { return null; },
    querySelector: function () { return null; }, querySelectorAll: function () { return []; },
    addEventListener: noop, removeEventListener: noop, fonts: { add: noop, check: function () { return true; }, forEach: noop, ready: Promise.resolve() } };
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

function dump(label, r) {
  console.log('=== ' + label + ' ===');
  console.log('  elements:', r.elements.length, 'dropped:', JSON.stringify(r.dropped), 'fatal:', r.fatal || null);
  for (const e of r.elements) {
    const sb = e.startBinding ? JSON.stringify(e.startBinding) : (e.start ? JSON.stringify(e.start) : null);
    const eb = e.endBinding ? JSON.stringify(e.endBinding) : (e.end ? JSON.stringify(e.end) : null);
    console.log('   ' + e.id + ' type=' + e.type + ' start=' + sb + ' end=' + eb);
  }
}

// 模拟「时序图」：LLM 没有时序原语，只能拿现有类型拼，且极易出现悬空绑定 / 未知类型
const seqScene = [
  { id: 'actor1', type: 'rectangle', x: 50, y: 50, width: 120, height: 60, label: { text: '用户' } },
  { id: 'actor2', type: 'rectangle', x: 400, y: 50, width: 120, height: 60, label: { text: '服务端' } },
  // 未知类型（时序图专属，但契约/渲染层都不认识）
  { id: 'life', type: 'lifeline', x: 110, y: 120, width: 2, height: 400 },
  // 悬空绑定：指向不存在的 lifeline 元素
  { id: 'm1', type: 'arrow', start: { id: 'actor1' }, end: { id: 'life' } },
  { id: 'm2', type: 'arrow', start: { id: 'life' }, end: { id: 'actor2' } },
  // 两端都悬空
  { id: 'm3', type: 'arrow', start: { id: 'ghost_a' }, end: { id: 'ghost_b' } },
];
dump('时序图类场景（修复前）', convertScene(seqScene));

// 纯悬空箭头（无任何形状）
const danglingOnly = [
  { id: 'x1', type: 'arrow', start: { id: 'nope1' }, end: { id: 'nope2' } },
];
dump('纯悬空箭头', convertScene(danglingOnly));
`;

const outDir = join(TOOLS_DIR, '..', 'node_modules', '.cache');
mkdirSync(outDir, { recursive: true });
const outfile = join(outDir, 'repro-a1b.bundle.cjs');
try {
  await esbuild.build({
    stdin: { contents: ENTRY, resolveDir: TOOLS_DIR, loader: 'ts' },
    bundle: true, platform: 'node', format: 'cjs', outfile,
    loader: { '.css': 'empty' }, banner: { js: DOM_STUB },
    external: ['react', 'react-dom'],
    define: { 'process.env.NODE_ENV': '"development"' }, logLevel: 'warning',
  });
  await import(pathToFileURL(outfile).href);
} finally {
  try { unlinkSync(outfile); } catch {}
}
