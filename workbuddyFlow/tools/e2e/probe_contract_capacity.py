"""Probe: which Excalidraw skeleton fields does the official converter accept?

Question this answers
---------------------
scene.schema.json exposes 6 usable element types and a very thin set of
properties. The frontend whitelist (sceneAdapter.SKELETON_KEYS) forwards only
those keys. So "the contract is too narrow" is true by construction -- but is
the RENDERER also narrow? i.e. if we widen the contract, will anything show up?

Method
------
Open the Vite dev server, dynamically import @excalidraw/excalidraw (Vite's
/@id/ prefix), call convertToExcalidrawElements with skeletons that use the
fields our contract currently forbids, then:
  1. report which fields survived conversion,
  2. push the converted elements onto the real canvas and screenshot.

No LLM call, no backend call. Pure renderer capability probe.

Run:  python probe_contract_capacity.py
"""

import json
import os
import sys

from playwright.sync_api import sync_playwright

DEV_URL = "http://127.0.0.1:5173/"
SHOTS = "F:/ProgramData/IDEA/flowchart/.playwright/shots"
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

# Fields the contract does NOT expose today, exercised deliberately here.
SKELETONS = [
    {
        "type": "rectangle", "id": "r1", "x": 0, "y": 0, "width": 220, "height": 100,
        "roundness": {"type": 3},
        "strokeStyle": "dashed",
        "groupIds": ["grp-core"],
        "link": "https://example.com",
        "label": {"text": "rounded + dashed + grouped"},
    },
    {
        "type": "rectangle", "id": "r2", "x": 0, "y": 220, "width": 220, "height": 100,
        "groupIds": ["grp-core"],
        "backgroundColor": "#ffec99", "fillStyle": "zigzag",
        "label": {"text": "zigzag fill"},
    },
    {
        "type": "arrow", "id": "a1", "x": 220, "y": 50,
        "points": [[0, 0], [160, 0], [160, 220]],
        "startArrowhead": "dot", "endArrowhead": "crowfoot_many",
        "strokeStyle": "dotted",
    },
    {
        "type": "arrow", "id": "a2", "x": 260, "y": 50,
        "points": [[0, 0], [80, 0]],
        "startArrowhead": "bar", "endArrowhead": "triangle_outline",
    },
    {
        "type": "text", "id": "t1", "x": 480, "y": 0,
        "text": "multi\nline\ntext",
        "fontSize": 20, "verticalAlign": "middle", "autoResize": False,
    },
    {
        "type": "line", "id": "l1", "x": 480, "y": 140,
        "points": [[0, 0], [120, 0], [120, 100], [240, 100]],
        "strokeStyle": "dashed",
    },
    {
        "type": "frame", "id": "f1", "x": -60, "y": -60, "width": 380, "height": 440,
        "name": "module A", "children": ["r1", "r2"],
    },
]

PROBE_JS = """
async (skeletons) => {
  const candidates = [
    '/@id/@excalidraw/excalidraw',
    '/node_modules/.vite/deps/@excalidraw_excalidraw.js',
    '/node_modules/@excalidraw/excalidraw/dist/prod/index.js',
  ];
  let mod = null, used = null, lastErr = null;
  for (const path of candidates) {
    try { mod = await import(path); used = path; break; }
    catch (e) { lastErr = String(e); }
  }
  if (!mod) return { ok: false, error: 'import failed: ' + lastErr };

  const fn = mod.convertToExcalidrawElements;
  if (typeof fn !== 'function') {
    return { ok: false, error: 'convertToExcalidrawElements missing', keys: Object.keys(mod).slice(0, 30) };
  }

  // A: default options (ids get regenerated per constraint.md)
  let out = null, err = null;
  try { out = fn(skeletons); } catch (e) { err = String(e); }
  if (err) return { ok: false, used, error: 'convert threw: ' + err };

  // B: regenerateIds:false -- can we keep our own ids?
  let keepIds = null;
  try {
    const kept = fn(skeletons, { regenerateIds: false });
    keepIds = kept.map((e) => e.id);
  } catch (e) { keepIds = 'threw: ' + String(e); }

  const FIELDS = ['roundness', 'strokeStyle', 'groupIds', 'link', 'frameId',
                  'startArrowhead', 'endArrowhead', 'verticalAlign', 'autoResize',
                  'fillStyle', 'opacity'];
  const report = out.map((e) => {
    const r = { type: e.type, id: e.id, hasChildren: 'children' in e,
                childCount: Array.isArray(e.children) ? e.children.length : null };
    for (const f of FIELDS) {
      const v = e[f];
      if (v !== undefined) r[f] = (v && typeof v === 'object') ? JSON.stringify(v) : v;
    }
    return r;
  });

  return { ok: true, used, count: out.length, report, keepIds,
           elements: out.slice(0, 400) };
}
"""


def main() -> int:
    os.makedirs(SHOTS, exist_ok=True)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            headless=True,
            proxy={"server": "direct://"},
            args=["--no-proxy-server", "--proxy-bypass-list=*"],
        )
        page = browser.new_page(viewport={"width": 1500, "height": 950})
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(DEV_URL, wait_until="load")
        page.wait_for_function("() => !!window.__chartflow", timeout=60000)

        res = page.evaluate(PROBE_JS, SKELETONS)

        print("=" * 78)
        if not res.get("ok"):
            print("PROBE FAILED:", res.get("error"))
            browser.close()
            return 1
        print("import path :", res["used"])
        print("converted   :", res["count"], "elements")
        print("ids w/o regen:", res["keepIds"])
        print("=" * 78)
        for r in res["report"]:
            print(json.dumps(r, ensure_ascii=False))

        # Push converted elements onto the real canvas and shoot.
        els = res["elements"]
        page.evaluate(
            "(els) => window.__chartflow.updateScene({elements: els})", els
        )
        page.wait_for_timeout(1500)
        page.evaluate(
            "() => window.__chartflow.scrollToContent("
            "window.__chartflow.getSceneElements(), {fitToContent: true, animate: false})"
        )
        page.wait_for_timeout(900)
        shot = os.path.join(SHOTS, "probe-contract-capacity.png")
        page.screenshot(path=shot)
        print("=" * 78)
        print("screenshot  :", shot)
        print("page errors :", errors[:5] if errors else "none")

        with open(os.path.join(OUT_DIR, "probe-contract-capacity.json"), "w",
                  encoding="utf-8") as fh:
            json.dump(res, fh, ensure_ascii=False, indent=2)
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
