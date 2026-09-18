# -*- coding: utf-8 -*-
"""真实 LLM 端到端验收：v2-36 布局 + 编辑模式几何保真。

与同目录其它 E2E 的分工：
  - e2e_drag_* 等 6 个：**打桩** `/api/chat`，只验前端逻辑，不碰大模型；
  - 本脚本：**不打桩**，走真实后端 + 真实大模型，验"这张图能不能看"。

前置：8080 后端与 5173 dev server 都在跑（`tools/run-backend.sh` / `tools/run-frontend.sh`）。
跑法：见 `.playwright/run-real-llm.sh`。

判定口径（全部量化，不靠"看着还行"）：
  1. NaN 元素数 = 0
  2. 图形两两包围盒重叠 = 0
  3. 两端绑定的箭头：净间距 >= 其标签宽度 + 2*12（布局层 EDGE_LABEL_PADDING 的两倍）
  4. 图形整体纵横比落在 [0.4, 2.5]
  5. 编辑模式：第一轮已存在的元素坐标一个都没动
并落两张截图供肉眼复核。

注意：大模型单轮 50~260 秒，是常态，不是卡死（`ChatPanel` 有"已等待 N 秒"提示）。
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import DEV_URL, Report  # noqa: E402  import 时已摘掉代理环境变量

HERE = os.path.dirname(os.path.abspath(__file__))
SHOT_DIR = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".playwright", "shots"))

PROMPT_1 = ("画一个电商系统的微服务架构图：包含 API 网关、用户服务、订单服务、"
            "库存服务、支付服务、数据库，服务之间用带名字的箭头标出调用关系")
PROMPT_2 = ("不要改动其它任何节点，只新增一个消息队列节点，"
            "并从订单服务拉一条箭头连到它上面")

ROUND_TIMEOUT = 330.0  # 单轮上限，得盖过后端 TOTAL_BUDGET_MS(420s) 之外的前端等待

# 探针：只读窗口，不改任何前端代码（window.__chartflow 是 dev 构建自带的）
HELPERS = r"""
window.__r = {
  scene: function () { return window.__chartflow.getSceneElements(); },
  fit: function () {
    var a = window.__chartflow;
    a.scrollToContent(a.getSceneElements(), { fitToContent: true, animate: false });
  },
  metrics: function () {
    var els = window.__chartflow.getSceneElements();
    var SHAPES = { rectangle: 1, ellipse: 1, diamond: 1 };
    var nan = els.filter(function (e) {
      return [e.x, e.y, e.width, e.height].some(function (v) {
        return typeof v !== 'number' || !Number.isFinite(v);
      });
    }).map(function (e) { return e.id; });
    var shapes = els.filter(function (e) { return SHAPES[e.type]; });
    var ov = [];
    for (var i = 0; i < shapes.length; i++) {
      for (var j = i + 1; j < shapes.length; j++) {
        var a = shapes[i], b = shapes[j];
        var ox = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
        var oy = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
        if (ox > 1 && oy > 1) ov.push([a.id, b.id, Math.round(ox), Math.round(oy)]);
      }
    }
    var byId = {}; els.forEach(function (e) { byId[e.id] = e; });
    var labels = {}; els.forEach(function (e) {
      if (e.type === 'text' && e.containerId) labels[e.containerId] = e;
    });
    var arrows = els.filter(function (e) { return e.type === 'arrow'; }).map(function (e) {
      var pts = e.points || [];
      var last = pts.length ? pts[pts.length - 1] : [0, 0];
      var sb = e.startBinding ? e.startBinding.elementId : null;
      var eb = e.endBinding ? e.endBinding.elementId : null;
      var gap = null;
      if (sb && eb && byId[sb] && byId[eb]) {
        var A = byId[sb], B = byId[eb];
        gap = Math.max(Math.max(B.x - (A.x + A.width), A.x - (B.x + B.width)),
                       Math.max(B.y - (A.y + A.height), A.y - (B.y + B.height)));
      }
      var lb = labels[e.id];
      return {
        id: e.id,
        bound: (sb && eb) ? 2 : ((sb || eb) ? 1 : 0),
        x: Math.round(e.x), y: Math.round(e.y),
        w: Math.round(e.width), h: Math.round(e.height),
        len: Math.round(Math.hypot(last[0], last[1])),
        gap: gap === null ? null : Math.round(gap),
        label: lb ? { text: lb.text, w: Math.round(lb.width) } : null,
        from: sb && byId[sb] && byId[sb].label ? byId[sb].label.text : null,
        to: eb && byId[eb] && byId[eb].label ? byId[eb].label.text : null
      };
    });
    var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    shapes.forEach(function (e) {
      minX = Math.min(minX, e.x); minY = Math.min(minY, e.y);
      maxX = Math.max(maxX, e.x + e.width); maxY = Math.max(maxY, e.y + e.height);
    });
    var boxes = shapes.map(function (e) {
      return { id: e.id, label: e.label ? e.label.text : null,
               x: Math.round(e.x), y: Math.round(e.y),
               w: Math.round(e.width), h: Math.round(e.height) };
    });
    var lbls = els.filter(function (e) { return e.type === 'text' && e.containerId; });
    var labelOverlaps = [];
    for (var k = 0; k < lbls.length; k++) {
      for (var l = k + 1; l < lbls.length; l++) {
        var A2 = lbls[k], B2 = lbls[l];
        var ox2 = Math.min(A2.x + A2.width, B2.x + B2.width) - Math.max(A2.x, B2.x);
        var oy2 = Math.min(A2.y + A2.height, B2.y + B2.height) - Math.max(A2.y, B2.y);
        if (ox2 > 1 && oy2 > 1) {
          labelOverlaps.push([A2.text, B2.text, Math.round(ox2), Math.round(oy2)]);
        }
      }
    }
    var chan = 0;
    for (var p = 0; p < arrows.length; p++) {
      for (var q = p + 1; q < arrows.length; q++) {
        var a3 = arrows[p], b3 = arrows[q];
        if (a3.bound !== 2 || b3.bound !== 2) continue;
        var vertA = a3.h >= a3.w, vertB = b3.h >= b3.w;
        if (vertA !== vertB) continue;
        var spanA = vertA ? [a3.x, a3.x + a3.w] : [a3.y, a3.y + a3.h];
        var spanB = vertB ? [b3.x, b3.x + b3.w] : [b3.y, b3.y + b3.h];
        var o3 = Math.min(spanA[1], spanB[1]) - Math.max(spanA[0], spanB[0]);
        var minW = Math.min(spanA[1] - spanA[0], spanB[1] - spanB[0]);
        if (minW > 0 && o3 > 0.5 * minW) chan++;
      }
    }
    return {
      total: els.length, nan: nan, overlaps: ov, arrows: arrows, boxes: boxes,
      labelOverlaps: labelOverlaps, edgeChannelPairs: chan,
      aspect: shapes.length ? Number(((maxX - minX) / (maxY - minY)).toFixed(2)) : null,
      bounds: shapes.length ? [Math.round(minX), Math.round(minY),
                               Math.round(maxX), Math.round(maxY)] : null
    };
  },
  ink: function () {
    var c = document.querySelectorAll('canvas')[0];
    if (!c) return -1;
    var d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    var n = 0;
    for (var i = 0; i < d.length; i += 4) {
      if (d[i] < 245 || d[i + 1] < 245 || d[i + 2] < 245) n++;
    }
    return n;
  }
};
"""


def open_page(pw):
    """起浏览器打开 dev 页面。**不装 /api/chat 桩** —— 这正是本脚本的意义。"""
    browser = pw.chromium.launch(
        headless=True,
        proxy={"server": "direct://"},
        args=["--no-proxy-server", "--proxy-bypass-list=*"])
    page = browser.new_page(viewport={"width": 1600, "height": 900})
    page.add_init_script(HELPERS)
    page.goto(DEV_URL, wait_until="load")
    page.wait_for_function("() => !!window.__chartflow", timeout=60000)
    return browser, page


def chat_log(page):
    return page.evaluate("""() => Array.from(document.querySelectorAll('.chat-log .msg'))
        .map(e => ({cls: e.className, text: e.textContent || ''}))""")


def ask(page, text, rep):
    """发一条消息，等到本轮落定（出结果或报错）。返回耗时秒数。"""
    done_before = len([m for m in chat_log(page) if '已生成' in m['text']])
    page.fill(".chat-input", text)
    page.click(".chat-send-btn")
    t0 = time.time()
    while True:
        waited = time.time() - t0
        if waited > ROUND_TIMEOUT:
            rep.fail("等待超时（%.0f 秒）：既没出结果也没报错" % waited)
            return waited
        msgs = chat_log(page)
        errs = [m for m in msgs if m['cls'].endswith('error')]
        done = len([m for m in msgs if '已生成' in m['text']])
        if done > done_before:
            return time.time() - t0
        if errs:
            rep.fail("本轮报错：" + errs[-1]['text'][:120])
            return time.time() - t0
        page.wait_for_timeout(2000)


def shot(page, name):
    os.makedirs(SHOT_DIR, exist_ok=True)
    path = os.path.join(SHOT_DIR, name)
    page.evaluate("() => window.__r.fit()")
    page.wait_for_timeout(700)
    page.screenshot(path=path)
    return path


def main():
    rep = Report("真实 LLM 端到端验收（v2-36 布局 + 编辑模式几何保真）")
    from playwright.sync_api import sync_playwright

    with sync_playwright() as pw:
        browser, page = open_page(pw)
        try:
            rep.note("dev 页面已就绪：%s" % DEV_URL)

            # ---------- 第一轮：纯新建 ----------
            rep.note("第一轮提示词：%s" % PROMPT_1)
            dt = ask(page, PROMPT_1, rep)
            rep.note("第一轮耗时 %.1f 秒" % dt)
            page.wait_for_timeout(1500)
            m1 = page.evaluate("() => window.__r.metrics()")
            ink = page.evaluate("() => window.__r.ink()")
            p1 = shot(page, "real-llm-round1.png")

            rep.note("元素总数 %d / 图形 %d / 箭头 %d（两端绑定 %d）"
                     % (m1['total'], len(m1['boxes']), len(m1['arrows']),
                        len([a for a in m1['arrows'] if a['bound'] == 2])))
            rep.note("画布非白像素 = %d" % ink)
            rep.note("整体 bounds = %s  纵横比 = %s" % (m1['bounds'], m1['aspect']))
            rep.note("截图：%s" % p1)
            for b in m1['boxes']:
                rep.note("  图形 %-9s (%4d,%4d) %3dx%3d  %s"
                         % (b['id'][:9], b['x'], b['y'], b['w'], b['h'], b['label']))
            for a in m1['arrows']:
                rep.note("  箭头 %-9s 绑定=%d 长度=%3d 间距=%s 标签=%s  %s -> %s"
                         % (a['id'][:9], a['bound'], a['len'], a['gap'],
                            a['label'] and a['label']['text'], a['from'], a['to']))

            if not m1['boxes']:
                rep.fail("没画出任何图形")
            if m1['nan']:
                rep.fail("存在 NaN 几何：%s" % m1['nan'])
            if ink is not None and ink < 2000:
                rep.fail("画布几乎空白（非白像素 %d），元素可能落在视口外" % ink)
            if m1['overlaps']:
                rep.fail("图形重叠 %d 对：%s" % (len(m1['overlaps']), m1['overlaps'][:5]))
            if m1['aspect'] is not None and not (0.4 <= m1['aspect'] <= 2.5):
                rep.fail("纵横比失控：%s（目标区间 0.5~2.0）" % m1['aspect'])
            for a in m1['arrows']:
                if a['bound'] == 2 and a['label'] and a['gap'] is not None:
                    need = a['label']['w'] + 24
                    if a['gap'] < need:
                        rep.fail("边标签盖线：箭头 %s 间距 %d < 标签宽 %d + 24"
                                 % (a['id'], a['gap'], a['label']['w']))

            rep.note("标签互相压叠 %d 对：%s"
                     % (len(m1['labelOverlaps']), m1['labelOverlaps'][:4]))
            rep.note("WARN 平行边共用同一通道 %d 对（已知缺口：自研布局不做虚拟节点"
                     "通道分散，属 v2-36 决策 1 记下的代价，见 roadmap 备份计划）"
                     % m1['edgeChannelPairs'])

            before = {b['id']: (b['x'], b['y'], b['w'], b['h']) for b in m1['boxes']}

            # ---------- 第二轮：编辑模式（只给新增元素定位） ----------
            rep.note("第二轮提示词：%s" % PROMPT_2)
            dt2 = ask(page, PROMPT_2, rep)
            rep.note("第二轮耗时 %.1f 秒" % dt2)
            page.wait_for_timeout(1500)
            m2 = page.evaluate("() => window.__r.metrics()")
            ink2 = page.evaluate("() => window.__r.ink()")
            p2 = shot(page, "real-llm-round2.png")

            after = {b['id']: (b['x'], b['y'], b['w'], b['h']) for b in m2['boxes']}
            kept = [i for i in before if i in after]
            moved = [(i, before[i], after[i]) for i in kept if before[i] != after[i]]
            added = [i for i in after if i not in before]

            rep.note("第二轮：元素总数 %d / 图形 %d / 新增图形 %d / 消失图形 %d"
                     % (m2['total'], len(m2['boxes']), len(added),
                        len([i for i in before if i not in after])))
            rep.note("第二轮非白像素 = %d  纵横比 = %s" % (ink2, m2['aspect']))
            rep.note("截图：%s" % p2)
            rep.note("新增图形：%s" % [(i, after[i]) for i in added])
            if moved:
                for (i, b, a) in moved:
                    rep.note("  被移动的既有图形 %s: %s -> %s" % (i, b, a))

            if m2['nan']:
                rep.fail("第二轮存在 NaN 几何：%s" % m2['nan'])
            if m2['overlaps']:
                rep.fail("第二轮图形重叠 %d 对：%s" % (len(m2['overlaps']), m2['overlaps'][:5]))
            if not added:
                rep.note("提示：本轮没有新增图形（大模型可能改成了别的形式，非布局缺陷）")
            # 核心断言：编辑模式下既有元素坐标必须一个不动（与 SceneDrift 的承诺一致）
            if len(kept) >= 3 and moved:
                rep.fail("编辑模式动了既有元素坐标：%d 个（应为 0）" % len(moved))
            elif len(kept) < 3:
                rep.note("可比元素只有 %d 个，冻结断言跳过（沿用 SceneDrift 的保守口径）"
                         % len(kept))
        finally:
            browser.close()
    rep.finish()


if __name__ == "__main__":
    main()
