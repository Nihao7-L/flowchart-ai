# -*- coding: utf-8 -*-
"""画布 E2E 共用桩与工具。

把两个后端接口换成本地假实现，让「后端 IR → 画布 → 用户操作 → 上报」
这条链路在**不依赖后端、不调用 LLM** 的情况下跑起来：

    POST /api/chat          → 直接回一份固定场景（SSE 单帧 result）
    PUT  /api/model/canvas  → 记下请求体并回 200

真实前端链路（React / Excalidraw / 防抖 / 场景转换器）全部照跑，
所以它能抓住的正是「前端逻辑」这一类回归 —— 那类问题在后端单测里
永远看不见（2026-09-16 就连续翻车三次：删图形不删连线、拖线解绑不回写、
拖图形误报删除）。

前置：前端 dev server 已起（默认 http://127.0.0.1:5173），且必须是
      dev 构建 —— 探针 window.__chartflow 只在 import.meta.env.DEV 下挂载。
      用 tools/run-frontend.sh 起（它显式 --host 127.0.0.1 --strictPort）。

注意：沙箱里 http_proxy 指向本地代理，Chromium 默认会继承系统代理而连不上
      本机端口 —— 所以 launch 时显式 proxy={"server": "direct://"} 绕过。
"""
import json
import os
import sys

# Chromium 在 Windows 上会继承代理环境变量；沙箱里 http_proxy 指向本地代理，
# 导致连本机 5173 直接 ERR_PROXY_CONNECTION_FAILED。这里把代理变量从进程环境
# 摘掉（Chromium 子进程继承到的即无代理环境），再配合 --no-proxy-server 双保险。
for _k in ("http_proxy", "https_proxy", "all_proxy",
           "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"):
    os.environ.pop(_k, None)


DEV_URL = "http://127.0.0.1:5173/"

PAGE_HELPERS = """
window.__reqs = [];
window.__payload = __SCENE_JSON__;
const __origFetch = window.fetch.bind(window);
window.fetch = async (url, opts) => {
  const u = String(url);
  if (u.indexOf('/api/chat') >= 0) {
    return new Response(
      'data:' + JSON.stringify({type:'result', data: window.__payload}) + '\\n\\n',
      {status:200, headers:{'Content-Type':'text/event-stream'}});
  }
  if (u.indexOf('/api/model/canvas') >= 0) {
    try {
      window.__reqs.push({method:(opts && opts.method) || '', body: JSON.parse(opts.body)});
    } catch (e) {
      window.__reqs.push({error: String(e)});
    }
    return new Response('{"code":200,"message":"ok","data":null}',
      {status:200, headers:{'Content-Type':'application/json'}});
  }
  return __origFetch(url, opts);
};

window.__h = {
  toPage: function (sx, sy) {
    const st = window.__chartflow.getAppState();
    const c = document.querySelector('.excalidraw__canvas');
    if (!c) throw new Error('找不到 canvas 元素');
    const r = c.getBoundingClientRect();
    return { x: r.left + (sx + st.scrollX) * st.zoom.value,
             y: r.top  + (sy + st.scrollY) * st.zoom.value };
  },
  snapshot: function () {
    const els = window.__chartflow.getSceneElements();
    const byType = {};
    let boundBoth = 0, boundOne = 0, freeArrow = 0, nan = 0;
    const nanIds = [], ids = [];
    for (const e of els) {
      byType[e.type] = (byType[e.type] || 0) + 1;
      ids.push(e.id);
      if (e.type === 'arrow') {
        const sb = !!e.startBinding, eb = !!e.endBinding;
        if (sb && eb) boundBoth++; else if (sb || eb) boundOne++; else freeArrow++;
      }
      const bad = [e.x, e.y, e.width, e.height].some(
        (v) => typeof v !== 'number' || !Number.isFinite(v));
      if (bad) { nan++; nanIds.push(e.id); }
    }
    return { total: els.length, byType: byType, boundBoth: boundBoth,
             boundOne: boundOne, freeArrow: freeArrow,
             nan: nan, nanIds: nanIds, ids: ids };
  },
  geo: function (id) {
    const e = window.__chartflow.getSceneElements().find((x) => x.id === id);
    if (!e) return null;
    return { id: e.id, type: e.type, x: e.x, y: e.y,
             w: e.width, h: e.height, points: e.points || null,
             sb: e.startBinding ? e.startBinding.elementId : null,
             eb: e.endBinding ? e.endBinding.elementId : null };
  },
  center: function (id) {
    const e = window.__chartflow.getSceneElements().find((x) => x.id === id);
    if (!e) return null;
    return { x: e.x + e.width / 2, y: e.y + e.height / 2 };
  },
  arrowMid: function (id) {
    const e = window.__chartflow.getSceneElements().find((x) => x.id === id);
    if (!e || !e.points || !e.points.length) return null;
    const p = e.points[Math.floor(e.points.length / 2)];
    return { x: e.x + p[0], y: e.y + p[1] };
  },
  inkPixels: function () {
    const c = document.querySelectorAll('canvas')[0];
    if (!c) return -1;
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    let n = 0;
    for (let i = 0; i < d.length; i += 4) {
      if (d[i] < 245 || d[i + 1] < 245 || d[i + 2] < 245) n++;
    }
    return n;
  },
  ids: function () { return window.__chartflow.getSceneElements().map((e) => e.id); },
  appState: function () {
    const s = window.__chartflow.getAppState();
    return { zoom: s.zoom.value, sx: s.scrollX, sy: s.scrollY };
  },
  /* Every arrow with its real drawn endpoints (p0 / p1 in absolute coords),
     binding targets, points count, and the id/position of its attached label. */
  arrows: function () {
    const els = window.__chartflow.getSceneElements();
    const out = [];
    for (const e of els) {
      if (e.type !== 'arrow') continue;
      const pts = e.points || [];
      const last = pts.length ? pts[pts.length - 1] : [0, 0];
      let lbl = null;
      for (const t of els) {
        if (t.type === 'text' && t.containerId === e.id) {
          lbl = { id: t.id, x: t.x, y: t.y, w: t.width, h: t.height, text: t.text };
          break;
        }
      }
      out.push({
        id: e.id, x: e.x, y: e.y, w: e.width, h: e.height,
        sb: e.startBinding ? e.startBinding.elementId : null,
        eb: e.endBinding ? e.endBinding.elementId : null,
        np: pts.length,
        p0: [e.x, e.y],
        p1: [e.x + last[0], e.y + last[1]],
        label: lbl
      });
    }
    return out;
  },
  /* All text elements; containerId != null means it is a bound label. */
  texts: function () {
    return window.__chartflow.getSceneElements()
      .filter((e) => e.type === 'text')
      .map((e) => ({ id: e.id, x: e.x, y: e.y, w: e.width, h: e.height,
                     text: e.text, containerId: e.containerId || null }));
  },
  rect: function (id) {
    const e = window.__chartflow.getSceneElements().find((x) => x.id === id);
    if (!e) return null;
    return { x: e.x, y: e.y, w: e.width, h: e.height,
             cx: e.x + e.width / 2, cy: e.y + e.height / 2 };
  },
  reqs: function () { return window.__reqs.slice(); },
  fit: function () {
    const a = window.__chartflow;
    a.scrollToContent(a.getSceneElements(), {fitToContent: true, animate: false});
  }
};
"""


def scene_of(nodes, arrows, extras=()):
    """拼一份后端 IR 形状的场景。

    nodes  : [(id, type, x, y, w, h, label)]
    arrows : [(id, startId, endId, label|None)]
    extras : 额外的原始元素 dict
    """
    els = []
    for (i, t, x, y, w, h, label) in nodes:
        els.append({"id": i, "type": t, "x": x, "y": y,
                    "width": w, "height": h, "label": {"text": label}})
    for (i, s, e, lb) in arrows:
        a = {"id": i, "type": "arrow", "start": {"id": s}, "end": {"id": e}}
        if lb:
            a["label"] = {"text": lb}
        els.append(a)
    return {"elements": els + list(extras)}


def open_page(pw, scene, width=1600, height=900):
    """起浏览器、装桩、等到探针就绪。"""
    # direct:// + --no-proxy-server = 绕开沙箱 http_proxy（否则连不上本机 5173）
    browser = pw.chromium.launch(
        headless=True,
        proxy={"server": "direct://"},
        args=["--no-proxy-server", "--proxy-bypass-list=*"])
    page = browser.new_page(viewport={"width": width, "height": height})
    page.add_init_script(PAGE_HELPERS.replace("__SCENE_JSON__", json.dumps(json.dumps(scene))))
    page.goto(DEV_URL, wait_until="load")
    page.wait_for_function("() => !!window.__chartflow", timeout=40000)
    return browser, page


def generate(page, text="e2e"):
    """触发一轮生成（走假 /api/chat）并等画布落定。"""
    page.fill(".chat-input", text)
    page.click(".chat-send-btn")
    page.wait_for_function(
        "() => window.__chartflow.getSceneElements().length > 0", timeout=20000)
    page.wait_for_timeout(1000)
    page.evaluate("() => window.__h.fit()")
    page.wait_for_timeout(500)


def drag(page, sx, sy, dx, dy, settle=800):
    """真实鼠标拖动（比合成事件稳）。settle 要盖过 400ms 的上报防抖。"""
    pt = page.evaluate("(a) => window.__h.toPage(a[0], a[1])", [sx, sy])
    page.mouse.move(pt["x"], pt["y"])
    page.wait_for_timeout(60)
    page.mouse.down()
    page.wait_for_timeout(60)
    page.mouse.move(pt["x"] + dx, pt["y"] + dy, steps=18)
    page.wait_for_timeout(60)
    page.mouse.up()
    page.wait_for_timeout(settle)


class Report:
    """极简结果收集：noted 打印过程，failed 决定退出码。"""

    def __init__(self, title):
        self.title = title
        self.fails = []
        print("=" * 72)
        print(title)
        print("-" * 72)

    def note(self, text):
        print("  " + text)

    def fail(self, text):
        self.fails.append(text)
        print("  FAIL: " + text)

    def finish(self):
        print("-" * 72)
        if self.fails:
            print("共 %d 项失败" % len(self.fails))
            for f in self.fails:
                print("  - " + f)
            sys.exit(1)
        print("PASS")
        sys.exit(0)


def last_sync(page):
    reqs = page.evaluate("() => window.__h.reqs()")
    return (reqs[-1].get("body") if reqs else None), len(reqs)
