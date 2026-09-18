# -*- coding: utf-8 -*-
"""折线箭头（elbowed）能力探针：验证 Excalidraw 能不能把「绑定箭头」画成折线。

背景（2026-09-18 判断"布局乱"的根因时，两条结论互相矛盾）：
  (a) 上一轮的说法：绑定箭头只存 start/end，几何由两端现算 → 渲染必然是直线；
  (b) 类型定义的证据：`ExcalidrawElbowArrowElement` 带 `startBinding`/`endBinding`，
      路由函数 `updateElbowArrowPoints(arrow, elementsMap, ...)` 收 elementsMap
      （能看到其它元素 → 才可能避让），另有 `BASE_PADDING = 40`，bundle 里还有
      `arrowtype_elbowed:"Elbow arrow"`。到底哪一种，只有实测能定。

================ 实测结论（2026-09-18，Excalidraw 0.18.1 = npm latest）================
 | 路径 | 做法                                  | 段数 | 结果 |
 |------|---------------------------------------|------|------|
 |  ①   | updateScene 注入 `elbowed: true`      |  1   | ❌ 不生效 |
 |  ②   | UI 切 arrow type（arrowtypes 组 3 个选项全试）| 1 | ❌ 里面没有折线选项 |
 |  ③   | 绑定箭头 + 3 段 points                |  1   | ❌ 被两端几何压回直线 |
 |  ④   | **未绑定箭头 + 3 段 points**          |  **3** | ✅ 折线保住，正常渲染 |
 |  ⑤   | 绑定箭头是否绕开挡路节点（n5）        |  1   | ❌ 斜穿障碍，不避让 |

→ **「跟随」（绑定）与「折线」在当前 Excalidraw 下互斥**：绑定则直线，折线则不绑定。
   折线能力（类型 / 路由函数 / i18n 文案）确实都在代码里，但 0.18.1 未开放入口；
   而 0.18.1 就是 npm 上的 latest，升级也无解。
→ 想要"折线 + 绕行"，只能**后端自己算路径、写进 arrow.points、且不绑定**
   （契约 arrow 本就有 `points` 字段、适配层白名单也含 `points`，今天就能用）；
   代价是拖节点时箭头不再跟随。

踩过的判据坑（v2 → v4，别重蹈）：
  · 对照组必须**横向跨障碍**。纯垂直的边（同 x）正交路由后本来就退化成直线，
    "段数 1" 无法区分"没生效"与"生效了但恰好是直线"。
  · 定位 UI 控件不能按 title / aria-label 搜 —— 属性面板的选项组是
    `input[type=radio][name=...]`，且**同组各选项 value 全是 "on"**，
    只能按**序号**点（面板组名：stroke-width / strokeStyle / sloppiness /
    arrowtypes / font-size / editor-current-shape）。

本探针**不改产品代码**（契约 schema 与适配层白名单都还没放行 elbowed）。
前置：5173 dev server 在跑（后端不需要，走打桩 /api/chat）。
跑法：
    PYTHONPATH=F:/ProgramData/IDEA/flowchart/.playwright/pkg \
    PLAYWRIGHT_BROWSERS_PATH=F:/ProgramData/IDEA/flowchart/.playwright/browsers \
    python probe_elbow_arrow.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Report, generate, open_page, scene_of  # noqa: E402

SHOT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "..", "layout-bench", "elbow-probe.png")

# n5 刻意压在 n1→n9 的直线路径上：折线若只做正交化不避让，会穿过 n5。
NODES = [
    ("n1", "rectangle", 0, 0, 160, 60, "下单"),
    ("n2", "rectangle", 0, 420, 160, 60, "发货"),
    ("n9", "rectangle", 520, 380, 200, 120, "库存服务"),
    ("n5", "rectangle", 260, 140, 200, 100, "挡路节点"),
]
ARROWS = [
    ("a1", "n1", "n2", "垂直对照"),
    ("a4", "n1", "n9", "横向跨障碍"),
    ("a5", "n9", "n2", "回边"),
]

# 后端直出折线路径（契约 arrow 本就有 points，适配层白名单也含 points）
EXTRAS = [
    {"id": "z1", "type": "arrow", "x": 900, "y": 0,
     "points": [[0, 0], [0, 80], [160, 80], [160, 160]],
     "label": {"text": "折线未绑定"}},
]

FIND_ARROW_TYPE = """
() => {
  const rs = Array.from(document.querySelectorAll('input[type=radio]'));
  const groups = {};
  for (const r of rs) {
    const k = r.name || '(no-name)';
    (groups[k] = groups[k] || []).push((r.value || r.id || '?') + (r.checked ? '*' : ''));
  }
  return {
    radios: rs.length,
    groups: groups
  };
}
"""

CLICK_ARROW_TYPE = """
(idx) => {
  const rs = Array.from(document.querySelectorAll('input[name=arrowtypes]'));
  if (!rs[idx]) return 'no-index-' + rs.length;
  rs[idx].click();
  const checked = rs.findIndex((r) => r.checked);
  return 'clicked-' + idx + ' checked=' + checked;
}
"""


def seg_count(arrow):
    """折线段数 = 点数 - 1（2 点 = 1 段直线）。"""
    return max(0, (arrow.get("np") or 0) - 1)


def hits_rect(arrow, rect):
    """折线是否穿过矩形（沿首末点连线采样，粗判足够定性）。"""
    p0, p1 = arrow.get("p0"), arrow.get("p1")
    if not p0 or not p1:
        return False
    for i in range(21):
        r = i / 20.0
        x = p0[0] + (p1[0] - p0[0]) * r
        y = p0[1] + (p1[1] - p0[1]) * r
        if rect["x"] <= x <= rect["x"] + rect["w"] \
                and rect["y"] <= y <= rect["y"] + rect["h"]:
            return True
    return False


def select(page, arrow_id):
    mid = page.evaluate("(id) => window.__h.arrowMid(id)", arrow_id)
    pt = page.evaluate("(a) => window.__h.toPage(a[0], a[1])",
                       [mid["x"], mid["y"]])
    page.mouse.click(pt["x"], pt["y"])
    page.wait_for_timeout(700)


def main():
    rep = Report("Excalidraw 折线箭头探针 v3（绑定 → 折线 → 避让）")
    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        browser, page = open_page(pw, scene_of(NODES, ARROWS, EXTRAS))
        try:
            generate(page)
            before = page.evaluate("() => window.__h.arrows()")
            rep.note("初始：%d 条箭头" % len(before))
            for a in before:
                rep.note("    %-3s 绑定 %s→%s  段数 %d  np=%s"
                         % (a["id"], a["sb"], a["eb"], seg_count(a), a["np"]))

            # ---- 选中横向跨障碍的 a4，看 Arrow type 面板 ----
            select(page, "a4")
            info = page.evaluate(FIND_ARROW_TYPE)
            rep.note("面板里的 radio 组（* = 当前值）：")
            for k, vs in (info.get("groups") or {}).items():
                rep.note("    %-26s %s" % (k, " ".join(vs)))

            n_arrow = len((info.get("groups") or {}).get("arrowtypes", []))
            rep.note("arrowtypes 组共 %d 个选项，逐个切换：" % n_arrow)
            for idx in range(n_arrow):
                res = page.evaluate(CLICK_ARROW_TYPE, idx)
                page.wait_for_timeout(900)
                cur = {a["id"]: a
                       for a in page.evaluate("() => window.__h.arrows()")}
                a4 = cur.get("a4")
                rep.note("  idx=%d（%s）→ a4 段数 %d  bbox %.0fx%.0f"
                         % (idx, res, seg_count(a4) if a4 else -1,
                            (a4 or {}).get("w") or 0, (a4 or {}).get("h") or 0))

            # ---- 全部边（含折线）是否穿过障碍 ----
            box = page.evaluate("(id) => window.__h.rect(id)", "n5")
            final = page.evaluate("() => window.__h.arrows()")
            blocked = [a["id"] for a in final if hits_rect(a, box)]
            rep.note("穿过障碍 n5 的箭头：%s" % (blocked or "无"))
            rep.note("最终各边段数：%s"
                     % {a["id"]: seg_count(a) for a in final})

            # 截图前重新选中 a4，让 Arrow type 面板出现在图里
            page.evaluate("() => window.__h.fit()")
            page.wait_for_timeout(500)
            if info.get("radios"):
                select(page, "a4")
            page.wait_for_timeout(400)
            out = os.path.abspath(SHOT)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            page.screenshot(path=out)
            rep.note("截图：%s" % out)
        finally:
            browser.close()
    rep.finish()


if __name__ == "__main__":
    main()
