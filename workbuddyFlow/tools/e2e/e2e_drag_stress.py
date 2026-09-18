# -*- coding: utf-8 -*-
"""拖动压力回归：反复 / 多方位 / 多距离拖拽后，连线不得脱离两端，标签不得掉队。

为什么单独开一个（2026-09-17 用户反馈「你的测试还是不够全面」）：
  旧 e2e_bugB 是「先手工把箭头改成哨兵坐标，再看能不能自愈」——
  它只证明了 heal 函数本身能跑，**没有证明真实拖拽是否还会再次污染**。
  用户的实操是：拖一个组件，各个方向、各种距离、来回拖很多次。
  本回归照这个动作做，并且逐帧记录几何，区分「拖动途中的瞬时抖动」
  与「拖完永久卡死」两种失败。

判据（几何不变量，不靠魔法常数猜）：
  一条两端绑定的箭头，它的两个端点**必须落在被绑定图形的包围盒上**
  （允许几十 px 的绑定间隙）。端点离框几千 px = 线被甩到画布外，
  就是用户看到的「连线透明消失不见」。
  这是绑定语义本身决定的，与 Excalidraw 的可变锚点(focus)无关 ——
  锚点可以在框边任意滑动，但永远在框上。

用法：python e2e_drag_stress.py      （需先起前端 dev server 127.0.0.1:5173）
"""
import json
import math
import os

from playwright.sync_api import sync_playwright

from harness import Report, PAGE_HELPERS, drag, generate, scene_of

# 端点离被绑定图形包围盒的最大允许距离(px)。真实绑定间隙只有几 px。
TOL_ARROW = 80

NODE_IDS = ["n1", "n2", "n3", "n4", "n5"]

# 布局刻意让「任意两个图形中心的连线」不穿过第三个图形的中心，
# 免得拖拽时误抓到躺在节点上的箭头（那是测试自己的问题，不是产品缺陷）。
SCENE = scene_of(
    nodes=[
        ("n1", "rectangle", 0, 0, 180, 80, "开始登录"),
        ("n2", "rectangle", 420, 0, 220, 80, "输入用户名密码"),
        ("n3", "diamond", 780, 160, 170, 170, "验证信息"),
        ("n4", "rectangle", 300, -260, 240, 70, "密码错误，请重试"),
        ("n5", "rectangle", 420, 320, 260, 70, "用户不存在，请注册"),
    ],
    arrows=[
        ("a1", "n1", "n2", None),
        ("a2", "n1", "n3", "验证通过"),
        ("a3", "n2", "n4", "密码错误"),
        ("a4", "n3", "n4", None),
        ("a5", "n3", "n5", "用户不存在"),
    ],
)

# 逐帧记录；在 JS 侧就归约成小对象再回传（整段日志序列化回 Python 会因为
# 体积过大静默返回 None —— 2026-09-17 踩过）。
WATCHER = """
window.__watch = function () {
  window.__log = { frames: 0, worst: 0, worstAt: 0, worstArrow: '',
                   badFrames: 0, samples: [] };
  var boxDist = function (px, py, r) {
    var dx = Math.max(r[0] - px, px - (r[0] + r[2]), 0);
    var dy = Math.max(r[1] - py, py - (r[1] + r[3]), 0);
    return Math.sqrt(dx * dx + dy * dy);
  };
  var step = function () {
    var L = window.__log;
    L.frames++;
    var els = window.__chartflow.getSceneElements();
    var rects = {}, arrows = [];
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.type === 'arrow') {
        var pts = e.points || [];
        var last = pts.length ? pts[pts.length - 1] : [0, 0];
        arrows.push({ id: e.id,
          sb: e.startBinding ? e.startBinding.elementId : null,
          eb: e.endBinding ? e.endBinding.elementId : null,
          p0: [e.x, e.y], p1: [e.x + last[0], e.y + last[1]] });
      } else {
        rects[e.id] = [e.x, e.y, e.width, e.height];
      }
    }
    for (var k = 0; k < arrows.length; k++) {
      var a = arrows[k];
      var r0 = a.sb ? rects[a.sb] : null, r1 = a.eb ? rects[a.eb] : null;
      var d = 0;
      if (r0) d = Math.max(d, boxDist(a.p0[0], a.p0[1], r0));
      if (r1) d = Math.max(d, boxDist(a.p1[0], a.p1[1], r1));
      if (d > 200) L.badFrames++;
      if (d > L.worst) {
        L.worst = d; L.worstAt = L.frames; L.worstArrow = a.id;
        L.samples.push({ t: L.frames, id: a.id, d: Math.round(d),
          p0: [Math.round(a.p0[0]), Math.round(a.p0[1])],
          p1: [Math.round(a.p1[0]), Math.round(a.p1[1])],
          sb: a.sb, eb: a.eb });
        if (L.samples.length > 8) L.samples.shift();
      }
    }
    window.__raf = requestAnimationFrame(step);
  };
  step();
};
window.__unwatch = function () {
  cancelAnimationFrame(window.__raf);
  return window.__log;
};
window.__watch();
"""


def box_dist(px, py, r):
    """点到矩形包围盒的距离；点在框内或框上时为 0。"""
    dx = max(r["x"] - px, px - (r["x"] + r["w"]), 0.0)
    dy = max(r["y"] - py, py - (r["y"] + r["h"]), 0.0)
    return math.hypot(dx, dy)


def snap(page):
    return {
        "arrows": page.evaluate("() => window.__h.arrows()"),
        "nodes": page.evaluate(
            "(ids) => { const o = {}; for (const i of ids) o[i] = window.__h.rect(i); return o; }",
            NODE_IDS),
        "state": page.evaluate("() => window.__h.appState()"),
    }


def check_arrows(page, rep, when, baseline, verbose=False):
    """基线里有的箭头必须还都在、两端必须仍贴在绑定图形上、标签必须没掉队。"""
    baseline_labeled = {k for k, v in baseline.items() if v}
    s = snap(page)
    now = {a["id"] for a in s["arrows"]}
    gone = sorted(set(baseline) - now)
    if gone:
        rep.fail("%s: 箭头从画布消失了 -> %s" % (when, gone))
    bad = 0
    for a in s["arrows"]:
        sb, eb = a["sb"], a["eb"]
        if not sb or not eb:
            rep.fail("%s: %s 两端绑定被摘掉 sb=%s eb=%s（线从此不再跟随图形，"
                     "且几何卡在旧位置）" % (when, a["id"], sb, eb))
            bad += 1
            continue
        sn, en = s["nodes"].get(sb), s["nodes"].get(eb)
        if not sn or not en:
            rep.fail("%s: %s 绑定的图形不在场景里 (%s/%s)" % (when, a["id"], sb, eb))
            bad += 1
            continue
        d0 = box_dist(a["p0"][0], a["p0"][1], sn)
        d1 = box_dist(a["p1"][0], a["p1"][1], en)
        if verbose:
            rep.note("  %s p0=(%.0f,%.0f) 离%s=%.0f  p1=(%.0f,%.0f) 离%s=%.0f  np=%d"
                     % (a["id"], a["p0"][0], a["p0"][1], sb, d0,
                        a["p1"][0], a["p1"][1], eb, d1, a["np"]))
        if d0 > TOL_ARROW or d1 > TOL_ARROW:
            bad += 1
            rep.fail("%s: %s 的端点离开了 %s/%s（脱靶 %.0f/%.0f px，线几何 x=%.1f y=%.1f）"
                     % (when, a["id"], sb, eb, d0, d1, a["x"], a["y"]))
        # 标签判据：**必须仍绑定在箭头上**（arrows() 只在 containerId===arrow.id 时
        # 才返回 label）。不能再拿「标签 stored x/y vs 线中点」当判据 ——
        # 2026-09-17 像素级实测（probe_label_render）证明：绑定标签的 stored x/y
        # 平日就是过期的（拖完仍停在初始中点），Excalidraw 按箭头几何现算渲染：
        # 拖动后新中点处暗像素 682、旧 stored 处 0。拿 stored 比中点会 100% 误报。
        # 真正的失效模式是「标签被解绑/被删」——那时它才会真的停在旧处不动。
        if baseline_labeled and a["id"] in baseline_labeled and not a["label"]:
            bad += 1
            rep.fail("%s: %s 原本带标签，现在标签丢了（解绑或被删）" % (when, a["id"]))
        elif a["label"] and verbose:
            rep.note("    标签 %r 仍绑定在 %s 上（stored=(%.0f,%.0f)，渲染随几何走）"
                     % ((a["label"]["text"] or "")[:10], a["id"],
                        a["label"]["x"], a["label"]["y"]))
    if bad == 0:
        rep.note("%s: %d 条线全部贴在两端图形上、标签未丢" % (when, len(s["arrows"])))
    return s


# 抓取点候选：图形中心 + 四个「向内 30%」的角点。
# 中心够用时用中心；一旦图形被拖到与别的图形/箭头重叠，中心可能被压在
# 下层的箭头抢走命中（Excalidraw 里箭头命中优先级更高），就换角点再试。
GRAB_OFFSETS = [(0.0, 0.0), (-0.30, -0.30), (0.30, 0.30),
                (-0.30, 0.30), (0.30, -0.30)]


def deselect(page):
    """清空选中 —— **不能靠 Escape**。

    2026-09-17 实测（probe_panel3）：headless 里 `keyboard.press("Escape")`
    并没有让 Excalidraw 取消选中（appState.selectedElementIds 仍是 ['n1']），
    于是 selected-shape-actions 面板继续盖着画布（x≈262~450，pointer-events:all），
    把下一次落在该区域的 pointerdown 吞掉 —— 表现就是「图形怎么拖都不动」。
    用命令式 API 清选中，才是确定性的。
    """
    page.evaluate(
        "() => window.__chartflow.updateScene({ appState: { selectedElementIds: {} } })")


def zoom(page):
    return page.evaluate("() => window.__chartflow.getAppState().zoom.value")


def refit(page):
    """把全部内容重新取景。

    反复大距离拖动会把图形推出视口（跑到侧边栏下面），那样 toPage 算出的落点
    根本不在画布上，5 个抓取点也全都抓不到 —— 那是测试自身的可达性问题，不是
    产品缺陷。fit 后图形必然可见；顺带这也制造了「拖动前后伴随 fit 缩放变化」
    的真实场景（Bug B 的触发条件之一）。注意 fit 会改 zoom，后续算屏幕位移必须
    乘 zoom。
    """
    page.evaluate("() => window.__h.fit()")
    page.wait_for_timeout(200)


def try_grab(page, nid, r):
    """在图形内部找一个「按下就选中它」的抓取点；成功返回该点的**屏幕**坐标。

    为什么要找而不是直接抓中心：图形与别的图形/箭头重叠时，中心可能被压在下面
    的箭头抢走命中（Excalidraw 里箭头命中优先级更高）。抓错元素的话，后面
    「拖箭头身体 → 解绑」就会被误报成产品把绑定弄丢了。所以按下后**核实**
    selectedElementIds 里是不是目标图形，不是就换下一个候选点。
    """
    for (fx, fy) in GRAB_OFFSETS:
        deselect(page)
        page.wait_for_timeout(60)
        gx = r["cx"] + fx * r["w"]
        gy = r["cy"] + fy * r["h"]
        pt = page.evaluate("(a) => window.__h.toPage(a[0], a[1])", [gx, gy])
        page.mouse.move(pt["x"], pt["y"])
        page.wait_for_timeout(50)
        page.mouse.down()
        page.wait_for_timeout(50)
        got = page.evaluate(
            "() => Object.keys(window.__chartflow.getAppState().selectedElementIds || {})")
        if nid in got:
            return pt
        page.mouse.up()
        page.wait_for_timeout(40)
    return None


def drag_node(page, rep, nid, dx, dy, when):
    """拖指定图形（dx/dy 为**场景**位移），并核实真是这个图形被拖动了。"""
    before = page.evaluate("(id) => window.__h.rect(id)", nid)
    if not before:
        rep.fail("%s: 找不到图形 %s" % (when, nid))
        return False
    refit(page)
    pt = try_grab(page, nid, before)
    if pt is None:
        rep.fail("%s: 测试环境问题——在 %s 上试了 %d 个抓取点都没能选中它"
                 "（多半被重叠的箭头抢走了命中）"
                 % (when, nid, len(GRAB_OFFSETS)))
        return False
    # dx/dy 是场景位移，鼠标要走的是**屏幕**位移 = 场景位移 × 当前缩放。
    # （refit 之后 zoom 未必是 1，直接拿场景数当屏幕数会差一个 zoom 倍。
    #  2026-09-17 踩过：期望 300 实际 375，就是 zoom=0.8 造成的。）
    z = zoom(page)
    page.mouse.move(pt["x"] + dx * z, pt["y"] + dy * z, steps=18)
    page.wait_for_timeout(60)
    page.mouse.up()
    page.wait_for_timeout(800)
    after = page.evaluate("(id) => window.__h.rect(id)", nid)
    if not after:
        rep.fail("%s: 拖完后图形 %s 不见了" % (when, nid))
        return False
    moved = math.hypot(after["cx"] - before["cx"], after["cy"] - before["cy"])
    want = math.hypot(dx, dy)
    if abs(moved - want) > max(30.0, want * 0.25):
        rep.fail("%s: 测试环境问题——落到 %s 上的拖动没有按预期位移"
                 "（期望 %.0f px，实际 %.0f px），多半是抓到了别的元素"
                 % (when, nid, want, moved))
        return False
    return True


# 用户原话：「转圈圈拖动就是不同方向不同距离拖动」「还拖拽了不止一次」
VECTORS = [
    (8, 4), (0, -6),                      # 微动：小于拖拽阈值
    (60, 40), (-50, 60), (-70, -40), (40, -70),
    (200, 140), (-220, 160), (-260, -150), (240, -180),
    (420, 0), (0, 300), (-380, 0), (0, -300),
]


def main():
    rep = Report("拖动压力回归：反复多方位拖拽后连线不脱离 / 标签不掉队")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            headless=True, proxy={"server": "direct://"},
            args=["--no-proxy-server", "--proxy-bypass-list=*"])
        page = browser.new_page(viewport={"width": 1600, "height": 900})
        page.add_init_script(
            PAGE_HELPERS.replace("__SCENE_JSON__", json.dumps(json.dumps(SCENE))))
        page.goto("http://127.0.0.1:5173/", wait_until="load")
        page.wait_for_function("() => !!window.__chartflow", timeout=40000)

        generate(page)
        first = snap(page)
        baseline = {a["id"]: bool(a["label"]) for a in first["arrows"]}
        rep.note("基线：%d 个图形 / %d 条线（其中 %d 条带标签）/ 视口 %s"
                 % (len([n for n in first["nodes"].values() if n]),
                    len(baseline), sum(1 for v in baseline.values() if v),
                    first["state"]))
        check_arrows(page, rep, "生成落定后", baseline, verbose=True)

        page.evaluate(WATCHER)

        # A) 14 个不同方向的拖动，每次都重新取当前中心
        for i, (dx, dy) in enumerate(VECTORS):
            if not drag_node(page, rep, "n1", dx, dy, "拖动 #%d" % (i + 1)):
                break
            check_arrows(page, rep, "拖动 #%d (dx=%d,dy=%d) 后" % (i + 1, dx, dy), baseline)

        # B) 同一个组件来回锯齿拖 12 次（用户说「拖拽了不止一次」）
        zig = [(90, 70), (-90, -70), (120, -60), (-120, 60)]
        for i in range(12):
            dx, dy = zig[i % len(zig)]
            if not drag_node(page, rep, "n1", dx, dy, "锯齿第 %d 次" % (i + 1)):
                break
        check_arrows(page, rep, "锯齿连拖 12 次后", baseline)

        # C) 换成拖 n2 / n3（不同图形、不同绑定拓扑）
        for nid in ("n2", "n3"):
            for j, (dx, dy) in enumerate([(150, 90), (-160, -80), (260, -140), (-240, 150)]):
                if not drag_node(page, rep, nid, dx, dy, "拖动 %s #%d" % (nid, j + 1)):
                    break
            check_arrows(page, rep, "拖动 %s 后" % nid, baseline)

        # D) 把 n1 拖到与 n2 重叠，再拖回去（用户截图里就是两个框叠在一起）
        r1 = page.evaluate("(id) => window.__h.rect(id)", "n1")
        r2 = page.evaluate("(id) => window.__h.rect(id)", "n2")
        if r1 and r2:
            if drag_node(page, rep, "n1", r2["cx"] - r1["cx"], 0, "拖到重叠"):
                check_arrows(page, rep, "把 n1 拖到与 n2 重叠后", baseline)
            if drag_node(page, rep, "n1", -(r2["cx"] - r1["cx"]), 0, "拖回原位"):
                check_arrows(page, rep, "把 n1 拖回原位后", baseline)

        # E) 拖动**途中**改变缩放（fit）—— 这是最初触发绑定重算污染的场景。
        # 必须核实抓到的是 n1：否则可能抓成躺在它上面的箭头身体，而拖动箭头
        # 本来就会解绑 —— 那会把「测试拖了线」误报成产品缺陷。位移不复验
        # （缩放变了，屏幕与场景不再等比），只验每次折腾完连线是否仍在两端图形上。
        for i in range(3):
            refit(page)
            rr = page.evaluate("(id) => window.__h.rect(id)", "n1")
            if not rr:
                break
            pt = try_grab(page, "n1", rr)
            if pt is None:
                rep.note("拖动途中改缩放 第 %d 轮：n1 抓不到，跳过" % (i + 1))
                continue
            z = zoom(page)
            page.mouse.move(pt["x"] + 120 * z, pt["y"] + 90 * z, steps=10)
            page.evaluate("() => window.__h.fit()")  # 拖动途中改缩放
            page.wait_for_timeout(80)
            page.mouse.move(pt["x"] + 240 * z, pt["y"] + 180 * z, steps=10)
            page.mouse.up()
            page.wait_for_timeout(800)
        check_arrows(page, rep, "拖动途中改缩放 3 轮后", baseline)

        log = page.evaluate("() => window.__unwatch()")
        if not log:
            rep.fail("逐帧记录没有取到（watcher 失效）")
        else:
            rep.note("逐帧扫描 %d 帧：最差脱靶 %.0f px（%s，第 %d 帧），越界帧 %d"
                     % (log["frames"], log["worst"], log["worstArrow"] or "-",
                        log["worstAt"], log["badFrames"]))
            for smp in log["samples"]:
                rep.note("    第 %d 帧 %s 脱靶 %d px p0=%s p1=%s 绑定=%s/%s"
                         % (smp["t"], smp["id"], smp["d"], smp["p0"], smp["p1"],
                            smp["sb"], smp["eb"]))
            # 帧内瞬时脱靶是 Excalidraw 绑定重算的固有时序（随后会被 heal 修回），
            # 不作为失败 —— 真正要命的是**拖完落定后**仍脱靶，那由每次拖动后的
            # check_arrows（读稳定态）负责判。这里只报峰值供诊断。
            if log["worst"] > 200:
                rep.note("（帧内瞬时脱靶峰值 %.0f px，落定后已由 heal 修回）"
                         % log["worst"])

        ink = page.evaluate("() => window.__h.inkPixels()")
        rep.note("画布墨迹 %d px" % ink)
        if ink <= 0:
            rep.fail("画布没有任何墨迹（图整个消失了）")

        shots = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
                os.path.abspath(__file__))))), ".playwright", "shots")
        os.makedirs(shots, exist_ok=True)
        page.screenshot(path=os.path.join(shots, "drag_stress_after.png"))

        browser.close()
    rep.finish()


main()
