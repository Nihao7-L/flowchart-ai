# -*- coding: utf-8 -*-
"""拖线解绑必须回写后端。

回归目标（2026-09-16 用户报「移动线条后这线条会看不到了」）：
用户在画布上拖动一条线时，Excalidraw 会解除它的端点绑定
（实测 n1 -> n2 变成 无 -> 无，元素本身还在）。如果不回写，
后端模型里那条线仍然写着绑定 —— 下一轮渲染又把它绑回两端图形之间，
用户拖的那一下白拖。

断言：
  1. 拖绑定箭头之后，上报里出现 unboundArrows 且含该箭头 id；
  2. 它带上自己的 points（解绑后必须能自己定位，否则是坏箭头）；
  3. 它**不**出现在 removedIds 里（元素还在，只是不绑了）；
  4. 没被拖过的箭头不许上报（避免每轮全量重报）。

用法：python e2e_arrow_unbind.py     （需先起前端 dev server）
"""
from playwright.sync_api import sync_playwright

from harness import Report, drag, generate, last_sync, open_page, scene_of

NODES = [
    ("n1", "rectangle", 0, 0, 160, 80, "A"),
    ("n2", "rectangle", 480, 0, 160, 80, "B"),
    ("n3", "rectangle", 960, 0, 160, 80, "C"),
]
ARROWS = [
    ("a12", "n1", "n2", None),
    ("a23", "n2", "n3", None),
]


def main():
    rep = Report("拖动线条：端点解绑必须回写（unboundArrows）")
    with sync_playwright() as pw:
        browser, page = open_page(pw, scene_of(NODES, ARROWS))

        # ---- 阶段一：只拖图形，不该产生任何解绑上报 ----
        generate(page)
        before = page.evaluate("() => window.__h.snapshot()")
        rep.note("基线: 双绑箭头 %d" % before["boundBoth"])
        c = page.evaluate("() => window.__h.center('n1')")
        drag(page, c["x"], c["y"], 0, 120)
        body, _ = last_sync(page)
        if (body or {}).get("unboundArrows"):
            rep.fail("拖动图形却上报了解绑: %s" % body["unboundArrows"])
        else:
            rep.note("拖图形后 unboundArrows 为空 —— 正确")

        # ---- 阶段二：拖箭头身体 ----
        geo_before = page.evaluate("() => window.__h.geo('a12')")
        rep.note("拖之前 a12: x=%.0f y=%.0f 绑定=(%s -> %s)"
                 % (geo_before["x"], geo_before["y"], geo_before["sb"], geo_before["eb"]))
        mid = page.evaluate("() => window.__h.arrowMid('a12')")
        drag(page, mid["x"], mid["y"], 0, 200)

        geo_after = page.evaluate("() => window.__h.geo('a12')")
        if geo_after is None:
            rep.fail("a12 从画布上消失了（它应该只是解绑，不该被删）")
        else:
            rep.note("拖之后 a12: x=%.0f y=%.0f 绑定=(%s -> %s)"
                     % (geo_after["x"], geo_after["y"],
                        geo_after["sb"], geo_after["eb"]))
            if geo_after["sb"] or geo_after["eb"]:
                rep.note("注意：Excalidraw 这次没有解绑（行为可能随版本变化），"
                         "下面只检查上报是否与画布一致")

        body, _ = last_sync(page)
        unbound = (body or {}).get("unboundArrows") or []
        removed = (body or {}).get("removedIds") or []
        rep.note("上报: unboundArrows=%s removedIds=%s"
                 % ([u.get("id") for u in unbound], removed))

        if geo_after is not None and not geo_after["sb"] and not geo_after["eb"]:
            ids = [u.get("id") for u in unbound]
            if "a12" not in ids:
                rep.fail("画布上 a12 已解绑，却没有上报（后端模型会继续以为它绑着，"
                         "下一轮把它绑回原处 —— 用户白拖）")
            else:
                brief = [u for u in unbound if u.get("id") == "a12"][0]
                rep.note("a12 上报: x=%s y=%s points=%s"
                         % (brief.get("x"), brief.get("y"), brief.get("points")))
                if not brief.get("points") or len(brief["points"]) < 2:
                    rep.fail("解绑的线必须带自己的 points，否则模型里是坏箭头")
                if "a23" in ids:
                    rep.fail("a23 没被拖过却也被上报解绑: %s" % ids)
                if "a12" in removed:
                    rep.fail("解绑的线上报成了删除: %s" % removed)

        browser.close()
    rep.finish()


main()
