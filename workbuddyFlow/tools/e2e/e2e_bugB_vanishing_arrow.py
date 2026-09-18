# -*- coding: utf-8 -*-
"""Bug B 回归：拖动后连线被污染成哨兵坐标 → 自愈，不消失。

复现（2026-09-17 用户报「拖动生成组件时连线透明消失不见」）：
  拖动一个绑定元素使它在拖动途中与另一绑定元素重叠，且拖动伴随 fit()
  缩放变化时，Excalidraw 内部绑定重算会吐出哨兵坐标（先 1024、随后 2^21
  ≈ 2097152），把箭头甩到画布外 → 视觉上「连线透明消失」。该坐标污染
  不会自愈（每帧 a.x = -2097053 永久卡死）。

本回归把一只「两端都绑定、但几何已是哨兵坐标」的箭头直接注入场景，
验证：
  1. 生成落定后箭头仍存在于画布（没被删、没在视口外消失）；
  2. 箭头的 x / y / points 全部为有限数且落在合理范围（已自愈）；
  3. 箭头的 start / end 绑定仍指向原图形（几何由绑定反推得到）；
  4. 箭头中点位于两端图形的连线上（确实画出来了，不是凭空消失）；
  5. 像用户那样把 n1 转圈圈拖动后，箭头几何**不再**被污染成哨兵值。

用法：python e2e_bugB_vanishing_arrow.py   （需先起前端 dev server）
"""
import json

from playwright.sync_api import sync_playwright

from harness import Report, drag, generate, open_page

SENTINEL = 2097152  # 2^21：Excalidraw 绑定重算吐出的哨兵坐标

# 两个图形 + 一只被污染成哨兵坐标的绑定箭头。
# 注意：x/y 是有限数（2097152 是有限数），所以场景转换器不会把箭头
# 当成「缺几何」去重算，污染值会原样进入 Excalidraw 场景 —— 这正是
# healCorruptedArrows 要兜住的情形。
CORRUPTED_SCENE = {
    "elements": [
        {"id": "n1", "type": "rectangle", "x": 0, "y": 0,
         "width": 160, "height": 80, "label": {"text": "接收订单"}},
        {"id": "n2", "type": "ellipse", "x": 420, "y": 0,
         "width": 180, "height": 80, "label": {"text": "处理中"}},
        {"id": "a1", "type": "arrow",
         "x": -2097053, "y": -2097053,
         "points": [[SENTINEL, SENTINEL], [SENTINEL + 4, SENTINEL + 8]],
         "start": {"id": "n1"}, "end": {"id": "n2"}},
    ]
}

# 把场景塞进 harness 的注入桩（与 harness.PAGE_HELPERS 同款替换）
def build_init_script():
    from harness import PAGE_HELPERS
    return PAGE_HELPERS.replace("__SCENE_JSON__",
                                json.dumps(json.dumps(CORRUPTED_SCENE)))


def geo(page, el_id):
    return page.evaluate("(id) => window.__h.geo(id)", el_id)


def check_healed(page, rep, when):
    g = geo(page, "a1")
    if g is None:
        rep.fail("%s: 箭头 a1 已从画布消失（正是 Bug B 的现象）" % when)
        return
    rep.note("%s: a1 几何 x=%.1f y=%.1f sb=%s eb=%s"
             % (when, g["x"], g["y"], g["sb"], g["eb"]))
    if g["sb"] != "n1" or g["eb"] != "n2":
        rep.fail("%s: a1 绑定丢失 sb=%s eb=%s" % (when, g["sb"], g["eb"]))
    if not all(isinstance(v, (int, float)) and abs(v) < 100000
               for v in (g["x"], g["y"])):
        rep.fail("%s: a1 坐标仍被污染（自愈失败）x=%s y=%s"
                 % (when, g["x"], g["y"]))
    for p in (g["points"] or []):
        if not all(isinstance(v, (int, float)) and abs(v) < 100000 for v in p):
            rep.fail("%s: a1 points 仍含哨兵坐标 %s" % (when, g["points"]))
    # 箭头中点应落在两节点连线上（确实画出来了）
    c1 = page.evaluate("(id) => window.__h.center(id)", "n1")
    c2 = page.evaluate("(id) => window.__h.center(id)", "n2")
    mid = page.evaluate("(id) => window.__h.arrowMid(id)", "a1")
    if c1 and c2 and mid:
        # 两端点中心的中点 = 连线中点；箭头中点应离它很近（在同一条线上）
        expect_x = (c1["x"] + c2["x"]) / 2
        expect_y = (c1["y"] + c2["y"]) / 2
        d = abs(mid["x"] - expect_x) + abs(mid["y"] - expect_y)
        rep.note("%s: 箭头中点=(%.0f,%.0f) 期望连线中点=(%.0f,%.0f) 偏差=%d"
                 % (when, mid["x"], mid["y"], expect_x, expect_y, d))
        if d > 400:
            rep.fail("%s: 箭头中点偏离连线（线没画在两端之间）偏差=%d"
                     % (when, d))


def main():
    rep = Report("Bug B：被污染连线自愈（拖动后不消失）")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            headless=True, proxy={"server": "direct://"},
            args=["--no-proxy-server", "--proxy-bypass-list=*"])
        page = browser.new_page(viewport={"width": 1600, "height": 900})
        page.add_init_script(build_init_script())
        page.goto("http://127.0.0.1:5173/", wait_until="load")
        page.wait_for_function("() => !!window.__chartflow", timeout=40000)

        # 1) 注入被污染场景 + 触发生成（flushSync 会跑 healCorruptedArrows）
        generate(page)
        check_healed(page, rep, "生成落定后")

        # 2) 像用户那样把 n1「转圈圈」拖动，反复触发绑定重算
        c1 = page.evaluate("(id) => window.__h.center(id)", "n1")
        if c1 is None:
            rep.fail("拖动前找不到 n1")
        else:
            for dx, dy in [(60, 40), (-50, 60), (-70, -40),
                           (40, -70), (90, 30), (-30, -50)]:
                drag(page, c1["x"], c1["y"], dx, dy)
                c1 = page.evaluate(
                    "(id) => window.__h.center(id)", "n1")

        # 3) 转圈圈之后再检查一次：箭头几何必须仍是安全的
        check_healed(page, rep, "转圈圈拖动后")

        # 4) 画布确实画出了东西（墨迹 > 0）
        ink = page.evaluate("() => window.__h.inkPixels()")
        rep.note("画布墨迹 %d px" % ink)
        if ink <= 0:
            rep.fail("画布没有任何墨迹（连线消失了）")

        browser.close()
    rep.finish()


main()
