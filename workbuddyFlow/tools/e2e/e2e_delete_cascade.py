# -*- coding: utf-8 -*-
"""删图形必须连坐删连线（真实 UI 操作）。

回归目标（2026-09-16 用户报「让他删除几个节点后，画出来的图有的地方
多加了这么多的线」）：在真实 UI 里选中一个图形按 Delete，
**它的箭头不会被一起删**（实测只上报了 removedIds:["n2"]，
而连着 n2 的两条线原样留在画布上）。留在画布上的那段线连向空处，
用户看到的就是"凭空多出来的线"。

断言：
  1. 删掉 n2 后，绑定到 n2 的 AI 连线从画布上消失；
  2. removedIds 里含 n2 与那两条线（后端模型才会一起删）；
  3. 与 n2 无关的元素原样保留；
  4. 用户自己手绘的线**不许**被连坐（我们无权因他删图形就抹掉他的涂画）。

用法：python e2e_delete_cascade.py     （需先起前端 dev server）
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
USER_ARROW = {
    "id": "user-arrow-1", "type": "arrow",
    "start": {"id": "n1"}, "end": {"id": "n2"},
}


def add_user_arrow(page):
    """在画布上放一条"用户手绘"的线（模型元素带 customData 标记，手绘的不带）。"""
    return page.evaluate("""() => {
      const api = window.__chartflow;
      const els = api.getSceneElements();
      const src = els.find((e) => e.id === 'a12');
      const u = JSON.parse(JSON.stringify(src));
      u.id = 'user-arrow-1';
      u.customData = {};
      u.startBinding = {elementId: 'n1', focus: 0, gap: 4};
      u.endBinding = {elementId: 'n2', focus: 0, gap: 4};
      api.updateScene({elements: [...els, u]});
      const back = api.getSceneElements().find((e) => e.id === 'user-arrow-1');
      return {found: !!back, hasFlag: !!(back && back.customData && back.customData.chartflowModel)};
    }""")


def main():
    rep = Report("删除连坐：删图形必须连带删掉它的 AI 连线")
    with sync_playwright() as pw:
        browser, page = open_page(pw, scene_of(NODES, ARROWS))
        generate(page)

        info = add_user_arrow(page)
        rep.note("用户手绘线就位: %s" % info)
        if not info["found"] or info["hasFlag"]:
            rep.fail("用户手绘线没放成功，或它带上了模型标记（测试前提不成立）")
        page.wait_for_timeout(900)
        ids0 = page.evaluate("() => window.__h.ids()")
        rep.note("删除前: %s" % ids0)

        # 真实点击选中 n2，按 Delete
        c = page.evaluate("() => window.__h.center('n2')")
        pt = page.evaluate("(a) => window.__h.toPointPage ? null : window.__h.toPage(a[0], a[1])",
                           [c["x"], c["y"]])
        page.mouse.click(pt["x"], pt["y"])
        page.wait_for_timeout(400)
        selected = page.evaluate(
            "() => Object.keys(window.__chartflow.getAppState().selectedElementIds || {})")
        rep.note("选中: %s" % selected)
        if "n2" not in selected:
            rep.fail("没选中 n2（测试前提不成立）")
        page.keyboard.press("Delete")
        page.wait_for_timeout(1200)

        ids = page.evaluate("() => window.__h.ids()")
        body, count = last_sync(page)
        removed = (body or {}).get("removedIds") or []
        rep.note("删除后: %s" % ids)
        rep.note("同步请求 %d 次；removedIds=%s" % (count, removed))

        for gone in ("a12", "a23"):
            if gone in ids:
                rep.fail("%s 还留在画布上 —— 就是用户看到的「凭空多出来的线」" % gone)
        for kept in ("n1", "n3"):
            if kept not in ids:
                rep.fail("与 n2 无关的 %s 被误删了" % kept)
        for rid in ("n2", "a12", "a23"):
            if rid not in removed:
                rep.fail("removedIds 里缺 %s（后端模型不会删它，下一轮会复活）" % rid)
        if "user-arrow-1" not in ids:
            rep.fail("用户自己手绘的线被连坐删掉了（我们无权动它）")
        if "user-arrow-1" in removed:
            rep.fail("用户手绘的线被上报成删除: %s" % removed)

        browser.close()
    rep.finish()


main()
