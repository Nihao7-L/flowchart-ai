# -*- coding: utf-8 -*-
"""把两个布局引擎的产出渲染成并排对比图（v2-38）。

用法：先跑 LayoutBench 导出 out/*.json，再执行本脚本生成 compare.html。
渲染尺寸与运行期一致：节点宽按最长标签估算（中文 20px/字、拉丁 10px/字），
高 60px —— 与 LayoutSpec 同源，否则画出来比实际窄。
"""

import html
import json
import os

BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "out")
TARGET = os.path.join(BASE, "compare.html")

SHAPES = ("rectangle", "ellipse", "diamond")

FILL = {"ellipse": "#d3f9d8", "diamond": "#fff3bf",
        "rectangle": "#f1f3f5"}
STROKE = {"ellipse": "#2f9e44", "diamond": "#e8590c",
          "rectangle": "#495057"}

NODE_HEIGHT = 60.0
MIN_NODE_WIDTH = 160.0
MAX_NODE_WIDTH = 360.0
PAD = 24.0


def label_width(text):
    total = 0
    for ch in text:
        total += 20.0 if ord(ch) >= 0x2E80 else 10.0
    return total


def node_width(labels):
    widest = MIN_NODE_WIDTH
    for text in labels:
        need = label_width(text) + PAD
        widest = max(widest, min(MAX_NODE_WIDTH, need))
    return widest


def label_text(element):
    raw = element.get("label")
    if isinstance(raw, dict):
        return raw.get("text") or ""
    if isinstance(raw, str):
        return raw
    return ""


def parse(path):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    boxes = {}
    order = []
    for element in data["elements"]:
        kind = element.get("type")
        if kind not in SHAPES:
            continue
        boxes[element["id"]] = {
            "id": element["id"], "type": kind,
            "x": element["x"], "y": element["y"],
            "label": label_text(element),
        }
        order.append(element["id"])
    arrows = []
    for element in data["elements"]:
        if element.get("type") != "arrow":
            continue
        src = (element.get("start") or {}).get("id")
        dst = (element.get("end") or {}).get("id")
        if src in boxes and dst in boxes:
            arrows.append({"a": src, "b": dst,
                           "label": label_text(element)})
    width = node_width([boxes[i]["label"] for i in order])
    return boxes, order, arrows, width


def overlaps(ax, ay, aw, ah, bx, by, bw, bh):
    w = min(ax + aw, bx + bw) - max(ax, bx)
    h = min(ay + ah, by + bh) - max(ay, by)
    return w > 1.0 and h > 1.0


def render(boxes, order, arrows, width, height):
    min_x = min(boxes[i]["x"] for i in order)
    min_y = min(boxes[i]["y"] for i in order)
    max_x = max(boxes[i]["x"] + width for i in order)
    max_y = max(boxes[i]["y"] + NODE_HEIGHT for i in order)
    view = "%s %s %s %s" % (min_x - 60, min_y - 60,
                            (max_x - min_x) + 120,
                            (max_y - min_y) + 120)
    parts = [
        '<svg viewBox="%s" preserveAspectRatio="xMidYMid meet"' % view,
        ' xmlns="http://www.w3.org/2000/svg" style="width:100%;height:100%">',
        '<defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5"',
        ' markerWidth="6" markerHeight="6" orient="auto-start-reverse">',
        '<path d="M1 1L9 5L1 9" fill="none" stroke="#495057"',
        ' stroke-width="1.6" stroke-linecap="round"/></marker></defs>',
    ]

    # 标签落位（边中点），用于标出压住节点的那几个
    placed = []
    for arrow in arrows:
        if not arrow["label"]:
            continue
        a = boxes[arrow["a"]]
        b = boxes[arrow["b"]]
        cx = (a["x"] + b["x"]) / 2 + width / 2
        cy = (a["y"] + b["y"]) / 2 + NODE_HEIGHT / 2
        w = label_width(arrow["label"])
        placed.append((cx, cy, w, 25.0, arrow["label"]))

    def hits_shape(entry):
        cx, cy, w, h = entry[0], entry[1], entry[2], entry[3]
        for i in order:
            box = boxes[i]
            if overlaps(cx - w / 2, cy - h / 2, w, h,
                        box["x"], box["y"], width, NODE_HEIGHT):
                return True
        return False

    def hits_label(index):
        cx, cy, w, h = placed[index][:4]
        for j, other in enumerate(placed):
            if j == index:
                continue
            if overlaps(cx - w / 2, cy - h / 2, w, h,
                        other[0] - other[2] / 2,
                        other[1] - other[3] / 2,
                        other[2], other[3]):
                return True
        return False

    for arrow in arrows:
        a = boxes[arrow["a"]]
        b = boxes[arrow["b"]]
        parts.append(
            '<line x1="%s" y1="%s" x2="%s" y2="%s" stroke="#adb5bd"'
            ' stroke-width="1.6" marker-end="url(#ah)"/>' % (
                a["x"] + width / 2, a["y"] + NODE_HEIGHT / 2,
                b["x"] + width / 2, b["y"] + NODE_HEIGHT / 2))

    for i in order:
        box = boxes[i]
        x, y = box["x"], box["y"]
        fill = FILL[box["type"]]
        stroke = STROKE[box["type"]]
        if box["type"] == "diamond":
            pts = "%s,%s %s,%s %s,%s %s,%s" % (
                x + width / 2, y,
                x + width, y + NODE_HEIGHT / 2,
                x + width / 2, y + NODE_HEIGHT,
                x, y + NODE_HEIGHT / 2)
            parts.append('<polygon points="%s" fill="%s" stroke="%s"'
                         ' stroke-width="1.5"/>' % (pts, fill, stroke))
        elif box["type"] == "ellipse":
            parts.append('<ellipse cx="%s" cy="%s" rx="%s" ry="%s"'
                         ' fill="%s" stroke="%s" stroke-width="1.5"/>'
                         % (x + width / 2, y + NODE_HEIGHT / 2,
                            width / 2, NODE_HEIGHT / 2, fill, stroke))
        else:
            parts.append('<rect x="%s" y="%s" width="%s" height="%s"'
                         ' rx="6" fill="%s" stroke="%s"'
                         ' stroke-width="1.5"/>'
                         % (x, y, width, NODE_HEIGHT, fill, stroke))
        parts.append(
            '<text x="%s" y="%s" text-anchor="middle"'
            ' dominant-baseline="central" font-family="sans-serif"'
            ' font-size="20" fill="#212529">%s</text>' % (
                x + width / 2, y + NODE_HEIGHT / 2,
                html.escape(box["label"])))

    for index, entry in enumerate(placed):
        cx, cy, w = entry[0], entry[1], entry[2]
        bad = hits_shape(entry) or hits_label(index)
        parts.append(
            '<text x="%s" y="%s" text-anchor="middle"'
            ' dominant-baseline="central" font-family="sans-serif"'
            ' font-size="20" fill="%s" font-weight="%s">%s</text>' % (
                cx, cy, "#e03131" if bad else "#c92a2a",
                "bold" if bad else "normal",
                html.escape(entry[4])))

    parts.append("</svg>")
    return "".join(parts), (max_x - min_x), (max_y - min_y)


def main():
    files = [
        ("SceneLayout", "SceneLayout-login-flow.json",
         "自研分层布局（当前默认）"),
        ("ElkPlain", "ElkLayoutEngine-plain-login-flow.json",
         "ELK 竖排单列（不折行）"),
        ("ElkWrapped", "ElkLayoutEngine-wrapped-login-flow.json",
         "ELK 折行多列（目标比例 1.3）"),
    ]
    panels = []
    for engine, name, title in files:
        path = os.path.join(OUT, name)
        if not os.path.exists(path):
            continue
        boxes, order, arrows, width = parse(path)
        svg, w, h = render(boxes, order, arrows, width, None)
        panels.append((engine, title, svg, w, h, width))

    cards = []
    for engine, title, svg, w, h, width in panels:
        cards.append(
            '<div style="flex:1;min-width:0;border:1px solid #dee2e6;'
            'border-radius:10px;padding:10px;background:#fff">'
            '<div style="font:500 14px sans-serif;margin-bottom:6px">%s</div>'
            '<div style="font:12px sans-serif;color:#868e96;'
            'margin-bottom:8px">包围盒 %d x %d px　节点宽 %d px'
            '　高/宽 %.2f</div>'
            '<div style="height:620px">%s</div></div>'
            % (title, w, h, width, h / w, svg))

    doc = (
        '<!DOCTYPE html><html><head><meta charset="utf-8">'
        '<title>布局引擎对比</title></head>'
        '<body style="margin:0;padding:16px;background:#f8f9fa;'
        'font-family:sans-serif">'
        '<div style="font:500 16px sans-serif;margin-bottom:4px">'
        '同一张图（20 图形 / 27 边 / 19 条带标签）·三个方案'
        '</div>'
        '<div style="font:13px sans-serif;color:#868e96;margin-bottom:12px">'
        '红字＝边标签；<b>粗体红字</b>＝压住了图形或另一个标签的位置'
        '</div>'
        '<div style="display:flex;gap:16px;align-items:stretch">%s</div>'
        '</body></html>' % "".join(cards))

    with open(TARGET, "w", encoding="utf-8") as handle:
        handle.write(doc)
    print("written:", TARGET)


if __name__ == "__main__":
    main()
