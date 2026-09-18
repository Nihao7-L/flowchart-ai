# -*- coding: utf-8 -*-
"""给 compare.html 截图，便于在没有浏览器窗口时核对渲染结果。"""

import os
import sys

os.environ.setdefault(
    "PLAYWRIGHT_BROWSERS_PATH",
    "F:/ProgramData/IDEA/flowchart/.playwright/browsers")
sys.path.insert(0, "F:/ProgramData/IDEA/flowchart/.playwright/pkg")

from playwright.sync_api import sync_playwright  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, "compare.html")
SHOT = os.path.join(HERE, "compare.png")

with sync_playwright() as play:
    browser = play.chromium.launch(
        proxy={"server": "direct://"})
    page = browser.new_page(viewport={"width": 1800, "height": 700})
    page.goto("file:///" + TARGET.replace("\\", "/"))
    page.wait_for_timeout(1200)
    page.screenshot(path=SHOT, full_page=False)
    browser.close()
print("shot:", SHOT)
