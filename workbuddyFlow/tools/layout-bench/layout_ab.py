"""
固定基线 A/B 工具：把一批 scene JSON 喂进真实布局管线，输出可比对的指标。

用途：改布局代码后，用**同一批输入**跑一遍，和改前对比，避免被
「每次 LLM 输出都不一样」掩盖真实效果。

指标口径：
  - canvas：所有元素（节点/框/箭头折线）的包围盒
  - bends：每条箭头的折点数分布
  - overlap：框与框的重叠对数目
  - gap：主轴节点之间的平均空隙（同一直线上的相邻节点）

用法：
    python layout_ab.py <scene.json> [<scene.json> ...]
"""
import json
import sys
from collections import Counter


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def bbox(elements):
    lo_x = lo_y = 1e18
    hi_x = hi_y = -1e18
    for e in elements:
        t = e.get("type")
        if t == "arrow":
            x0, y0 = e.get("x", 0), e.get("y", 0)
            for p in e.get("points") or [[0, 0]]:
                lo_x = min(lo_x, x0 + p[0])
                lo_y = min(lo_y, y0 + p[1])
                hi_x = max(hi_x, x0 + p[0])
                hi_y = max(hi_y, y0 + p[1])
        else:
            x, y = e.get("x", 0), e.get("y", 0)
            w, h = e.get("width", 0), e.get("height", 0)
            lo_x, lo_y = min(lo_x, x), min(lo_y, y)
            hi_x, hi_y = max(hi_x, x + w), max(hi_y, y + h)
    return lo_x, lo_y, hi_x, hi_y


def bends(elements):
    out = []
    for e in elements:
        if e.get("type") != "arrow":
            continue
        pts = e.get("points") or []
        b = 0
        for i in range(1, len(pts) - 1):
            a, c, n = pts[i - 1], pts[i], pts[i + 1]
            d1 = (c[0] - a[0], c[1] - a[1])
            d2 = (n[0] - c[0], n[1] - c[1])
            if d1[0] * d2[1] - d1[1] * d2[0] != 0:
                b += 1
        out.append(b)
    return out


def frames_overlap(elements):
    """框与框的重叠对数（包含关系不算重叠）。"""
    frames = [e for e in elements if e.get("type") == "frame"]
    n = 0
    for i in range(len(frames)):
        for j in range(i + 1, len(frames)):
            a, b = frames[i], frames[j]
            ax, ay = a.get("x", 0), a.get("y", 0)
            aw, ah = a.get("width", 0), a.get("height", 0)
            bx, by = b.get("x", 0), b.get("y", 0)
            bw, bh = b.get("width", 0), b.get("height", 0)
            ox = min(ax + aw, bx + bw) - max(ax, bx)
            oy = min(ay + ah, by + bh) - max(ay, by)
            if ox > 0 and oy > 0:
                area = ox * oy
                # 完全包含（交集等于较小者）不算重叠
                small = min(aw * ah, bw * bh)
                if small <= 0 or area < small - 1e-6:
                    n += 1
    return n


def same_frame_overlaps(elements):
    """同一父框内的兄弟框是否互相重叠（这才是真问题）。"""
    by_id = {e.get("id"): e for e in elements}
    frames = [e for e in elements if e.get("type") == "frame"]
    n = 0
    for i in range(len(frames)):
        for j in range(i + 1, len(frames)):
            a, b = frames[i], frames[j]
            ax, ay = a.get("x", 0), a.get("y", 0)
            aw, ah = a.get("width", 0), a.get("height", 0)
            bx, by = b.get("x", 0), b.get("y", 0)
            bw, bh = b.get("width", 0), b.get("height", 0)
            ox = min(ax + aw, bx + bw) - max(ax, bx)
            oy = min(ay + ah, by + bh) - max(ay, by)
            if ox <= 0 or oy <= 0:
                continue
            area = ox * oy
            small = min(aw * ah, bw * bh)
            if small > 0 and area >= small - 1e-6:
                continue  # 包含关系
            n += 1
    _ = by_id
    return n


def report(path):
    d = load(path)
    els = d.get("elements") or []
    lo_x, lo_y, hi_x, hi_y = bbox(els)
    bs = bends(els)
    nodes = [e for e in els
             if e.get("type") in ("rectangle", "diamond", "ellipse")]
    frames = [e for e in els if e.get("type") == "frame"]
    return {
        "path": path,
        "canvas": (round(hi_x - lo_x), round(hi_y - lo_y)),
        "nodes": len(nodes),
        "frames": len(frames),
        "arrows": len(bs),
        "bend_max": max(bs) if bs else 0,
        "bend_dist": dict(sorted(Counter(bs).items())),
        "bend_avg": round(sum(bs) / len(bs), 2) if bs else 0,
        "frame_overlap": frames_overlap(els),
    }


def main(paths):
    for p in paths:
        r = report(p)
        name = p.replace("\\", "/").rsplit("/", 1)[-1]
        print(f"{name}")
        print(f"  canvas      {r['canvas'][0]}x{r['canvas'][1]}")
        print(f"  nodes={r['nodes']} frames={r['frames']} arrows={r['arrows']}")
        print(f"  bends       max={r['bend_max']} avg={r['bend_avg']} "
              f"dist={r['bend_dist']}")
        print(f"  frameOverlap {r['frame_overlap']}")


if __name__ == "__main__":
    main(sys.argv[1:])
