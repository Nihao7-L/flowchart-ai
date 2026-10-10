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
    // 2026-09-17 拍板「方案 a」：契约不含 children，由适配层补 children:[]。
    // 2026-09-18 改为「透传 + 兜底」（后端 ScenePlanner 现在会算并下发成员表），
    // 但**这条用例的判据完全没变**：补丁的作用是让转换走得通。
    // 转换器的**产物**里不会有 children（它只是入参要求，产出改用 frameId），
    // 所以断言只能是"这条路走得通"：一旦有人删掉适配层的兜底，
    // 这里立刻 fatal + dropped 而红。
    name: 'frame 元素无 children（未算成员时）：适配层兜底必须让整批转换走通',
    input: [{ id: 'grp', type: 'frame', x: 0, y: 0, width: 600, height: 300, name: '登录流程' }],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (r.dropped.length ? '不应丢弃: ' + r.dropped.join(',') : null)
      || (r.elements.length !== 1 ? '应渲染 1 个元素，实际 ' + r.elements.length : null)
      || (r.elements[0].type !== 'frame' ? '类型应为 frame' : null),
  },
  {
    // v2-40 P1 的核心断言：children **有语义**，不是"渲染器细节"。
    // 传成员 id 列表时，成员的 frameId 被反填为框的 id —— 这是「框 ↔ 内容」
    // 归属的唯一数据通道（拖框/删框能不能带走内容全看它）。
    // 若有人把透传改成无条件补 []，这条会红（frameId 恒 null）。
    name: 'frame.children 透传后成员 frameId 被反填（框与内容的归属通道）',
    input: [
      { id: 'grp', type: 'frame', x: 0, y: 0, width: 600, height: 400, name: '下单域', children: ['n1', 'n2'] },
      { id: 'n1', type: 'rectangle', x: 40, y: 60, width: 120, height: 60, label: { text: '创建订单' } },
      { id: 'n2', type: 'rectangle', x: 260, y: 60, width: 120, height: 60, label: { text: '扣库存' } },
    ],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (r.dropped.length ? '不应丢弃: ' + r.dropped.join(',') : null)
      || (() => {
        for (const id of ['n1', 'n2']) {
          const el = r.elements.find((e) => e.id === id);
          if (!el) return '未找到成员 ' + id;
          if (el.frameId !== 'grp') return id + ' 的 frameId 应为 grp，实际 ' + el.frameId;
        }
        return null;
      })(),
  },
  {
    // children 里的脏数据（LLM 越权填的）：指向不存在的 id、指向自己、
    // 甚至不是字符串 —— 都必须被静默滤掉而不是让整批转换抛错。
    name: 'frame.children 脏数据被滤掉（指向不存在/自己/非字符串）',
    input: [
      { id: 'grp', type: 'frame', x: 0, y: 0, width: 600, height: 400, name: '下单域', children: ['n1', 'ghost', 'grp', 42 as unknown as string] },
      { id: 'n1', type: 'rectangle', x: 40, y: 60, width: 120, height: 60, label: { text: '创建订单' } },
    ],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (r.dropped.length ? '不应丢弃: ' + r.dropped.join(',') : null)
      || (() => {
        const n1 = r.elements.find((e) => e.id === 'n1');
        if (!n1) return '未找到 n1';
        if (n1.frameId !== 'grp') return '合法成员应仍被认领，实际 frameId=' + n1.frameId;
        return null;
      })(),
  },
  {
    // 2026-09-18 真实电池实测缺陷：转换器把 frame 的几何当"可选输入"
    // （data/transform.ts 里 frameY = frame.y || minY —— 注意那个 ||），
    // 而 **数值 0 是 falsy** —— 后端算出的 y = 0（最上面一排模块）被当成
    // "没给"，换成"成员包围盒上沿 − 10"：框从 0 掉到 70，既出 20px 网格、
    // 又和它自己框住的节点错位。适配层必须把契约值恢复回去。
    name: 'frame 在 y=0（falsy 陷阱）：几何必须保持契约原值',
    input: [
      { id: 'grp', type: 'frame', x: 0, y: 0, width: 400, height: 300, name: '顶层模块', children: ['n1'] },
      { id: 'n1', type: 'rectangle', x: 40, y: 40, width: 120, height: 60, label: { text: '节点' } },
    ],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (() => {
        const f = r.elements.find((e) => e.id === 'grp');
        if (!f) return '未找到 grp';
        if (f.x !== 0 || f.y !== 0) {
          return 'frame 坐标应保持 (0,0)，实际 (' + f.x + ',' + f.y + ')';
        }
        if (f.width !== 400 || f.height !== 300) {
          return 'frame 尺寸应保持 400x300，实际 ' + f.width + 'x' + f.height;
        }
        return null;
      })(),
  },
  {
    // 同一根因的另一面：契约给了几何，就不该被"按成员包围盒重算"覆盖 ——
    // 后端的 frame 是模块包围盒 + 内边距算出来的，不是从成员猜的。
    name: 'frame 几何不被"成员包围盒"改写（契约优先）',
    input: [
      { id: 'grp', type: 'frame', x: 200, y: 200, width: 900, height: 700, name: '大框', children: ['n1'] },
      { id: 'n1', type: 'rectangle', x: 240, y: 280, width: 120, height: 60, label: { text: '小节点' } },
    ],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (() => {
        const f = r.elements.find((e) => e.id === 'grp');
        if (!f) return '未找到 grp';
        if (f.x !== 200 || f.y !== 200 || f.width !== 900 || f.height !== 700) {
          return 'frame 几何被改写为 (' + f.x + ',' + f.y + ') ' + f.width + 'x' + f.height;
        }
        return null;
      })(),
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
    name: 'freedraw 保留并参与取景（白板手绘能力：points 包围盒 + 基类字段补齐）',
    input: [
      { id: 'f1', type: 'freedraw', x: 200, y: 200, points: [[0, 0], [10, 10], [20, 5]] },
      rect({ id: 'keep', x: 0, y: 0 }),
    ],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (r.dropped.includes('f1') ? 'freedraw 不应被跳过（已是白板一等能力）' : null)
      || (r.elements.length < 2 ? '矩形与其标签、freedraw 都应保留，实际 ' + r.elements.length : null)
      || (getCommonBounds(r.elements).some((v) => !Number.isFinite(v)) ? '取景边界不应有 NaN' : null),
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
      for (const branch of ['shape', 'arrow', 'line', 'text', 'frame', 'freedraw', 'image', 'embeddable', 'magicframe']) {
        const props = (schema.definitions[branch] || {}).properties || {};
        for (const k of Object.keys(props)) fields.add(k);
      }
      // 显式豁免：这些字段在契约里（后端排版/语义要用）但**刻意不搬进渲染层**。
      // group = 模块名；relation = 箭头的语义关系类型（flow/call/data/async/
      // inherit/ER 基数等）—— 都是语义层字段，渲染器（Excalidraw）不认识，
      // 搬过去只会被忽略或抛错；语义层与渲染层分开正是 v2-40「语义编译」的架构。
      const EXEMPT = new Set(['group', 'relation']);
      const missing = [...fields].filter((f) => !keys.has(f) && !EXEMPT.has(f));
      if (missing.length) {
        return '契约里有、前端白名单缺（会被静默丢弃）: ' + missing.join(', ');
      }
      // 反向守卫：白名单里有、契约里没有的字段（多半是契约删了字段忘了删这里）
      const extra = [...keys].filter((k) => k !== 'id' && !fields.has(k));
      if (extra.length) {
        return '前端白名单里有、契约里没有（可能是契约删了字段）: ' + extra.join(', ');
      }
      return null;
    },
  },
  {
    // A1b 回归（2026-09-21）：「时序图」类输入会被 LLM 用契约没有的原语
    // （lifeline）拼出来，且箭头常绑到这些未知类型元素。修复前整批转换抛
    // TypeError: Cannot read properties of undefined (reading 'id') 让整批转换失败 → 画布全空。
    // 修复后：未知类型被隔离进 dropped，指向它的箭头脱落成自由箭头，矩形照常渲染。
    name: 'A1b 回归：未知元素类型 + 悬空箭头绑定不得让整批转换抛错',
    input: [
      { id: 'actor1', type: 'rectangle', x: 50, y: 50, width: 120, height: 60, label: { text: '用户' } },
      { id: 'actor2', type: 'rectangle', x: 400, y: 50, width: 120, height: 60, label: { text: '服务端' } },
      { id: 'life', type: 'lifeline', x: 110, y: 120, width: 2, height: 400 },
      { id: 'm1', type: 'arrow', start: { id: 'actor1' }, end: { id: 'life' } },
      { id: 'm2', type: 'arrow', start: { id: 'life' }, end: { id: 'actor2' } },
      { id: 'm3', type: 'arrow', start: { id: 'ghost_a' }, end: { id: 'ghost_b' } },
    ],
    check: (r) => (r.fatal ? '不应 fatal（A1b 回归）: ' + r.fatal : null)
      || (!r.dropped.includes('life') ? '未知类型 life 应被记录为 dropped' : null)
      || (r.elements.filter((e) => e.type === 'rectangle').length !== 2 ? '两个矩形应保留，实际 ' + r.elements.filter((e) => e.type === 'rectangle').length : null),
  },
  {
    // A1c 回归（2026-09-26）：32 组实测的高危缺陷 —— 箭头绑到 **embeddable / frame**
    // 这类「目标存在但类型不可绑」的元素时，转换器走进 bindLinearElement() 时
    // 拿到的端点元素是 undefined，下一行读 .id 就抛 TypeError，整批转换失败
    // → 画布全空。
    // A1b 只挡住了"目标不存在"，挡不住这种。修复后：绑定被摘掉、箭头降级为
    // 自由箭头照常画出来，embeddable / frame 本身也必须在。
    name: 'A1c 回归：箭头绑到 embeddable / frame 不得让整批转换失败（画布全空）',
    input: [
      { id: 'emb1', type: 'embeddable', x: 0, y: 0, width: 800, height: 500, url: 'https://example.com/features', link: 'https://example.com/features' },
      { id: 'grp', type: 'frame', x: 870, y: 10, width: 220, height: 140, name: '功能概览', children: [] },
      { id: 'n1', type: 'rectangle', x: 300, y: 640, width: 120, height: 60, label: { text: '节点' } },
      { id: 'a1', type: 'arrow', start: { id: 'n1' }, end: { id: 'emb1' }, label: { text: '对应区域' } },
      { id: 'a2', type: 'arrow', start: { id: 'n1' }, end: { id: 'grp' } },
    ],
    check: (r) => (r.fatal ? '不应 fatal（A1c 回归）: ' + r.fatal : null)
      || (r.elements.length === 0 ? '画布不得为空（这是"生成成功但一片空白"的直接原因）' : null)
      || (!r.elements.some((e) => e.id === 'emb1') ? 'embeddable 本身必须画得出来' : null)
      || (!r.elements.some((e) => e.id === 'grp') ? 'frame 本身必须画得出来' : null)
      || (r.elements.filter((e) => e.type === 'arrow').length !== 2
        ? '两条箭头都应降级后画出来，实际 '
          + r.elements.filter((e) => e.type === 'arrow').length : null)
      || (getCommonBounds(r.elements).some((v) => !Number.isFinite(v))
        ? '取景边界不应有 NaN' : null),
  },
  {
    // 上面那条的对偶面：修完之后，**合法**绑定必须原样保留 —— 尤其别把
    // 指向 text 的绑定误伤掉（text 是 Excalidraw 明确支持的绑定端点类型）。
    name: 'A1c 边界：矩形 / text / 形状之间的合法绑定必须原样保留',
    input: [
      { id: 't1', type: 'text', x: 0, y: 0, text: '标题', fontSize: 20 },
      { id: 'n1', type: 'ellipse', x: 0, y: 80, width: 120, height: 60, label: { text: '开始' } },
      { id: 'n2', type: 'diamond', x: 240, y: 80, width: 120, height: 90, label: { text: '判断' } },
      { id: 'a1', type: 'arrow', start: { id: 't1' }, end: { id: 'n1' } },
      { id: 'a2', type: 'arrow', start: { id: 'n1' }, end: { id: 'n2' } },
    ],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (r.dropped.length ? '不应丢弃: ' + r.dropped.join(',') : null)
      || (() => {
        for (const id of ['a1', 'a2']) {
          const a = r.elements.find((e) => e.id === id);
          if (!a) return id + ' 未转换出来';
          if (!a.startBinding || !a.endBinding) {
            return id + ' 的合法绑定被误摘掉了';
          }
        }
        return null;
      })(),
  },
  {
    // A1d 回归（2026-09-26 实测发现）：契约里 text.containerId 的合法目标是
    // **图形**（rectangle / ellipse / diamond），指向 frame 是非法用法 ——
    // 框的标题走 frame.name、框内归属走 frame.children。给 frame 填
    // containerId 的文字在 Excalidraw 里不报错、也不可见（元素在场景里、
    // 坐标正常，但一个像素都画不出来），观感又是"AI 生成了但内容缺一块"。
    name: 'A1d 回归：text.containerId 指向 frame（非法目标）应摘掉，文字必须可见',
    input: [
      { id: 'grp', type: 'frame', x: 100, y: 50, width: 200, height: 120, name: '用户中心' },
      { id: 'u1', type: 'text', x: 120, y: 65, text: '用户管理', containerId: 'grp' },
      { id: 'ghost', type: 'text', x: 120, y: 115, text: '认证授权', containerId: 'nope' },
      { id: 'n1', type: 'rectangle', x: 400, y: 50, width: 160, height: 80, label: { text: '下单' } },
      { id: 't2', type: 'text', x: 420, y: 70, text: '内文字', containerId: 'n1' },
    ],
    check: (r) => (r.fatal ? '不应 fatal: ' + r.fatal : null)
      || (() => {
        // 指向 frame / 不存在元素的两条：containerId 必须被摘掉（否则不可见）
        for (const id of ['u1', 'ghost']) {
          const t = r.elements.find((e) => e.id === id);
          if (!t) return id + ' 未被转换出来（文字会整块消失）';
          if (t.containerId) {
            return id + ' 的非法 containerId 未被摘掉（渲染时不可见）: ' + t.containerId;
          }
        }
        // 指向图形的合法内文字：必须原样保留
        const t2 = r.elements.find((e) => e.id === 't2');
        if (!t2) return 't2 未转换出来';
        if (t2.containerId !== 'n1') {
          return '合法 containerId 被误摘: ' + t2.containerId;
        }
        return null;
      })(),
  },
  {
    // 能力覆盖盲区回归（v2-43，2026-09-26 全量 32 组实测的结论之一）：
    // 契约 SCHEMA 里 fillStyle 有 **7** 个枚举值（hachure / cross-hatch /
    // solid / zigzag / dots / dashed / diagonal），但 32 组真跑一遍下来，
    // 模型一次都没用过非 diagonal 的纹理；同样从没被碰过的还有 angle、
    // roughness、startArrowhead。也就是说这些能力**从没被验证过能活过适配层**。
    //
    // 盲区的成因值得记一笔：frontend/src/types.ts 里 fillStyle 只声明了
    // 4 个字面量类型，比 Excalidraw 运行时（rough.js 实际宽得多）窄。
    // TS 类型窄 ≠ 运行时不接受 —— 但"没人跑过"就是真风险，所以这里
    // 用**契约的全量枚举**做一次覆盖，任何一项被适配层/转换器吞掉都算回归。
    // ⚠️ 本注释块在模板字符串里，禁止出现反引号。
    //
    // 用例故意**不转成 .ts**：scene-smoke.mjs 不经 tsc，才能带上契约里
    // 那几个类型声明外的取值（它们正是要验的东西）。
    name: '能力覆盖盲区：7 种 fillStyle + angle + roughness + startArrowhead 全量透传',
    input: [
      { id: 'f_hachure', type: 'rectangle', x: 0, y: 0, width: 120, height: 60, fillStyle: 'hachure', label: { text: 'A' } },
      { id: 'f_cross', type: 'rectangle', x: 160, y: 0, width: 120, height: 60, fillStyle: 'cross-hatch', label: { text: 'B' } },
      { id: 'f_solid', type: 'rectangle', x: 320, y: 0, width: 120, height: 60, fillStyle: 'solid', label: { text: 'C' } },
      { id: 'f_zigzag', type: 'rectangle', x: 480, y: 0, width: 120, height: 60, fillStyle: 'zigzag', label: { text: 'D' } },
      { id: 'f_dots', type: 'rectangle', x: 640, y: 0, width: 120, height: 60, fillStyle: 'dots', label: { text: 'E' } },
      { id: 'f_dashed', type: 'rectangle', x: 800, y: 0, width: 120, height: 60, fillStyle: 'dashed', label: { text: 'F' } },
      { id: 'f_diagonal', type: 'rectangle', x: 960, y: 0, width: 120, height: 60, fillStyle: 'diagonal', label: { text: 'G' } },
      // roughness = 0 是 Excalidraw 的" architects（工整）"档，与默认值 1 不同，
      // 同样从没被真跑过；angle 同理，且它是 getCommonBounds 旋转数学的输入。
      { id: 'r0', type: 'rectangle', x: 0, y: 120, width: 120, height: 60, roughness: 0, angle: 0.3, label: { text: 'H' } },
      { id: 'n_end', type: 'ellipse', x: 300, y: 120, width: 100, height: 60, label: { text: '终点' } },
      { id: 'a1', type: 'arrow', start: { id: 'f_hachure' }, end: { id: 'n_end' }, startArrowhead: 'dot', roughness: 0.5 },
    ],
    check: (r) => {
      if (r.fatal) return '不应 fatal: ' + r.fatal;
      if (r.dropped.length) return '不应丢弃: ' + r.dropped.join(',');
      const got = new Map(r.elements.map((e) => [e.id, e]));
      const fills = {
        f_hachure: 'hachure', f_cross: 'cross-hatch', f_solid: 'solid',
        f_zigzag: 'zigzag', f_dots: 'dots', f_dashed: 'dashed', f_diagonal: 'diagonal',
      };
      for (const [id, want] of Object.entries(fills)) {
        const el = got.get(id);
        if (!el) return id + ' 未转换出来';
        if (el.fillStyle !== want) {
          return id + ' 的 fillStyle 不是 ' + want + '，实际 ' + el.fillStyle;
        }
      }
      const r0 = got.get('r0');
      if (!r0) return 'r0（angle/roughness 用例）未转换出来';
      if (r0.angle !== 0.3) return 'angle 被改写成 ' + r0.angle;
      if (r0.roughness !== 0) return 'roughness 被改写成 ' + r0.roughness;
      const a1 = got.get('a1');
      if (!a1) return 'a1 未转换出来';
      if (a1.startArrowhead !== 'dot') return 'startArrowhead 被吞: ' + a1.startArrowhead;
      if (a1.roughness !== 0.5) return '箭头 roughness 被吞: ' + a1.roughness;
      if (getCommonBounds(r.elements).some((v) => !Number.isFinite(v))) {
        return '取景边界不应有 NaN（angle 参与旋转数学）';
      }
      return null;
    },
  },
  {
    // #1（line 闭合填充套装，2026-09-28）：闭合 line 应被当多边形渲染，
    // closed / backgroundColor / fillStyle 必须透传到产物，且整批转换不 fatal。
    // 这是"用线画填充形状 / 伪 3D"能力的回归锁：一旦适配层白名单漏掉 closed、
    // 或契约 additionalProperties 堵死这些字段，这里立刻红。
    name: 'line 闭合填充：closed / backgroundColor / fillStyle 必须透传且转换不 fatal',
    input: [
      { id: 'poly', type: 'line', x: 100, y: 100, points: [[0, 0], [120, 0], [60, 90]], closed: true, backgroundColor: '#ffd166', fillStyle: 'solid', strokeColor: '#1e1e1e' },
      rect({ id: 'keep', x: 0, y: 0 }),
    ],
    check: (r) => {
      if (r.fatal) return '不应 fatal: ' + r.fatal;
      if (r.dropped.includes('poly')) return '闭合 line 不应被丢弃';
      const el = r.elements.find((e) => e.id === 'poly');
      if (!el) return '闭合 line 未转换出来';
      // Excalidraw 0.18.1 靠 points 首末点重合（isPathALoop）判定填充，
      // 没有 closed 布尔字段 —— 适配层负责把首点补到末尾形成闭环。
      const pts = Array.isArray(el.points) ? el.points : [];
      if (pts.length < 4) return '闭合多边形至少 4 个点（含闭合点），实际 ' + pts.length;
      const first = pts[0];
      const last = pts[pts.length - 1];
      if (first[0] !== last[0] || first[1] !== last[1]) {
        return '首末点未重合（Excalidraw 靠 isPathALoop 判定填充，未闭合则不填）: ' + JSON.stringify(pts);
      }
      if (el.backgroundColor !== '#ffd166') return 'backgroundColor 被吞: ' + el.backgroundColor;
      if (el.fillStyle !== 'solid') return 'fillStyle 被吞: ' + el.fillStyle;
      if (getCommonBounds(r.elements).some((v) => !Number.isFinite(v))) {
        return '取景边界不应有 NaN';
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
