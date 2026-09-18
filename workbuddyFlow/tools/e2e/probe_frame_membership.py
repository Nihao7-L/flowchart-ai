# -*- coding: utf-8 -*-
"""Probe: how frame <-> content ownership actually flows through the browser.

The backend computes `frame.children` (ScenePlanner) and the schema calls it
"the only way to express frame <-> content ownership". This probe runs two
server-shaped scenes through the real frontend chain and contrasts them:

  A. frame WITH `children`     -> do members get `frameId`? does drag carry them?
  B. frame WITHOUT `children`  -> does anything still bind?

Findings this probe is designed to settle (2026-09-18):
  * `children` is NOT kept on the rendered element - Excalidraw strips it.
  * Excalidraw keeps ownership at runtime by scanning `frameId`, so a missing
    `children` key is harmless AFTER conversion.
  * The question is whether `children` is the load-bearing INPUT that causes
    `convertToExcalidrawElements` to back-fill `frameId` in the first place.

Run:  python probe_frame_membership.py
Exit code 1 on failure.

ASCII only on purpose: this file must survive editors that mangle CJK.
"""
import os
import sys

from playwright.sync_api import sync_playwright

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import harness  # noqa: E402

NL = chr(10)

MEMBERS = ["m1", "m2", "m3"]


def scene_with(with_children):
    """One frame with three declared members + one shape that is NOT a member."""
    nodes = [
        ("m1", "rectangle", 300, 300, 160, 60, "member one"),
        ("m2", "rectangle", 300, 420, 160, 60, "member two"),
        ("m3", "diamond", 300, 540, 240, 240, "member" + NL + "three"),
        ("free1", "rectangle", 900, 300, 160, 60, "not a member"),
    ]
    frame = {"id": "f1", "type": "frame", "x": 220, "y": 220, "width": 460,
             "height": 620, "name": "module A"}
    if with_children:
        frame["children"] = list(MEMBERS)
    return harness.scene_of(nodes, [], extras=[frame])


PROBE_JS = """
window.__fm = {
  frame: function (id) {
    return window.__chartflow.getSceneElements().find(function (e) { return e.id === id; });
  },
  frameIdOf: function (ids) {
    var out = {};
    var els = window.__chartflow.getSceneElements();
    for (var i = 0; i < ids.length; i++) {
      var e = els.find(function (x) { return x.id === ids[i]; });
      out[ids[i]] = e ? (e.frameId || null) : 'MISSING';
    }
    return out;
  },
  geoOf: function (ids) {
    var out = {};
    var els = window.__chartflow.getSceneElements();
    for (var i = 0; i < ids.length; i++) {
      var e = els.find(function (x) { return x.id === ids[i]; });
      out[ids[i]] = e ? { x: e.x, y: e.y } : null;
    }
    return out;
  }
};
"""


def run_case(pw, with_children, rep):
    label = "A. 契约给了 children" if with_children else "B. 契约没给 children"
    rep.note("--- %s ---" % label)
    browser, page = harness.open_page(pw, scene_with(with_children))
    try:
        harness.generate(page, "frame membership probe")
        page.evaluate(PROBE_JS)

        fr = page.evaluate("() => window.__fm.frame('f1')")
        if fr is None:
            rep.fail("%s：frame f1 未渲染" % label)
            return
        children = fr.get("children")
        rep.note("  渲染元素上的 children：%s" % (children if children else "（被转换器剥掉）"))

        fids = page.evaluate("(ids) => window.__fm.frameIdOf(ids)",
                             MEMBERS + ["free1"])
        rep.note("  成员 frameId 反填：%s" % fids)

        before = page.evaluate("(ids) => window.__fm.geoOf(ids)",
                               ["f1"] + MEMBERS + ["free1"])
        # grab the frame TITLE bar: Excalidraw draws the frame name ABOVE the
        # frame rect, and that label is the only draggable handle of a frame.
        harness.drag(page, fr["x"] + 40, fr["y"] - 8, 200, 160)
        after = page.evaluate("(ids) => window.__fm.geoOf(ids)",
                              ["f1"] + MEMBERS + ["free1"])

        dx = after["f1"]["x"] - before["f1"]["x"]
        dy = after["f1"]["y"] - before["f1"]["y"]
        rep.note("  拖框位移 dx=%s dy=%s" % (dx, dy))
        if abs(dx) < 1 and abs(dy) < 1:
            rep.fail("%s：框没被拖动（抓点落空）" % label)
            return

        moved = 0
        for mid in MEMBERS:
            mdx = after[mid]["x"] - before[mid]["x"]
            mdy = after[mid]["y"] - before[mid]["y"]
            rep.note("    成员 %s 位移 dx=%s dy=%s" % (mid, mdx, mdy))
            if abs(mdx - dx) <= 0.5 and abs(mdy - dy) <= 0.5:
                moved += 1
        fx = after["free1"]["x"] - before["free1"]["x"]
        fy = after["free1"]["y"] - before["free1"]["y"]
        rep.note("    非成员 free1 位移 dx=%s dy=%s" % (fx, fy))

        # A must carry all members; B is expected to carry none (that is the
        # point of the contrast). Report both, assert only the invariants that
        # hold in both cases: a non-member is never dragged along.
        if abs(fx) > 0.5 or abs(fy) > 0.5:
            rep.fail("%s：非成员 free1 被误带走（dx=%s dy=%s）" % (label, fx, fy))
        if with_children:
            if moved != len(MEMBERS):
                rep.fail("%s：只有 %d/%d 个成员跟随框"
                         % (label, moved, len(MEMBERS)))
            for mid in MEMBERS:
                if fids.get(mid) != "f1":
                    rep.fail("%s：成员 %s 的 frameId 应为 f1，实际 %s"
                             % (label, mid, fids.get(mid)))
        return {"children_on_element": isinstance(children, list),
                "frame_ids": fids, "moved": moved, "dx": dx, "dy": dy}
    finally:
        browser.close()


def main():
    rep = harness.Report("frame membership: children -> frameId -> drag carries members")
    with sync_playwright() as pw:
        a = run_case(pw, True, rep)
        b = run_case(pw, False, rep)

    rep.note("--- 结论 ---")
    if a and b:
        rep.note("children 的载荷作用 = %s（给了=%d/3 跟随，没给=%d/3 跟随）"
                 % ("决定性的" if a["moved"] > b["moved"] else "可有可无",
                    a["moved"], b["moved"]))
        rep.note("渲染元素是否保留 children = %s（两个用例都是 False 即为转换器剥离）"
                 % (a["children_on_element"] or b["children_on_element"]))
    rep.finish()


if __name__ == "__main__":
    main()
