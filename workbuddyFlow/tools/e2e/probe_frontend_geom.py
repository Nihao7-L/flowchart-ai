# -*- coding: utf-8 -*-
"""Probe: does the BROWSER keep the server's geometry?

Renders a server-shaped scene through the real frontend chain (fake /api/chat ->
sceneAdapter -> convertToExcalidrawElements -> Excalidraw) and compares every
element's rendered geometry with the contract. Anything that moves or resizes is
a place where the layout's promises have been silently overridden.

Run:  python probe_frontend_geom.py [scene.json]
      (default: the built-in 7-node / 4-frame scene)

Checks (exit code 1 on any failure):
  1. every shape keeps x / y / width / height   (layout owns geometry)
  2. frames keep x / y / width / height AND stay on the 20px grid
     (Excalidraw treats y = 0 as "not provided": transform.ts `frame?.y || minY`)
  3. no two shapes overlap in the rendered scene

ASCII only on purpose: this file must survive editors that mangle CJK.
"""
import json
import os
import sys

from playwright.sync_api import sync_playwright

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import harness  # noqa: E402

NL = chr(10)
GRID = 20.0


def builtin_scene():
    """Small server-shaped scene: frames, members, one frame at y = 0.

    Sizes here must be ones the LAYOUT would emit, i.e. big enough for the
    bound label (LayoutSpec.nodeSize): a diamond only offers round(w/2)-10 px
    of usable text width, so narrow diamonds with long labels legitimately grow
    in the browser. Keep this scene generously sized - the real check is
    running the probe against a scene the backend actually produced.
    """
    nodes = [
        ("r1", "rectangle", 240, 280, 160, 60, "create order"),
        ("r2", "diamond", 240, 420, 240, 240, "allow?"),
        ("r3", "diamond", 240, 700, 240, 240, "allow" + NL + "backorder?"),
        ("r4", "rectangle", 240, 980, 160, 60, "short"),
        ("r5", "rectangle", 240, 1080, 160, 60, "a much longer latin label here"),
    ]
    arrows = [("a1", "r1", "r2", "yes"), ("a2", "r2", "r3", None)]
    frames = [
        {"id": "f1", "type": "frame", "x": 200, "y": 200, "width": 320, "height": 420,
         "name": "module A", "children": ["r1", "r2"]},
        {"id": "f2", "type": "frame", "x": 640, "y": 0, "width": 320, "height": 1220,
         "name": "module B (y = 0)", "children": ["r3", "r4", "r5"]},
    ]
    return harness.scene_of(nodes, arrows, extras=frames)


def load_scene(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def overlaps(a, b):
    w = min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"])
    h = min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"])
    return w > 1 and h > 1


def main():
    scene = load_scene(sys.argv[1]) if len(sys.argv) > 1 else builtin_scene()
    contract = {}
    for el in scene["elements"]:
        if el.get("id"):
            contract[el["id"]] = el

    with sync_playwright() as pw:
        browser, page = harness.open_page(pw, scene)
        try:
            harness.generate(page)
            got = page.evaluate(
                """() => window.__chartflow.getSceneElements().map((e) => ({
                     id: e.id, type: e.type, x: e.x, y: e.y, w: e.width, h: e.height,
                     fr: e.frameId || null,
                   }))""")
        finally:
            browser.close()

    shapes = [e for e in got if e["type"] in ("rectangle", "ellipse", "diamond")]
    frames = [e for e in got if e["type"] == "frame"]

    fails = []
    print("== geometry drift (contract vs rendered) ==")
    moved = 0
    for el in got:
        src = contract.get(el["id"])
        if not src:
            continue
        if el["type"] not in ("rectangle", "ellipse", "diamond", "frame"):
            continue
        dx = el["x"] - src.get("x", el["x"])
        dy = el["y"] - src.get("y", el["y"])
        dw = el["w"] - src.get("width", el["w"])
        dh = el["h"] - src.get("height", el["h"])
        if dx or dy or dw or dh:
            moved += 1
            print("   DRIFT %-16s %-9s d=(%s,%s) size=(%s,%s) -> (%s,%s)"
                  % (el["id"], el["type"], dx, dy, dw, dh, el["w"], el["h"]))
    if not moved:
        print("   none - browser kept every coordinate and size")

    print("== frames on the 20px grid ==")
    on_grid = 0
    for f in frames:
        ok = (f["x"] % GRID == 0) and (f["y"] % GRID == 0)
        on_grid += 1 if ok else 0
        print("   %-10s x=%-7s y=%-7s %sx%s  %s"
              % (f["id"], f["x"], f["y"], f["w"], f["h"],
                 "on grid" if ok else "OFF GRID"))
    if frames and on_grid != len(frames):
        fails.append("frame 离网：%d/%d 落在 20px 网格上" % (on_grid, len(frames)))

    print("== shape overlaps (rendered) ==")
    bad = []
    for i in range(len(shapes)):
        for j in range(i + 1, len(shapes)):
            if overlaps(
                {"x": shapes[i]["x"], "y": shapes[i]["y"], "w": shapes[i]["w"], "h": shapes[i]["h"]},
                {"x": shapes[j]["x"], "y": shapes[j]["y"], "w": shapes[j]["w"], "h": shapes[j]["h"]},
            ):
                bad.append((shapes[i]["id"], shapes[j]["id"]))
    print("   %d overlapping pairs%s" % (len(bad), (" " + str(bad[:4])) if bad else ""))
    if bad:
        fails.append("图形重叠 %d 对" % len(bad))

    if moved:
        fails.append("几何被渲染端改写 %d 处" % moved)

    print("")
    if fails:
        print("PROBE FAILED: " + "; ".join(fails))
        return 1
    print("PROBE PASSED (%d shapes, %d frames)" % (len(shapes), len(frames)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
