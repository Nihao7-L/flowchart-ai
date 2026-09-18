# -*- coding: utf-8 -*-
"""复杂提示词测试集电池：随机抽题 -> 真实大模型端到端 -> 量化落盘。

题目来源：`workbuddyFlow/docs/复杂提示词测试集.md`（9 条，每种图表类型一个代表，
全部含多层嵌套 / 结合元素，规模远超提示词里写的"元素总数 <= 40"）。

与同目录其它脚本的分工：
  - e2e_drag_* 等 6 个：打桩 /api/chat，只验前端逻辑；
  - e2e_real_llm_layout.py：不打桩，验"一张小图能不能看"（硬断言 PASS/FAIL）；
  - **本脚本**：不打桩，**压力/探索**性质 —— 把远超预算的复杂需求丢进去，
    量化记录"会发生什么"。因此默认不判 PASS/FAIL，只记事实与硬缺陷。

硬缺陷（会以退出码 1 收尾）只有三类，都能百分百归责于代码而非需求规模：
  1. 出现 NaN / Infinity 几何
  2. 图形两两包围盒重叠
  3. 压根没出图（元素数 0）

前置：8080 后端 + 5173 dev server 都在跑，且 dev 构建（探针 window.__chartflow）。
跑法（在 Git Bash 里，PYTHONPATH / 浏览器路径由 .playwright/run-battery.sh 备好）：
  bash .playwright/run-battery.sh --list          # 只列题
  bash .playwright/run-battery.sh --pick 1        # 随机抽 1 条
  bash .playwright/run-battery.sh --seed 42 --pick 3
  bash .playwright/run-battery.sh --index 1       # 指定第 1 条（1 基）
  bash .playwright/run-battery.sh --all
"""
import argparse
import json
import os
import random
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import DEV_URL, Report  # noqa: E402  导入时已摘掉代理环境变量

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
BATTERY_MD = os.path.join(PROJ, "workbuddyFlow", "docs", "复杂提示词测试集.md")
SHOT_DIR = os.path.join(PROJ, ".playwright", "shots")
OUT_DIR = os.path.join(HERE, "battery-out")

# 单条上限（秒）。后端总预算 420s，前端在其之上还有建连与渲染开销，
# 留到 480s 才不会把"后端正常收尾"误判成"前端卡死"。
CASE_TIMEOUT = 480.0

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
    var counts = {};
    els.forEach(function (e) { counts[e.type] = (counts[e.type] || 0) + 1; });
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
    var lbls = els.filter(function (e) { return e.type === 'text'; });
    var labelHit = [];
    for (var k = 0; k < lbls.length; k++) {
      for (var l = k + 1; l < lbls.length; l++) {
        var A2 = lbls[k], B2 = lbls[l];
        var ox2 = Math.min(A2.x + A2.width, B2.x + B2.width) - Math.max(A2.x, B2.x);
        var oy2 = Math.min(A2.y + A2.height, B2.y + B2.height) - Math.max(A2.y, B2.y);
        if (ox2 > 1 && oy2 > 1) {
          labelHit.push([A2.text, B2.text, Math.round(ox2), Math.round(oy2)]);
        }
      }
    }
    var labelOnShape = 0;
    lbls.forEach(function (L) {
      for (var m = 0; m < shapes.length; m++) {
        var S = shapes[m];
        if (L.containerId && L.containerId === S.id) continue;
        var ox3 = Math.min(L.x + L.width, S.x + S.width) - Math.max(L.x, S.x);
        var oy3 = Math.min(L.y + L.height, S.y + S.height) - Math.max(L.y, S.y);
        if (ox3 > 1 && oy3 > 1) { labelOnShape++; return; }
      }
    });
    var chan = 0;    for (var p = 0; p < arrows.length; p++) {
      for (var q = p + 1; q < arrows.length; q++) {
        var a3 = arrows[p], b3 = arrows[q];
        if (a3.bound !== 2 || b3.bound !== 2) continue;
        var spanA = [a3.gap === null ? 0 : 0, 0];
        if (a3.gap === null) continue;
        var ae = byId[a3.id], be = byId[b3.id];
        if (!ae || !be) continue;
        var vertA = ae.height >= ae.width, vertB = be.height >= be.width;
        if (vertA !== vertB) continue;
        var sA = vertA ? [ae.x, ae.x + ae.width] : [ae.y, ae.y + ae.height];
        var sB = vertB ? [be.x, be.x + be.width] : [be.y, be.y + be.height];
        var o3 = Math.min(sA[1], sB[1]) - Math.max(sA[0], sB[0]);
        var minW = Math.min(sA[1] - sA[0], sB[1] - sB[0]);
        if (minW > 0 && o3 > 0.5 * minW) chan++;
      }
    }
    // frame 审计：布局引擎只认 rectangle/ellipse/diamond（ElkLayoutEngine
    // 的 SHAPE_TYPES），frame 完全不经手 -> 它的坐标还是 LLM 给的种子值，
    // 而框里的节点已被重排。三个指标盯的就是这个"框与内容脱节"。
    var frames = els.filter(function (e) { return e.type === 'frame'; });
    var cIn = function (a, b) {
      var cx = a.x + a.width / 2, cy = a.y + a.height / 2;
      return b.x <= cx && cx <= b.x + b.width
          && b.y <= cy && cy <= b.y + b.height;
    };
    var onGrid = function (v) {
      return Math.abs(v / 20 - Math.round(v / 20)) < 1e-9;
    };
    var frameOv = [];
    var framesOnGrid = 0;
    frames.forEach(function (f) { if (onGrid(f.x) && onGrid(f.y)) framesOnGrid++; });
    for (var fi = 0; fi < frames.length; fi++) {
      for (var fj = fi + 1; fj < frames.length; fj++) {
        var F1 = frames[fi], F2 = frames[fj];
        var fox = Math.min(F1.x + F1.width, F2.x + F2.width) - Math.max(F1.x, F2.x);
        var foy = Math.min(F1.y + F1.height, F2.y + F2.height) - Math.max(F1.y, F2.y);
        if (fox > 1 && foy > 1) {
          frameOv.push([F1.name || F1.id, F2.name || F2.id, Math.round(fox), Math.round(foy)]);
        }
      }
    }
    var shapesOutside = 0, shapesInMulti = 0;
    shapes.forEach(function (s) {
      var n = 0;
      frames.forEach(function (f) { if (cIn(s, f)) n++; });
      if (n === 0) shapesOutside++;
      if (n > 1) shapesInMulti++;
    });
    return {
      total: els.length, counts: counts, nan: nan, overlaps: ov, arrows: arrows,
      boxes: boxes, labelOverlaps: labelHit, labelOnShape: labelOnShape,
      edgeChannelPairs: chan,
      frames: frames.length, framesOnGrid: framesOnGrid,
      frameOverlaps: frameOv, shapesOutsideFrames: shapesOutside,
      shapesInMultiFrames: shapesInMulti,
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


def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def parse_battery(path):
    """从测试集 markdown 里抽题：## 分组标题 + ### 提示词 N：标题 + 紧随的代码块。"""
    lines = read_text(path).split("\n")
    cases = []
    section = ""
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("## ") and not line.startswith("### "):
            section = line[3:].strip()
        elif line.startswith("### ") and "提示词" in line:
            title = line[4:].strip()
            j = i + 1
            while j < len(lines) and not lines[j].lstrip().startswith("```"):
                j += 1
            body = []
            if j < len(lines):
                j += 1
                while j < len(lines) and not lines[j].lstrip().startswith("```"):
                    body.append(lines[j])
                    j += 1
            prompt = "\n".join(body).strip()
            if prompt:
                cases.append({"section": section, "title": title, "prompt": prompt})
            i = j
        i += 1
    return cases


def open_page(pw):
    """起浏览器打开 dev 页面。不装 /api/chat 桩 —— 走真后端 + 真大模型。"""
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


def ask(page, text):
    """发一条消息并等本轮落定。返回 (状态, 耗时秒, 消息列表)。

    状态取值：ok（出结果）/ error（报错）/ timeout（既无结果也无报错）
    """
    done_before = len([m for m in chat_log(page) if "已生成" in m["text"]])
    page.fill(".chat-input", text)
    page.click(".chat-send-btn")
    t0 = time.time()
    while True:
        waited = time.time() - t0
        if waited > CASE_TIMEOUT:
            return "timeout", waited, chat_log(page)
        msgs = chat_log(page)
        if len([m for m in msgs if "已生成" in m["text"]]) > done_before:
            return "ok", time.time() - t0, msgs
        errs = [m for m in msgs if m["cls"].endswith("error")]
        if errs:
            return "error", time.time() - t0, msgs
        page.wait_for_timeout(2000)


def run_case(pw, idx, case, rep):
    """跑一条题：新开页面（= 新 sessionId，确保走"新建"而非"编辑"分支）。"""
    rep.note("")
    rep.note("=" * 68)
    rep.note("[%d] %s / %s" % (idx, case["section"], case["title"]))
    rep.note("提示词 %d 字 | %d 行" % (len(case["prompt"]), case["prompt"].count("\n") + 1))

    rec = {"index": idx, "section": case["section"], "title": case["title"],
           "promptChars": len(case["prompt"])}

    browser, page = open_page(pw)
    try:
        status, dt, msgs = ask(page, case["prompt"])
        rec["status"] = status
        rec["seconds"] = round(dt, 1)
        rec["chat"] = [{"cls": m["cls"], "text": m["text"][:400]} for m in msgs if m["cls"] != "msg user"]
        users = [m for m in msgs if m["cls"] == "msg user"]
        rec["sentChars"] = len(users[-1]["text"]) if users else None
        rec["sentLines"] = (users[-1]["text"].count(chr(10)) + 1) if users else None
        rep.note("结果：%s，耗时 %.1f 秒" % (status.upper(), dt))
        rep.note("提示词保真：送进输入框 %d 字 / %d 行 -> 聊天记录里 %s 字 / %s 行"
                 % (len(case["prompt"]), case["prompt"].count(chr(10)) + 1,
                    rec["sentChars"], rec["sentLines"]))

        wait = 1500 if status == "ok" else 0
        if wait:
            page.wait_for_timeout(wait)
        m = page.evaluate("() => window.__r.metrics()")
        ink = page.evaluate("() => window.__r.ink()")
        shot = os.path.join(SHOT_DIR, "battery-case%02d.png" % idx)
        try:
            page.evaluate("() => window.__r.fit()")
            page.wait_for_timeout(700)
            page.screenshot(path=shot)
            rec["shot"] = shot
        except Exception as e:  # 截图失败不该吞掉主结果
            rep.note("截图失败：%s" % e)
        try:
            with open(os.path.join(OUT_DIR, "case%02d-scene.json" % idx), "w",
                      encoding="utf-8") as f:
                json.dump({"elements": page.evaluate("() => window.__r.scene()")},
                          f, ensure_ascii=False)
        except Exception as e:
            rep.note("场景快照失败：%s" % e)

        rec.update({
            "total": m["total"], "counts": m["counts"],
            "shapes": len(m["boxes"]), "nan": m["nan"],
            "shapeOverlaps": m["overlaps"][:10], "shapeOverlapCount": len(m["overlaps"]),
            "labelOverlapCount": len(m["labelOverlaps"]),
            "labelOverlaps": m["labelOverlaps"][:8],
            "labelOnShape": m["labelOnShape"],
            "boundArrows": len([a for a in m["arrows"] if a["bound"] == 2]),
            "unboundArrows": len([a for a in m["arrows"] if a["bound"] == 0]),
            "edgeChannelPairs": m["edgeChannelPairs"],
            "frames": m["frames"], "framesOnGrid": m["framesOnGrid"],
            "frameOverlapCount": len(m["frameOverlaps"]),
            "frameOverlaps": m["frameOverlaps"][:6],
            "shapesOutsideFrames": m["shapesOutsideFrames"],
            "shapesInMultiFrames": m["shapesInMultiFrames"],
            "aspect": m["aspect"], "bounds": m["bounds"], "ink": ink,
        })
        rep.note("元素 %d %s" % (m["total"], m["counts"]))
        rep.note("图形 %d | 箭头 %d（两端绑定 %d / 未绑定 %d）| 包围盒 %s | 纵横比 %s"
                 % (len(m["boxes"]), len(m["arrows"]), rec["boundArrows"],
                    rec["unboundArrows"], m["bounds"], m["aspect"]))
        rep.note("非白像素 %d" % ink)
        rep.note("图形重叠 %d 对 | 文字互叠 %d 对 | 文字压节点 %d 处 | 平行边共用通道 %d 对"
                 % (len(m["overlaps"]), len(m["labelOverlaps"]),
                    m["labelOnShape"], m["edgeChannelPairs"]))
        if m["overlaps"]:
            rep.note("  重叠样例：%s" % m["overlaps"][:3])
        if m["labelOverlaps"]:
            rep.note("  互叠样例：%s" % m["labelOverlaps"][:3])
        if m["nan"]:
            rep.note("  NaN 元素：%s" % m["nan"][:5])
        if m["frames"]:
            rep.note("frame %d 个 | 落在 20px 网格上 %d 个（节点布局产物会落网 | 种子值不会）"
                     " | frame 两两重叠 %d 对 | 不在任何 frame 内的图形 %d | 落在 2 个以上 frame 内的图形 %d"
                     % (m["frames"], m["framesOnGrid"], len(m["frameOverlaps"]),
                        m["shapesOutsideFrames"], m["shapesInMultiFrames"]))
            if m["frameOverlaps"]:
                rep.note("  frame 重叠样例：%s" % m["frameOverlaps"][:3])

        # ---- 硬缺陷：能百分百归责于代码，与需求规模无关 ----
        if m["nan"]:
            rep.fail("[%d] 存在 NaN 几何：%s" % (idx, m["nan"][:5]))
        if m["overlaps"]:
            rep.fail("[%d] 图形重叠 %d 对：%s"
                     % (idx, len(m["overlaps"]), m["overlaps"][:3]))
        if m["total"] == 0:
            rep.fail("[%d] 一个元素都没画出来（%s）" % (idx, status))
        # frame 与节点坐标系脱节：布局重排了框里的节点，却没搬框本身
        # （ElkLayoutEngine 的 SHAPE_TYPES 不含 frame）。证据 = 节点全在
        # 20px 网格上、frame 坐标仍是 LLM 的种子值。
        if m["boxes"] and m["frames"] and m["framesOnGrid"] < m["frames"]:
            rep.fail("[%d] frame 未随节点一起布局：%d 个 frame 里只有 %d 个"
                     "落在节点布局的 20px 网格上 -> 框与内容脱节"
                     "（%d 个图形跑出所有 frame，frame 互相重叠 %d 对）"
                     % (idx, m["frames"], m["framesOnGrid"],
                        m["shapesOutsideFrames"], len(m["frameOverlaps"])))
        # 图形全靠 text 表达（时序图这类契约没有原语）-> 布局引擎零输入
        if m["total"] and not m["boxes"]:
            rep.fail("[%d] 图形数为 0（元素全是 %s）-> 布局引擎整体跳过，"
                     "种子坐标原样上屏，文字互叠 %d 对"
                     % (idx, m["counts"], len(m["labelOverlaps"])))
    finally:
        browser.close()
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true", help="只列题不跑")
    ap.add_argument("--pick", type=int, default=0, help="随机抽 N 条")
    ap.add_argument("--index", type=int, default=0, help="只跑第 N 条（1 基）")
    ap.add_argument("--all", action="store_true", help="跑全部")
    ap.add_argument("--seed", type=int, default=None, help="随机种子（默认取当前时间，可复现用）")
    args = ap.parse_args()

    cases = parse_battery(BATTERY_MD)
    if not cases:
        print("没从 %s 里解析出任何题" % BATTERY_MD)
        sys.exit(2)

    if args.list:
        for i, c in enumerate(cases, 1):
            print("%d. [%s] %s  (%d 字)" % (i, c["section"], c["title"], len(c["prompt"])))
        print("共 %d 条" % len(cases))
        return

    seed = args.seed if args.seed is not None else int(time.time())
    if args.all:
        chosen = list(range(len(cases)))
    elif args.index:
        chosen = [args.index - 1]
    elif args.pick:
        rng = random.Random(seed)
        chosen = sorted(rng.sample(range(len(cases)), min(args.pick, len(cases))))
    else:
        rng = random.Random(seed)
        chosen = [rng.randrange(len(cases))]

    rep = Report("复杂提示词测试集 · 真实大模型电池（随机种子 %s）" % seed)
    rep.note("题源：%s" % BATTERY_MD)
    rep.note("题量 %d 条 | 本次抽中 %s" % (len(cases), [i + 1 for i in chosen]))
    for i in chosen:
        rep.note("  #%d %s / %s" % (i + 1, cases[i]["section"], cases[i]["title"]))

    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(SHOT_DIR, exist_ok=True)
    from playwright.sync_api import sync_playwright

    records = []
    with sync_playwright() as pw:
        for i in chosen:
            records.append(run_case(pw, i + 1, cases[i], rep))

    stamp = time.strftime("%Y%m%d-%H%M%S")
    out = os.path.join(OUT_DIR, "%s-seed%s.json" % (stamp, seed))
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"seed": seed, "picked": [i + 1 for i in chosen],
                   "records": records}, f, ensure_ascii=False, indent=2)
    rep.note("")
    rep.note("结果 JSON：%s" % out)
    rep.finish()


if __name__ == "__main__":
    main()
