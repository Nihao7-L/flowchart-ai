# -*- coding: utf-8 -*-
"""Probe: the frame-ownership acceptance criterion, deterministically.

v2-39 acceptance #9 says "shapes that escaped their OWN module frame = 0".
The prompt battery measures this end to end, but the LLM's output for one
prompt varies wildly (2026-09-18: the same 1144-char prompt produced
32 shapes + 3 frames once, then 16 shapes + 0 frames) - so the battery alone
cannot gate it. This probe pins the semantics instead:

  * it builds the exact shape the backend produces for case 2
    (4 modules, only 3 of them framed -> one module legitimately has no frame)
  * it runs the browser through the real adapter + Excalidraw
  * it evaluates the SHIPPED metric JS (imported from e2e_prompt_battery.HELPERS,
    so this can never drift from what the battery reports)
  * it asserts: escaped == 0, and the module without a frame is reported as
    "noFrameModule" rather than smeared into the escape count

Run:  python probe_frame_audit.py
Exit code 1 on failure.

ASCII only on purpose: this file must survive editors that mangle CJK.
"""
import os
import sys

from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import harness  # noqa: E402
import e2e_prompt_battery as battery  # noqa: E402  (reuse the shipped metric JS)

NL = chr(10)

# (id, x, y, w, h, label, group)
NODES = [
    # module 1 - the top-level flow. group says so, but the LLM emitted NO frame
    # for it. This is the production pattern that produced "6 shapes outside".
    ("main1", 300, 200, 160, 60, "step one", "main flow"),
    ("main2", 300, 360, 160, 60, "step two", "main flow"),
    ("main3", 300, 520, 160, 60, "step three", "main flow"),
    # module 2 - framed
    ("a1", 900, 200, 160, 60, "a one", "mod A"),
    ("a2", 900, 360, 160, 60, "a two", "mod A"),
    # module 3 - framed
    ("b1", 1300, 200, 160, 60, "b one", "mod B"),
    ("b2", 1300, 360, 160, 60, "b two", "mod B"),
    # no group at all - must NOT be counted as escaping anything
    ("solo", 1700, 200, 160, 60, "standalone", None),
]

# (id, x, y, w, h, name, children) - names match "mod A" / "mod B"; no "main flow"
FRAMES = [
    ("fA", 840, 140, 300, 360, "mod A", ["a1", "a2"]),
    ("fB", 1240, 140, 300, 360, "mod B", ["b1", "b2"]),
]


def contract_scene():
    """Server-shaped contract: shapes carry `group`, frames carry `name`."""
    els = []
    for (i, x, y, w, h, label, group) in NODES:
        e = {"id": i, "type": "rectangle", "x": x, "y": y, "width": w,
             "height": h, "label": {"text": label}}
        if group:
            e["group"] = group
        els.append(e)
    for (i, x, y, w, h, name, children) in FRAMES:
        els.append({"id": i, "type": "frame", "x": x, "y": y, "width": w,
                    "height": h, "name": name, "children": children})
    return {"elements": els}


def main():
    rep = harness.Report("frame audit: escaped-own-frame must be 0")
    with sync_playwright() as pw:
        browser, page = harness.open_page(pw, contract_scene())
        try:
            page.evaluate(battery.HELPERS)
            harness.generate(page, "frame audit probe")

            m = page.evaluate("() => window.__r.metrics()")
            frames = page.evaluate("() => window.__r.scene().filter(function (e) {"
                                   " return e.type === 'frame'; })")
            rep.note("framed: %d | frame names %s"
                     % (m["frames"], [f.get("name") for f in frames]))
            rep.note("tap captured contract: %s (sse frames = %s)"
                     % (m.get("contractSeen"),
                        page.evaluate("() => window.__sseFrames || 0")))
            rep.note("shapes inside a frame        = %d"
                     % (len(m["boxes"]) - m["shapesOutsideFrames"]))
            rep.note("shapes outside every frame   = %d (raw metric)"
                     % m["shapesOutsideFrames"])
            rep.note("  - no group at all          = %d  %s"
                     % (m["shapesOutsideNoGroup"], "allowed"))
            rep.note("  - module has NO frame       = %d  %s"
                     % (m["shapesOutsideNoFrameModule"],
                        m["shapesOutsideNoFrameModuleIds"]))
            rep.note("  - escaped its own frame     = %d  <== must be 0"
                     % m["shapesEscapingOwnFrame"])

            if not m.get("contractSeen"):
                rep.fail("tap 未捕获契约，精确口径无法判定")
            if m["shapesEscapingOwnFrame"] != 0:
                rep.fail("有 %d 个图形跑出所属框：%s"
                         % (m["shapesEscapingOwnFrame"],
                            m["shapesEscapingOwnFrameDetail"]))
            # the three ungrouped-by-frame module members are the production pattern
            if m["shapesOutsideNoFrameModule"] != 3:
                rep.fail("模块无框应报 3 个（main1..3），实际 %d（%s）"
                         % (m["shapesOutsideNoFrameModule"],
                            m["shapesOutsideNoFrameModuleIds"]))
            if m["shapesOutsideNoGroup"] != 1:
                rep.fail("无 group 应报 1 个（solo），实际 %d" % m["shapesOutsideNoGroup"])
        finally:
            browser.close()
    rep.finish()


if __name__ == "__main__":
    main()
