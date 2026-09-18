# -*- coding: utf-8 -*-
"""文字宽度标定探针：量 Excalidraw 实际渲染的文字宽度。

为什么需要它：`SceneLayout` 用「每字符固定像素宽」的启发式估算标签宽度，
据此决定层间距（`mainGapFor`）。估算值一旦小于浏览器实测值，"边上有文字
就看不到线"的老毛病就会在长标签上复发（2026-09-18 实测 4 个汉字标签
估算 64px、实测 80px，导致间距 100 < 需要 80+24）。

本探针把这件事从"猜"变成"量"：注入已知字符数与文种的标签，
读回 Excalidraw 产出的 text 元素宽度，算出每字符宽度。

前置：5173 dev server 在跑（后端不需要 —— 走打桩的 /api/chat）。
跑法：
    PYTHONPATH=F:/ProgramData/IDEA/flowchart/.playwright/pkg \
    PLAYWRIGHT_BROWSERS_PATH=F:/ProgramData/IDEA/flowchart/.playwright/browsers \
    python probe_text_metrics.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Report, generate, open_page, scene_of  # noqa: E402

CJK = "库存数据同步异步载荷"


def main():
    rep = Report("Excalidraw 文字宽度标定（供 SceneLayout 估算常数校准）")
    labels = []
    for n in (1, 2, 4, 6, 8):
        labels.append(CJK[:n])
    for n in (3, 5, 10, 15, 20):
        labels.append("x" * n)

    nodes = [("n1", "rectangle", 0, 0, 160, 60, "A"),
             ("n2", "rectangle", 0, 300, 160, 60, "B")]
    arrows = [("a%d" % i, "n1", "n2", lb) for i, lb in enumerate(labels)]
    scene = scene_of(nodes, arrows)

    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        browser, page = open_page(pw, scene)
        try:
            generate(page)
            texts = page.evaluate(
                "() => window.__h.texts().filter(t => t.containerId)")
            rep.note("量到 %d 个绑定标签" % len(texts))
            # 注意：Excalidraw 会重生成 text 元素 id，不能靠 id 回查原文 ——
            # 只能按 text 内容自己数文种（这正是 constraint.md 记的坑）。
            cjk_px, latin_px = [], []
            for t in texts:
                src = t["text"]
                if not src:
                    continue
                n_cjk = sum(1 for c in src if ord(c) >= 0x2E80)
                n_lat = len(src) - n_cjk
                per = t["w"] / float(len(src))
                (cjk_px if n_cjk >= n_lat else latin_px).append((src, t["w"], per))
                rep.note("  %-20s 字符数 %2d  实测宽 %5.0f  每字 %5.2f"
                         % (src, len(src), t["w"], per))
            if cjk_px:
                rep.note("中文每字宽度区间：%.2f ~ %.2f（均值 %.2f）"
                         % (min(p for _, _, p in cjk_px),
                            max(p for _, _, p in cjk_px),
                            sum(p for _, _, p in cjk_px) / len(cjk_px)))
            if latin_px:
                rep.note("拉丁每字宽度区间：%.2f ~ %.2f（均值 %.2f）"
                         % (min(p for _, _, p in latin_px),
                            max(p for _, _, p in latin_px),
                            sum(p for _, _, p in latin_px) / len(latin_px)))
        finally:
            browser.close()
    rep.finish()


if __name__ == "__main__":
    main()
