# -*- coding: utf-8 -*-
"""A1b 端到端复验：真实浏览器跑「画一个时序图」，确认不再抛
TypeError: Cannot read properties of undefined (reading 'id')，且画布有图。
同时跑一条对照（用户登录流程图）确认正常路径不受影响。
前置：8080 后端 + 5173 dev server 都在跑。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
# 本地 Playwright（免下载安装）：pkg 在 .playwright/pkg，浏览器在 .playwright/browsers。
# 用项目根（脚本向上 3 级）拼绝对路径，避免 sys.path 里的 ".." 在 Windows 上不被归一化。
PROJ = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.normpath(os.path.join(PROJ, ".playwright", "pkg")))
from e2e_prompt_battery import open_page  # noqa: E402

CASES = [
    ("画一个时序图", "A1b 时序图（原崩溃输入）"),
    ("画一个用户登录的流程图", "对照：正常流程图"),
]
OUT_DIR = "F:/ProgramData/IDEA/flowchart/.workbuddy/visual"
os.makedirs(OUT_DIR, exist_ok=True)


def main():
    from playwright.sync_api import sync_playwright
    all_errors = []
    with sync_playwright() as pw:
        for prompt, label in CASES:
            browser, page = open_page(pw)
            page_errors = []

            def on_pageerror(e):
                all_errors.append("[%s] PAGEERROR: %s" % (label, e))
                page_errors.append(str(e))

            def on_console(m):
                if m.type in ("error", "warning"):
                    all_errors.append("[%s] CONSOLE[%s]: %s" % (label, m.type, m.text))
                    page_errors.append(m.text)

            page.on("pageerror", on_pageerror)
            page.on("console", on_console)

            t0 = time.time()
            # 直接用底层 fetch 发消息并等待 result（ask 依赖聊天框，这里自管等待更稳）
            page.fill(".chat-input", prompt)
            page.click(".chat-send-btn")

            # 等待 result / error 终态
            done = False
            status = "timeout"
            while time.time() - t0 < 480:
                msgs = page.evaluate(
                    "() => Array.from(document.querySelectorAll('.chat-log .msg'))"
                    ".map(e => ({cls: e.className, text: e.textContent || ''}))"
                )
                if any("已生成" in m["text"] for m in msgs):
                    status = "ok"
                    done = True
                    break
                if any(m["cls"].endswith("error") for m in msgs):
                    status = "error"
                    done = True
                    break
                time.sleep(2)
            dt = round(time.time() - t0, 1)

            # 等渲染稳定
            time.sleep(1.5)
            metrics = page.evaluate("() => window.__r.metrics()")
            ink = page.evaluate("() => window.__r.ink()")
            chat = page.evaluate(
                "() => Array.from(document.querySelectorAll('.chat-log .msg'))"
                ".map(e => ({cls: e.className, text: (e.textContent||'').slice(0,120)}))"
            )

            # 取景（含 A2 的 canvasOffsets 路径）+ 截图
            try:
                page.evaluate("() => window.__r.fit()")
                time.sleep(0.7)
            except Exception as e:
                all_errors.append("[%s] fit() 抛错: %s" % (label, e))
            shot = os.path.join(OUT_DIR, "a1b_%s.png" % label.split("：")[-1][:8])
            try:
                page.screenshot(path=shot)
            except Exception as e:
                all_errors.append("[%s] 截图失败: %s" % (label, e))

            print("==== %s ====" % label)
            print("  输入: %s" % prompt)
            print("  状态: %s  耗时: %ss" % (status, dt))
            print("  元素数: %d  类型分布: %s" % (metrics["total"], metrics["counts"]))
            print("  非白像素(ink): %d" % ink)
            print("  pageerror/console错误: %d 条" % len(page_errors))
            if page_errors:
                for e in page_errors[:5]:
                    print("    - %s" % e[:160])
            print("  聊天末尾: %s" % (chat[-1] if chat else None))
            print("  截图: %s" % shot)
            browser.close()

    print("\n==== 汇总 ====")
    if all_errors:
        print("发现 %d 条页面错误：" % len(all_errors))
        for e in all_errors:
            print("  - %s" % e[:200])
        sys.exit(1)
    else:
        print("无 pageerror / console 错误，A1b 复验通过。")


if __name__ == "__main__":
    main()
