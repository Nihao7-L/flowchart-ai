# -*- coding: utf-8 -*-
"""Faithful offline renderer for ChartFlow internal scene JSON.

The internal schema (AgentResult.sceneJson() -> ctx.validScene()) is a JSON with
an "elements" array. Each element:
  - node: {id, type:"rectangle"|"ellipse"|"diamond", x, y, width, height,
           label:{text}|text, backgroundColor, strokeColor, strokeStyle, ...}
  - frame: {id, type:"frame", x, y, width, height, name, strokeColor,
            strokeStyle, strokeWidth, backgroundColor, children:[ids]}
  - arrow: {id, type:"arrow", start:{id}, end:{id}, x, y, points:[[dx,dy],...],
            label:{text}|text, containerId?, ...}
  - text : {id, type:"text", x, y, text, fontSize, containerId?}

Arrow points are stored RELATIVE to (arrow.x, arrow.y) — same convention as the
Excalidraw skeleton. We render the polyline start=(x,y) + each delta.

Usage:
  python scene_renderer.py input.json [-o out.png] [--report out.txt]
"""
import json
import math
import sys
import os
import itertools

from PIL import Image, ImageDraw, ImageFont

FONT_DIRS = [
    r"C:\Windows\Fonts",
]
FONT_CANDIDATES = [
    ("msyh.ttc", 0),      # Microsoft YaHei
    ("simhei.ttf", 0),    # SimHei
    ("simsun.ttc", 0),    # SimSun
    ("msyhbd.ttc", 0),    # YaHei Bold
]


def load_font(size):
    for fname, idx in FONT_CANDIDATES:
        for d in FONT_DIRS:
            p = os.path.join(d, fname)
            if os.path.exists(p):
                try:
                    return ImageFont.truetype(p, size, index=idx)
                except Exception:
                    pass
    return ImageFont.load_default()


# color helpers ----------------------------------------------------------------
def hex_to_rgb(h):
    if not h or not isinstance(h, str) or not h.startswith("#"):
        return (200, 200, 200)
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    try:
        return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))
    except Exception:
        return (200, 200, 200)


def lighten(rgb, amt=0.85):
    r, g, b = rgb
    r = int(r + (255 - r) * amt)
    g = int(g + (255 - g) * amt)
    b = int(b + (255 - b) * amt)
    return (r, g, b)


def text_of(el):
    raw = el.get("label")
    if isinstance(raw, dict):
        return raw.get("text") or ""
    if isinstance(raw, str):
        return raw
    raw = el.get("text")
    if isinstance(raw, dict):
        return raw.get("text") or ""
    if isinstance(raw, str):
        return raw
    return ""


def label_width(text, font):
    try:
        w = font.getlength(text)
    except Exception:
        w = len(text) * font.size * 0.6
    return w


def draw_text_centered(draw, cx, cy, text, font, fill, max_w=None):
    if not text:
        return
    # wrap if too wide
    lines = []
    cur = ""
    for ch in text:
        if max_w and label_width(cur + ch, font) > max_w and cur:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        lines.append(cur)
    line_h = font.size * 1.25
    total_h = line_h * len(lines)
    y = cy - total_h / 2 + line_h / 2
    for ln in lines:
        w = label_width(ln, font)
        draw.text((cx - w / 2, y - font.size / 2), ln, font=font, fill=fill)
        y += line_h


# geometry helpers -------------------------------------------------------------
def node_box(el):
    x = float(el.get("x", 0))
    y = float(el.get("y", 0))
    w = float(el.get("width", 160))
    h = float(el.get("height", 60))
    return x, y, w, h


def arrow_points(el):
    """Return absolute polyline points for an arrow."""
    x = float(el.get("x", 0) or 0)
    y = float(el.get("y", 0) or 0)
    pts = el.get("points")
    if not pts or not isinstance(pts, list) or len(pts) < 2:
        return [(x, y), (x, y)]
    out = []
    for p in pts:
        out.append((x + float(p[0]), y + float(p[1])))
    return out


def seg_intersect(p1, p2, p3, p4):
    def ccw(a, b, c):
        return (c[1] - a[1]) * (b[0] - a[0]) - (b[1] - a[1]) * (c[0] - a[0])
    d1 = ccw(p3, p4, p1)
    d2 = ccw(p3, p4, p2)
    d3 = ccw(p1, p2, p3)
    d4 = ccw(p1, p2, p4)
    if ((d1 > 0) != (d2 > 0)) and ((d3 > 0) != (d4 > 0)):
        return True
    return False


def point_in_box(px, py, box, pad=0.0):
    x, y, w, h = box
    return (px >= x - pad) and (px <= x + w + pad) and (py >= y - pad) and (py <= y + h + pad)


def dist_to_seg(px, py, a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0, min(1, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def arrow_hits_box(pts, box, pad=0.0):
    for i in range(len(pts) - 1):
        if seg_intersect(pts[i], pts[i + 1], (box[0], box[1]), (box[0] + box[2], box[1])):
            return True
        if seg_intersect(pts[i], pts[i + 1], (box[0] + box[2], box[1]), (box[0] + box[2], box[1] + box[3])):
            return True
        if seg_intersect(pts[i], pts[i + 1], (box[0] + box[2], box[1] + box[3]), (box[0], box[1] + box[3])):
            return True
        if seg_intersect(pts[i], pts[i + 1], (box[0], box[1] + box[3]), (box[0], box[1])):
            return True
    # endpoint inside box
    if len(pts) >= 2:
        if point_in_box(pts[0][0], pts[0][1], box, pad) or point_in_box(pts[-1][0], pts[-1][1], box, pad):
            return True
    return False


def render(scene, out_png, report_path=None):
    data = json.loads(scene)
    elements = data.get("elements", data if isinstance(data, list) else [])

    nodes = {}
    frames = {}
    arrows = {}
    texts = {}
    lifelines = {}
    for el in elements:
        t = el.get("type")
        if t in ("rectangle", "ellipse", "diamond"):
            # 生命线是 sequence 排版引擎合成的 2px 虚线装饰条
            # （id 前缀与 SequenceLayout.java:152 同源），语义上不是
            # 「可被箭头穿过的节点」——消息箭头横跨两条生命线正是
            # 时序图的定义，不能算压线。
            if str(el.get("id", "")).startswith("life_"):
                lifelines[el["id"]] = el
            else:
                nodes[el["id"]] = el
        elif t == "frame":
            frames[el["id"]] = el
        elif t == "arrow":
            arrows[el["id"]] = el
        elif t == "text":
            texts[el["id"]] = el

    # bounds
    xs, ys = [], []
    for el in list(nodes.values()) + list(frames.values()) + list(texts.values()) + list(lifelines.values()):
        x, y, w, h = node_box(el) if el["type"] != "text" else (
            float(el.get("x", 0)), float(el.get("y", 0)),
            float(el.get("width", 80)), float(el.get("height", 30)))
        xs += [x, x + w]
        ys += [y, y + h]
    for el in arrows.values():
        for px, py in arrow_points(el):
            xs.append(px)
            ys.append(py)
    if not xs:
        xs, ys = [0, 100], [0, 100]
    minx, maxx = min(xs), max(xs)
    miny, maxy = min(ys), max(ys)
    pad = 80
    minx -= pad
    miny -= pad
    maxx += pad
    maxy += pad
    W = int(maxx - minx)
    H = int(maxy - miny)

    IMG_SCALE = 1
    img = Image.new("RGB", (W, H), (255, 255, 255))
    d = ImageDraw.Draw(img)
    font = load_font(20)
    font_sm = load_font(15)

    def T(p):
        return (int(p[0] - minx), int(p[1] - miny))

    problems = []

    # draw frames first (nested: outer first). Order by nesting depth via children.
    frame_ids_list = sorted(frames.keys(), key=lambda fid: -len(frames[fid].get("children", []) or []))
    for fid in frames:
        el = frames[fid]
        x, y, w, h = node_box(el)
        stroke = hex_to_rgb(el.get("strokeColor", "#1e1e1e"))
        if el.get("backgroundColor") and el.get("backgroundColor") != "transparent":
            fill = lighten(hex_to_rgb(el.get("backgroundColor")), 0.92)
            d.rectangle([T((x, y)), T((x + w, y + h))], fill=fill)
        dash = el.get("strokeStyle") == "dashed"
        width = int(el.get("strokeWidth", 1) or 1)
        col = stroke
        if dash:
            # dashed rectangle
            draw_dashed_rect(d, T((x, y)), T((x + w, y + h)), col, width)
        else:
            d.rectangle([T((x, y)), T((x + w, y + h))], outline=col, width=width)
        nm = el.get("name", "")
        if nm:
            d.text((T((x + 6, y + 6))), nm, font=font_sm, fill=col)

    # arrows (under nodes so endpoints tuck under)
    for aid, el in arrows.items():
        pts = arrow_points(el)
        ap = [T(p) for p in pts]
        col = hex_to_rgb(el.get("strokeColor", "#868e96"))
        width = int(el.get("strokeWidth", 1.6) or 1.6)
        d.line(ap, fill=col, width=width, joint="curve")
        # arrowhead at last segment
        if len(ap) >= 2:
            draw_arrowhead(d, ap[-2], ap[-1], col, width)
        lbl = text_of(el)
        if lbl:
            # midpoint of polyline by length
            mid = poly_mid(pts)
            mx, my = T(mid)
            # containerId -> bound near its point; else midpoint
            if el.get("containerId"):
                owner = nodes.get(el.get("containerId"))
                if owner:
                    ox, oy, ow, oh = node_box(owner)
                    mx, my = T((ox + ow / 2, oy + oh / 2))
            lw = label_width(lbl, font_sm)
            d.rectangle([(mx - lw / 2 - 3, my - 12), (mx + lw / 2 + 3, my + 12)],
                        fill=(255, 255, 255))
            d.text((mx - lw / 2, my - 9), lbl, font=font_sm, fill=(192, 42, 42))
            # problem: label overlapping a node
            for nid, n in nodes.items():
                nx, ny, nw, nh = node_box(n)
                if point_in_box(mx, my, (nx, ny, nw, nh), 4):
                    problems.append(f"边标签「{lbl}」压在节点「{text_of(n)}」上 (arrow {aid})")

    # nodes（生命线也画：它们是时序图的骨架，只是不参与压线判定）
    for nid, el in itertools.chain(nodes.items(), lifelines.items()):
        x, y, w, h = node_box(el)
        t = el["type"]
        bg = el.get("backgroundColor", "transparent")
        fill = lighten(hex_to_rgb(bg), 0.85) if bg and bg != "transparent" else (241, 243, 245)
        stroke = hex_to_rgb(el.get("strokeColor", "#495057"))
        width = int(el.get("strokeWidth", 1.5) or 1.5)
        box_t = [T((x, y)), T((x + w, y + h))]
        if t == "rectangle":
            d.rectangle(box_t, fill=fill, outline=stroke, width=width)
        elif t == "ellipse":
            d.ellipse(box_t, fill=fill, outline=stroke, width=width)
        elif t == "diamond":
            cx, cy = (x + w / 2) - minx, (y + h / 2) - miny
            pts = [(cx, (y - miny)), ((x + w) - minx, cy), (cx, (y + h) - miny), ((x) - minx, cy)]
            d.polygon(pts, fill=fill, outline=stroke)
        lbl = text_of(el)
        draw_text_centered(d, (x + w / 2) - minx, (y + h / 2) - miny, lbl, font, (33, 37, 41),
                          max_w=w - 16)

    # standalone texts
    for tid, el in texts.items():
        if el.get("containerId"):
            continue
        x = float(el.get("x", 0)) - minx
        y = float(el.get("y", 0)) - miny
        txt = el.get("text", "")
        if txt:
            d.text((x, y), txt, font=font_sm, fill=(33, 37, 41))

    # problem detection -------------------------------------------------------
    # 1) overlapping nodes
    node_list = list(nodes.items())
    for i in range(len(node_list)):
        for j in range(i + 1, len(node_list)):
            ida, a = node_list[i]
            idb, b = node_list[j]
            ax, ay, aw, ah = node_box(a)
            bx, by, bw, bh = node_box(b)
            ox = min(ax + aw, bx + bw) - max(ax, bx)
            oy = min(ay + ah, by + bh) - max(ay, by)
            if ox > 2 and oy > 2:
                problems.append(f"节点重叠：{text_of(a)} 与 {text_of(b)} "
                                f"({ox:.0f}x{oy:.0f} 重叠)")

    # 2) arrows crossing/running through a node that is neither endpoint
    for aid, el in arrows.items():
        pts = arrow_points(el)
        start_id = (el.get("start") or {}).get("id")
        end_id = (el.get("end") or {}).get("id")
        for nid, n in nodes.items():
            if nid == start_id or nid == end_id:
                continue
            nx, ny, nw, nh = node_box(n)
            if arrow_hits_box(pts, (nx, ny, nw, nh), 1.0):
                problems.append(f"箭头 {aid} 穿过节点「{text_of(n)}」 "
                                f"(start={start_id}, end={end_id})")

    # 3) dangling arrows (endpoint not near any node it claims)
    def near_endpoint(ep_id, P):
        # P is start or end absolute point
        if ep_id in nodes:
            nx, ny, nw, nh = node_box(nodes[ep_id])
            return point_in_box(P[0], P[1], (nx, ny, nw, nh), 12)
        return None
    for aid, el in arrows.items():
        pts = arrow_points(el)
        if len(pts) < 2:
            continue
        start_id = (el.get("start") or {}).get("id")
        end_id = (el.get("end") or {}).get("id")
        for ep_id, P in ((start_id, pts[0]), (end_id, pts[-1])):
            if ep_id is None:
                continue
            if ep_id in nodes:
                if not near_endpoint(ep_id, P):
                    problems.append(f"箭头 {aid} 端点悬空：它声明连到「{ep_id}」"
                                    f"但端点不在该节点上")
            elif ep_id in frames:
                fx, fy, fw, fh = node_box(frames[ep_id])
                if not point_in_box(P[0], P[1], (fx, fy, fw, fh), 12):
                    problems.append(f"箭头 {aid} 端点悬空：声明连到框「{ep_id}」但不在框内")
            else:
                problems.append(f"箭头 {aid} 端点引用了不存在的元素「{ep_id}」（悬空/孤儿）")

    # 4) parallel-region frames should be purple dashed
    for fid, el in frames.items():
        name = el.get("name", "")
        if name.startswith("并行：") or "并行" in name:
            if el.get("strokeStyle") != "dashed" or hex_to_rgb(el.get("strokeColor", "")) != hex_to_rgb("#a855f7"):
                problems.append(f"并行区框「{name}」未渲染成紫色虚线 (id={fid})")

    img.save(out_png)
    if report_path:
        with open(report_path, "w", encoding="utf-8") as f:
            f.write(f"图元统计：节点 {len(nodes)} / 生命线 {len(lifelines)} / 框 {len(frames)} / 箭头 {len(arrows)} / 文字 {len(texts)}\n")
            f.write(f"包围盒 {W} x {H} px\n")
            f.write("\n".join(f"  - {p}" for p in problems) if problems else "未检测到明显布局问题")
    return problems, (W, H)


def poly_mid(pts):
    total = 0.0
    segs = []
    for i in range(len(pts) - 1):
        d = math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1])
        segs.append((pts[i], pts[i + 1], d))
        total += d
    if total == 0:
        return pts[0]
    half = total / 2
    acc = 0
    for a, b, d in segs:
        if acc + d >= half:
            t = (half - acc) / d if d else 0
            return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        acc += d
    return pts[-1]


def draw_arrowhead(d, p1, p2, col, width):
    import math
    ang = math.atan2(p2[1] - p1[1], p2[0] - p1[0])
    size = 10 + width * 2
    a1 = (p2[0] - size * math.cos(ang - 0.4), p2[1] - size * math.sin(ang - 0.4))
    a2 = (p2[0] - size * math.cos(ang + 0.4), p2[1] - size * math.sin(ang + 0.4))
    d.polygon([p2, a1, a2], fill=col)


def draw_dashed_rect(d, p1, p2, col, width):
    x1, y1 = p1
    x2, y2 = p2
    dash = 8
    gap = 6
    # top
    for x in range(int(x1), int(x2), dash + gap):
        d.line([(x, y1), (min(x + dash, x2), y1)], fill=col, width=width)
    for x in range(int(x1), int(x2), dash + gap):
        d.line([(x, y2), (min(x + dash, x2), y2)], fill=col, width=width)
    for y in range(int(y1), int(y2), dash + gap):
        d.line([(x1, y), (x1, min(y + dash, y2))], fill=col, width=width)
    for y in range(int(y1), int(y2), dash + gap):
        d.line([(x2, y), (x2, min(y + dash, y2))], fill=col, width=width)


def main():
    if len(sys.argv) < 2:
        print("usage: scene_renderer.py input.json [-o out.png] [--report out.txt]")
        sys.exit(1)
    inp = sys.argv[1]
    out = "out.png"
    rep = None
    i = 2
    while i < len(sys.argv):
        if sys.argv[i] == "-o":
            out = sys.argv[i + 1]
            i += 2
        elif sys.argv[i] == "--report":
            rep = sys.argv[i + 1]
            i += 2
        else:
            i += 1
    with open(inp, encoding="utf-8") as f:
        scene = f.read()
    problems, (W, H) = render(scene, out, rep)
    print(f"rendered {out} ({W}x{H}); problems={len(problems)}")
    for p in problems:
        print("  - " + p)


if __name__ == "__main__":
    main()
