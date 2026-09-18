# -*- coding: utf-8 -*-
"""拖动完整性：复杂场景反复拖图形，看连线会不会消失。

回归目标（2026-09-16 用户报「拖动图案的时候有的连线会消失看不到了」）：
  1. 拖动前后元素总数不变、两端绑定的箭头数不变；
  2. 不产生非有限几何（NaN 会让整张图都看不见）；
  3. 画布墨迹量不塌陷（元素还在但没画出来 = 同样是"看不见"）；
  4. 只拖图形时**不许**上报删除或解绑（误报会让后端模型越删越少，不可逆）。

用法：python e2e_drag_integrity.py     （需先起前端 dev server）
"""
from playwright.sync_api import sync_playwright

from harness import Report, drag, generate, last_sync, open_page, scene_of

NODES = [
    ("n1", "ellipse", 0, 0, 140, 70, "开始"),
    ("n2", "rectangle", 260, 0, 180, 80, "接收订单"),
    ("n3", "diamond", 580, -10, 200, 120, "库存充足?"),
    ("n4", "rectangle", 900, -140, 180, 80, "锁定库存"),
    ("n5", "rectangle", 900, 120, 180, 80, "通知补货"),
    ("n6", "rectangle", 1220, -140, 180, 80, "生成支付单"),
    ("n7", "ellipse", 1220, 120, 160, 70, "缺货结束"),
    ("n8", "rectangle", 1540, -140, 180, 80, "支付回调"),
    ("n9", "diamond", 1860, -150, 200, 120, "支付成功?"),
    ("n10", "ellipse", 2160, -240, 160, 70, "发货"),
    ("n11", "ellipse", 2160, 20, 160, 70, "关闭订单"),
]
ARROWS = [
    ("a1", "n1", "n2", None), ("a2", "n2", "n3", None),
    ("a3", "n3", "n4", "是"), ("a4", "n3", "n5", "否"),
    ("a5", "n4", "n6", None), ("a6", "n5", "n7", None),
    ("a7", "n6", "n8", None), ("a8", "n8", "n9", None),
    ("a9", "n9", "n10", "是"), ("a10", "n9", "n11", "否"),
    ("a11", "n6", "n8", None), ("a12", "n2", "n5", None),
]
EXTRAS = [
    {"id": "free1", "type": "arrow", "x": 0, "y": 320,
     "points": [[0, 0], [300, 0]]},
    {"id": "t1", "type": "text", "x": 0, "y": 400,
     "text": "说明：这是一张订单处理流程图", "fontSize": 16},
]
MOVES = [
    ("n2", 90, 40), ("n3", -60, 70), ("n4", 120, -50), ("n6", -80, -60),
    ("n8", 100, 90), ("n9", -110, 30), ("n5", 70, -80), ("n10", -50, -40),
]


def main():
    rep = Report("拖动完整性：复杂场景 + 反复拖动图形")
    with sync_playwright() as pw:
        browser, page = open_page(pw, scene_of(NODES, ARROWS, EXTRAS), 1800, 1000)
        generate(page)

        base = page.evaluate("() => window.__h.snapshot()")
        ink0 = page.evaluate("() => window.__h.inkPixels()")
        rep.note("基线: 元素 %d，两端绑定箭头 %d，自由箭头 %d，墨迹 %d px"
                 % (base["total"], base["boundBoth"], base["freeArrow"], ink0))
        if base["nan"]:
            rep.fail("基线就有非有限几何: %s" % base["nanIds"])

        for i, (nid, dx, dy) in enumerate(MOVES, 1):
            before = page.evaluate("(x) => window.__h.center(x)", nid)
            if before is None:
                rep.fail("拖动前找不到节点 %s" % nid)
                continue
            drag(page, before["x"], before["y"], dx, dy)
            after = page.evaluate("(x) => window.__h.center(x)", nid)
            snap = page.evaluate("() => window.__h.snapshot()")
            ink = page.evaluate("() => window.__h.inkPixels()")
            moved = abs(after["x"] - before["x"]) > 5 or abs(after["y"] - before["y"]) > 5
            rep.note("drag#%d %s d=(%d,%d) moved=%s 总数=%d 双绑=%d 墨迹=%d"
                     % (i, nid, dx, dy, moved, snap["total"], snap["boundBoth"], ink))

            if not moved:
                rep.fail("drag#%d %s 没被真正拖动" % (i, nid))
            if snap["total"] != base["total"]:
                rep.fail("drag#%d %s 后元素总数 %d -> %d（丢了 %s）"
                         % (i, nid, base["total"], snap["total"],
                            [x for x in base["ids"] if x not in snap["ids"]]))
            if snap["boundBoth"] != base["boundBoth"]:
                rep.fail("drag#%d %s 后双绑箭头 %d -> %d"
                         % (i, nid, base["boundBoth"], snap["boundBoth"]))
            if snap["nan"]:
                rep.fail("drag#%d %s 后出现非有限几何: %s" % (i, nid, snap["nanIds"]))
            if ink < ink0 * 0.5:
                rep.fail("drag#%d %s 后墨迹骤减 %d -> %d（线条可能消失）"
                         % (i, nid, ink0, ink))

        body, count = last_sync(page)
        rep.note("同步请求数 %d，最后一次: removedIds=%s unboundArrows=%d userElements=%d"
                 % (count,
                    (body or {}).get("removedIds"),
                    len((body or {}).get("unboundArrows") or []),
                    len((body or {}).get("userElements") or [])))
        if not body:
            rep.fail("一次同步请求都没发出")
        else:
            if body.get("removedIds"):
                rep.fail("只拖了图形却上报了删除: %s（会让后端模型越删越少）"
                         % body["removedIds"])
            if body.get("unboundArrows"):
                rep.fail("只拖了图形却上报了解绑: %s" % body["unboundArrows"])
            if body.get("userElements"):
                rep.fail("画布上没有用户手绘却上报了: %s" % body["userElements"])

        browser.close()
    rep.finish()


main()
