#!/usr/bin/env node
/**
 * 场景适配层冒烟测试（sceneAdapter smoke test）
 *
 * 用法（在 frontend/ 目录下）：node tools/scene-smoke.mjs
 *
 * 为什么需要它：
 * LLM 输出是**无界**的 —— scene.schema.json 只约束了我们想到的字段，
 * 而 Excalidraw 的 convertToExcalidrawElements 还有我们控制不了的隐含前提。
 * 2026-09-16 就因此翻车两次（text 漏字段 / frame 缺 children），
 * 且异常被 catch 静默吞掉，表现为"聊天说已生成 N 个元素、画布一片空白"。
 * 这个脚本把这类"某个元素形状让转换器整批抛错"的场景固化成回归用例。
 *
 * 依赖说明：不新增任何依赖 —— esbuild 由 vite 传递带入 node_modules。
 * 由于 Excalidraw 在模块加载期就要访问浏览器 API，脚本用 DOM 桩作为 esbuild banner 前置。
 */
import * as esbuild from 'esbuild';
import { mkdirSync, unlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/** 本脚本所在目录（tools/）：作为 stdin 入口的解析基准，保证 '../src/...' 指向 frontend/src */
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
      classList: { add: noop, remove: noop, contains: function () { return false; }, toggle: noop },
      setAttribute: noop, getAttribute: function () { return null; }, removeAttribute: noop,
      appendChild: noop, removeChild: noop, insertBefore: noop,
      addEventListener: noop, removeEventListener: noop, dispatchEvent: function () { return true; },
      focus: noop, blur: noop, click: noop,
      getContext: function () { return ctx; },
      getBoundingClientRect: function () { return { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0 }; },
      querySelector: function () { return null; }, querySelectorAll: function () { return []; }, contains: function () { return false; }
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
import { convertScene, isModelElement } from '../src/lib/sceneAdapter';
// 用 Excalidraw 自己的取景算法做判据：它是"画布空白"的直接责任方
import { getCommonBounds } from '@excalidraw/excalidraw';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const rect = (over) => Object.assign({ id: 'r1', type: 'rectangle', x: 100, y: 100, width: 120, height: 80, label: { text: '矩形' } }, over || {});

const cases = [
  {
    // 2026-09-17 拍板「方案 a」：契约不含 children，由适配层补 children:[] ——
    // 这条用例就是那个决定的锁。转换器的**产物**里不会有 children（它只是入参
    // 要求，产出改用 boundElements/frameId），所以断言只能是"这条路走得通"：
    // 一旦有人删掉适配层的 children 补丁，这里立刻 fatal + dropped 而红。
    name: 'frame 元素无 children（LLM 常见输出）：适配层补丁必须让整批转换走通',
    input: [{ id: 'grp', type: 'frame', x: 0, y: 0, width: 600, height: 300, name: '登录流程' }],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (r.dropped.length ? '不应丢弃: ' + r.dropped.join(',') : null)
      || (r.elements.length !== 1 ? '应渲染 1 个元素，实际 ' + r.elements.length : null)
      || (r.elements[0].type !== 'frame' ? '类型应为 frame' : null),
  },
  {
    name: 'text 元素缺 text 字段',
    input: [{ id: 't1', type: 'text', x: 10, y: 10, fontSize: 14 }],
    check: (r) => (r.elements.length !== 0 ? '应跳过该元素' : null)
      || (!r.dropped.includes('t1') ? '应记录被跳过的 id' : null),
  },
  {
    name: '形状 + 箭头 + frame 混合（含曾导致整批失败的 frame）',
    input: [
      { id: 'grp', type: 'frame', x: 0, y: 100, width: 1200, height: 300, name: '登录流程' },
      { id: 'start', type: 'ellipse', x: 50, y: 200, width: 80, height: 60, label: { text: '开始' } },
      rect({ id: 'input', x: 230, y: 190 }),
      { id: 'check', type: 'diamond', x: 430, y: 180, width: 120, height: 100, label: { text: '输入校验' } },
      { id: 'bad', type: 'text', x: 430, y: 320, text: '提示：输入格式错误', fontSize: 14 },
      { id: 'a1', type: 'arrow', start: { id: 'start' }, end: { id: 'input' } },
      { id: 'a2', type: 'arrow', start: { id: 'input' }, end: { id: 'check' }, label: { text: '是' } },
    ],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (r.dropped.length ? '不应丢弃: ' + r.dropped.join(',') : null)
      || (r.elements.length < 7 ? '元素数应 >= 7，实际 ' + r.elements.length : null),
  },
  {
    name: '转换产物带 AI 来源标记（场景合并依赖它区分用户手绘）',
    input: [rect()],
    check: (r) => (r.elements.every((e) => isModelElement(e)) ? null : '存在未打标记的元素'),
  },
  {
    name: 'id 不被重新生成（箭头绑定与坐标回写依赖它）',
    input: [rect({ id: 'custom_a' })],
    check: (r) => (r.elements.some((e) => e.id === 'custom_a') ? null : '后端 id 丢失: ' + r.elements.map((e) => e.id).join(',')),
  },
  {
    name: '绑定式箭头缺 x/y（曾让 getCommonBounds 变 NaN → 画布空白真凶）',
    input: [
      { id: 'n1', type: 'rectangle', x: 0, y: 0, width: 100, height: 60, label: { text: 'A' } },
      { id: 'n2', type: 'rectangle', x: 300, y: 0, width: 100, height: 60, label: { text: 'B' } },
      { id: 'a1', type: 'arrow', start: { id: 'n1' }, end: { id: 'n2' } },
    ],
    check: (r) => {
      const arrow = r.elements.find((e) => e.type === 'arrow');
      if (!arrow) return '箭头未被转换出来';
      if (!Number.isFinite(arrow.x) || !Number.isFinite(arrow.y)) {
        return '箭头 x/y 非有限数字: ' + arrow.x + ',' + arrow.y;
      }
      if (!Array.isArray(arrow.points) || arrow.points.length < 2) return '箭头缺 points';
      const bounds = getCommonBounds(r.elements);
      if (bounds.some((v) => !Number.isFinite(v))) {
        return '取景边界含 NaN（会导致视口损坏、画布全空）: ' + bounds.join(',');
      }
      return null;
    },
  },
  {
    name: '箭头端点落在图形边缘（不能穿过图形与文字重叠）',
    input: [
      { id: 'n1', type: 'rectangle', x: 0, y: 0, width: 100, height: 60 },
      { id: 'n2', type: 'rectangle', x: 300, y: 0, width: 100, height: 60 },
      { id: 'a1', type: 'arrow', start: { id: 'n1' }, end: { id: 'n2' } },
    ],
    check: (r) => {
      const arrow = r.elements.find((e) => e.type === 'arrow');
      if (!arrow) return '箭头未被转换出来';
      if (!Array.isArray(arrow.points) || arrow.points.length < 2) return '箭头缺 points';
      const first = arrow.points[0];
      const last = arrow.points[arrow.points.length - 1];
      const absStart = { x: arrow.x + first[0], y: arrow.y + first[1] };
      const absEnd = { x: arrow.x + last[0], y: arrow.y + last[1] };
      // n1 右边缘 x=100，n2 左边缘 x=300，两者中心高度 y=30
      if (Math.abs(absStart.x - 100) > 1) return '起点未落在 n1 右边缘: x=' + absStart.x;
      if (Math.abs(absEnd.x - 300) > 1) return '终点未落在 n2 左边缘: x=' + absEnd.x;
      if (Math.abs(absStart.y - 30) > 1) return '起点 y 应为 30，实际 ' + absStart.y;
      if (Math.abs(absEnd.y - 30) > 1) return '终点 y 应为 30，实际 ' + absEnd.y;
      return null;
    },
  },
  {
    name: '全类型混合场景的取景边界必须可计算（无 NaN）',
    input: [
      { id: 'grp', type: 'frame', x: -50, y: -50, width: 900, height: 400, name: '流程' },
      { id: 's1', type: 'ellipse', x: 0, y: 0, width: 100, height: 60, label: { text: '开始' } },
      { id: 's2', type: 'diamond', x: 300, y: 0, width: 120, height: 90, label: { text: '判断' } },
      { id: 's3', type: 'rectangle', x: 600, y: 0, width: 120, height: 60 },
      { id: 't1', type: 'text', x: 600, y: 80, text: '说明', fontSize: 14 },
      { id: 'l1', type: 'line', x: 0, y: 200, points: [[0, 0], [80, 40]] },
      { id: 'a1', type: 'arrow', start: { id: 's1' }, end: { id: 's2' } },
      { id: 'a2', type: 'arrow', start: { id: 's2' }, end: { id: 's3' } },
      { id: 'a3', type: 'arrow', x: 0, y: 300, points: [[0, 0], [100, 0]] },
    ],
    check: (r) => {
      if (r.fatal) return '不应 fatal: ' + r.fatal;
      if (r.dropped.length) return '不应丢弃: ' + r.dropped.join(',');
      const bounds = getCommonBounds(r.elements);
      if (bounds.some((v) => !Number.isFinite(v))) {
        const dump = r.elements
          .map((e) => e.id + ':' + e.type + '(x=' + e.x + ',y=' + e.y + ',w=' + e.width + ',h=' + e.height + ')')
          .join(' ; ');
        return '取景边界含 NaN: ' + bounds.join(',') + ' || 元素明细: ' + dump;
      }
      const bad = r.elements.filter((e) => !Number.isFinite(e.x) || !Number.isFinite(e.y));
      if (bad.length) return '存在坐标非有限的元素: ' + bad.map((e) => e.id).join(',');
      return null;
    },
  },
  {
    name: 'freedraw 被安全跳过（取景边界算不出来，会毁掉整张图）',
    input: [
      { id: 'f1', type: 'freedraw', x: 200, y: 200, points: [[0, 0], [10, 10], [20, 5]] },
      rect({ id: 'keep', x: 0, y: 0 }),
    ],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (!r.dropped.includes('f1') ? '应跳过 freedraw' : null)
      || (r.elements.length < 2 ? '矩形与其标签应保留，实际 ' + r.elements.length : null)
      || (getCommonBounds(r.elements).some((v) => !Number.isFinite(v)) ? '剩余元素取景边界仍有 NaN' : null),
  },
  {
    name: '空输入（新建对话清空）',
    input: [],
    check: (r) => (r.elements.length || r.dropped.length || r.fatal ? '空输入应得到空场景' : null),
  },
  {
    name: 'v2-40 P0 新增渲染层字段必须透传到产物（被白名单吞掉 = 改了没反应）',
    input: [
      { id: 's1', type: 'rectangle', x: 0, y: 0, width: 160, height: 80, label: { text: '订单' }, strokeStyle: 'dashed', fillStyle: 'zigzag', roundness: { type: 3 }, link: 'https://example.com/spec' },
      { id: 's2', type: 'rectangle', x: 300, y: 0, width: 160, height: 80, label: { text: '库存' } },
      { id: 'a1', type: 'arrow', start: { id: 's1' }, end: { id: 's2' }, strokeStyle: 'dotted', startArrowhead: 'bar', endArrowhead: 'crowfoot_many' },
      { id: 't1', type: 'text', x: 0, y: 200, text: '说明', fontSize: 14, verticalAlign: 'middle', autoResize: true },
    ],
    check: (r) => {
      if (r.fatal) return '不应 fatal: ' + r.fatal;
      if (r.dropped.length) return '不应丢弃: ' + r.dropped.join(',');
      const rect = r.elements.find((e) => e.id === 's1');
      if (!rect) return '未找到 s1';
      if (rect.strokeStyle !== 'dashed') return '图形的 strokeStyle 被吞: ' + rect.strokeStyle;
      if (!rect.roundness || rect.roundness.type !== 3) return 'roundness 被吞: ' + JSON.stringify(rect.roundness);
      if (rect.fillStyle !== 'zigzag') return 'fillStyle 的 zigzag 被吞: ' + rect.fillStyle;
      if (rect.link !== 'https://example.com/spec') return 'link 被吞: ' + rect.link;
      const arrow = r.elements.find((e) => e.id === 'a1');
      if (!arrow) return '未找到 a1';
      if (arrow.endArrowhead !== 'crowfoot_many') return 'endArrowhead 被吞: ' + arrow.endArrowhead;
      if (arrow.startArrowhead !== 'bar') return 'startArrowhead 被吞: ' + arrow.startArrowhead;
      if (!arrow.endBinding) return '端点形状与绑定应共存，但 endBinding 丢了';
      const text = r.elements.find((e) => e.id === 't1');
      if (!text) return '未找到 t1';
      if (text.verticalAlign !== 'middle') return 'verticalAlign 被吞: ' + text.verticalAlign;
      if (text.autoResize !== true) return 'autoResize 被吞: ' + text.autoResize;
      return null;
    },
  },
  {
    name: '契约字段与前端白名单一致（自动守卫：漏一个就是"改了没反应"）',
    input: [],
    check: () => {
      const FRONTEND = join(__dirname, '..', '..');
      const adapterSrc = readFileSync(join(FRONTEND, 'src', 'lib', 'sceneAdapter.ts'), 'utf8');
      // 不用跨行正则、也不用"反斜杠加字母"的转义写法：本脚本的 ENTRY 是模板
      // 字符串，反斜杠会被 JS 先吃掉一层，导致正则或字符串在构建期就变形
      const lines = adapterSrc.split(String.fromCharCode(10));
      const start = lines.findIndex((l) => l.indexOf('const SKELETON_KEYS = [') >= 0);
      const end = lines.findIndex((l, i) => i > start && l.indexOf('] as const;') >= 0);
      if (start < 0 || end < 0) return '无法从 sceneAdapter.ts 里解析出 SKELETON_KEYS';
      const block = lines.slice(start, end + 1).join(' ');
      const keys = new Set((block.match(/'[^']+'/g) || []).map((s) => s.slice(1, -1)));
      const schemaPath = join(FRONTEND, '..', 'src', 'main', 'resources', 'schemas', 'scene.schema.json');
      const schema = JSON.parse(readFileSync(schemaPath, 'utf8'));
      const fields = new Set();
      for (const branch of ['shape', 'arrow', 'line', 'text', 'frame', 'freedraw']) {
        const props = (schema.definitions[branch] || {}).properties || {};
        for (const k of Object.keys(props)) fields.add(k);
      }
      const missing = [...fields].filter((f) => !keys.has(f));
      if (missing.length) {
        return '契约里有、前端白名单缺（会被静默丢弃）: ' + missing.join(', ');
      }
      return null;
    },
  },
];

let failed = 0;
for (const c of cases) {
  const r = convertScene(c.input);
  const err = c.check(r);
  if (err) {
    failed++;
    console.log('FAIL | ' + c.name + ' | ' + err
      + ' | got=' + JSON.stringify({ n: r.elements.length, dropped: r.dropped, fatal: r.fatal }));
  } else {
    console.log('PASS | ' + c.name + ' | ' + r.elements.length + ' 个画布元素');
  }
}
if (failed > 0) {
  console.log('\\nSMOKE FAILED: ' + failed + '/' + cases.length);
  process.exit(1);
}
console.log('\\nSMOKE PASSED: ' + cases.length + '/' + cases.length);
`;

// 产物必须落在 frontend/ 内：react / react-dom 是 external，Node 需从产物位置向上解析
// 到 frontend/node_modules。放 node_modules/.cache 下可避免污染 git 工作区。
const outDir = join(TOOLS_DIR, '..', 'node_modules', '.cache');
mkdirSync(outDir, { recursive: true });
const outfile = join(outDir, 'scene-smoke.bundle.cjs');

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
    define: { 'process.env.NODE_ENV': '"development"' },
    logLevel: 'warning',
  });

  await import(pathToFileURL(outfile).href);
} finally {
  try {
    unlinkSync(outfile);
  } catch {
    /* 忽略清理失败 */
  }
}
