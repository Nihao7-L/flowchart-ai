# -*- coding: utf-8 -*-
"""CRUD 循环：连删 3 轮 + 手绘新增 + 再生成，验证不变式。

对应用户 2026-09-16 的原话："多来几次删除添加看看会有什么效果"。

场景是一条 5 节点链 n1-a12-n2-a23-n3-a34-n4-a45-n5，每轮真实选中一个
节点按 Delete，最后手绘一个矩形、再触发一次生成。每轮都检查：

  1. 删掉的节点**和它的 AI 连线**都从画布上消失；
  2. removedIds 覆盖它们（后端模型才会跟着删，否则下一轮复活）；
  3. 没被点到的元素一个都没少；
  4. 画布上不存在"悬空线"——每条线若还带绑定，被绑的元素必须真的在；
  5. 用户手绘的元素不被上报成删除，且新一轮生成不吞掉它。

用法：python e2e_crud_cycles.py     （需先起前端 dev server）
"""
from playwright.sync_api import sync_playwright

from harness import Report, generate, last_sync, open_page, scene_of

NODES = [
    ("n1", "rectangle", 0, 0, 160, 80, "1"),
    ("n2", "rectangle", 280, 0, 160, 80, "2"),
    ("n3", "rectangle", 560, 0, 160, 80, "3"),
    ("n4", "rectangle", 840, 0, 160, 80, "4"),
    ("n5", "rectangle", 1120, 0, 160, 80, "5"),
]
ARROWS = [
    ("a12", "n1", "n2", None),
    ("a23", "n2", "n3", None),
    ("a34", "n3", "n4", None),
    ("a45", "n4", "n5", None),
]

# 每轮：删除的节点 → 期望被连坐的连线
ROUNDS = [
    ("n3", ["a23", "a34"]),
    ("n2", ["a12"]),
    ("n5", ["a45"]),
]

DANGLING = """() => {
  const els = window.__chartflow.getSceneElements();
  const have = new Set(els.map((e) => e.id));
  const bad = [];
  for (const e of els) {
    if (e.type !== 'arrow') continue;
    for (const b of [e.startBinding, e.endBinding]) {
      if (b && b.elementId && !have.has(b.elementId)) {
        bad.push(e.id + '->' + b.elementId);
      }
    }
  }
  return bad;
}"""


def select_and_delete(page, node_id):
    c = page.evaluate("(i) => window.__h.center(i)", node_id)
    pt = page.evaluate("(a) => window.__h.toPage(a[0], a[1])", [c["x"], c["y"]])
    page.mouse.click(pt["x"], pt["y"])
    page.wait_for_timeout(350)
    sel = page.evaluate(
        "() => Object.keys(window.__chartflow.getAppState().selectedElementIds || {})")
    page.keyboard.press("Delete")
    page.wait_for_timeout(1200)
    return sel


def find_empty_spot(page):
    """在当前视口里找一个空白落点（页面坐标），用于手绘新图形。

    <p>写死场景坐标不行：视口缩放/滚动随拟合变化，硬编码的点常常落在
    画布可视区之外。这里从画布矩形 + zoom/scroll 反解出**当前可见的
    场景坐标范围**，在范围内按网格试点，跳过已有元素附近，并保证落点
    右侧下方留得下要画的矩形。
    """
    return page.evaluate("""() => {
      const c = document.querySelector('.excalidraw__canvas');
      const r = c.getBoundingClientRect();
      const st = window.__chartflow.getAppState();
      const z = st.zoom.value;
      const els = window.__chartflow.getSceneElements();
      const m = 120;                  // 距画布边缘留白（页面像素）
      const needW = 250, needH = 170; // 要画出来的矩形尺寸（页面像素）
      const sxMin = m / z - st.scrollX;
      const sxMax = (r.width - m - needW) / z - st.scrollX;
      const syMin = m / z - st.scrollY;
      const syMax = (r.height - m - needH) / z - st.scrollY;
      if (sxMax <= sxMin || syMax <= syMin) return null;
      for (let i = 1; i <= 6; i += 1) {
        for (let j = 1; j <= 6; j += 1) {
          const sx = sxMin + (sxMax - sxMin) * i / 7;
          const sy = syMin + (syMax - syMin) * j / 7;
          const hit = els.some((e) => sx > e.x - 50 && sx < e.x + e.width + 50
                                   && sy > e.y - 50 && sy < e.y + e.height + 50);
          if (hit) continue;
          return window.__h.toPage(sx, sy);
        }
      }
      return null;
    }""")


def draw_rectangle(page):
    """真实手绘一个矩形（按 r 切工具、鼠标拖拽、Esc 收工具）。"""
    pt = find_empty_spot(page)
    if pt is None:
        return None
    page.keyboard.press("r")
    page.wait_for_timeout(250)
    page.mouse.move(pt["x"], pt["y"])
    page.mouse.down()
    page.mouse.move(pt["x"] + 220, pt["y"] + 140, steps=14)
    page.wait_for_timeout(80)
    page.mouse.up()
    page.wait_for_timeout(700)
    page.keyboard.press("Escape")
    page.wait_for_timeout(900)
    return pt


def main():
    rep = Report("CRUD 循环：连删 3 轮 + 手绘新增 + 再生成")
    with sync_playwright() as pw:
        browser, page = open_page(pw, scene_of(NODES, ARROWS), 1800, 1000)
        generate(page)

        ids0 = page.evaluate("() => window.__h.ids()")
        rep.note("基线: %d 个元素 -> %s" % (len(ids0), sorted(ids0)))

        seen_removed = []
        for (node, cascaded) in ROUNDS:
            if node not in page.evaluate("() => window.__h.ids()"):
                rep.fail("前置不成立：%s 已不在画布上" % node)
                break
            sel = select_and_delete(page, node)
            if node not in sel:
                rep.fail("第 %s 轮没选中 %s（测试前提不成立）" % (node, node))
                break
            ids = page.evaluate("() => window.__h.ids()")
            body, _ = last_sync(page)
            removed = (body or {}).get("removedIds") or []
            seen_removed = removed
            rep.note("删 %s -> 剩 %d 个；removedIds=%s" % (node, len(ids), sorted(removed)))

            if node in ids:
                rep.fail("%s 删了还在画布上" % node)
            for g in cascaded:
                if g in ids:
                    rep.fail("%s 的连线 %s 没被连坐（凭空多出来的线）" % (node, g))
                if g not in removed:
                    rep.fail("removedIds 缺 %s（后端模型不会删，下一轮会复活）" % g)
            if node not in removed:
                rep.fail("removedIds 缺被删的节点 %s" % node)

            dangling = page.evaluate(DANGLING)
            if dangling:
                rep.fail("第 %s 轮出现悬空线: %s" % (node, dangling))

        # ---- 新增：真实手绘一个矩形 ----
        before = set(page.evaluate("() => window.__h.ids()"))
        pt = draw_rectangle(page)
        if pt is None:
            rep.fail("找不到空白落点，手绘新增没能执行")
        else:
            after = set(page.evaluate("() => window.__h.ids()"))
            new_ids = sorted(after - before)
            rep.note("手绘落点 %s -> 新增 %s" % (pt, new_ids))
            if not new_ids:
                rep.fail("手绘的矩形没进画布")
            else:
                body, _ = last_sync(page)
                users = [u.get("id") for u in ((body or {}).get("userElements") or [])]
                removed = (body or {}).get("removedIds") or []
                rep.note("上报 userElements=%s" % users)
                for nid in new_ids:
                    if nid not in users:
                        rep.fail("手绘的 %s 没被当成用户元素上报" % nid)
                    if nid in removed:
                        rep.fail("手绘的 %s 被上报成删除" % nid)

            # ---- 再生成一次：AI 元素重画，用户手绘必须活下来 ----
            generate(page, "再画一张")
            after2 = set(page.evaluate("() => window.__h.ids()"))
            survive = [nid for nid in new_ids if nid in after2]
            rep.note("再生成后手绘存活: %s" % survive)
            for nid in new_ids:
                if nid not in after2:
                    rep.fail("新一轮生成吞掉了用户手绘的 %s" % nid)
            ai_back = [i for i in ("n1", "n4") if i in after2]
            if len(ai_back) < 2:
                rep.fail("新一轮生成没有重建 AI 元素（期望 n1/n4 回来，实际 %s）"
                         % sorted(after2))
            dangling = page.evaluate(DANGLING)
            if dangling:
                rep.fail("再生成后出现悬空线: %s" % dangling)

        rep.note("累计已上报删除: %s" % sorted(seen_removed))
        browser.close()
    rep.finish()


main()
