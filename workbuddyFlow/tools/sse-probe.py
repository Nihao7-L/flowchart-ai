#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SSE 完整性探针：调用后端 /api/chat，严格校验每个事件的 JSON 是否完整。"""
import json
import sys
import urllib.request

URL = "http://localhost:8080/api/chat"
PAYLOAD = json.dumps(
    {"sessionId": "probe-strict", "message": "画一个三节点的登录流程图：开始 -> 校验账号 -> 登录成功"},
    ensure_ascii=False,
).encode("utf-8")

req = urllib.request.Request(
    URL, data=PAYLOAD, headers={"Content-Type": "application/json"}, method="POST"
)
print("=== 发送请求 ===")
raw_events = []
with urllib.request.urlopen(req, timeout=180) as resp:
    print("status:", resp.status, "content-type:", resp.headers.get("Content-Type"))
    buf = b""
    while True:
        chunk = resp.read1(4096)
        if not chunk:
            break
        buf += chunk
    raw = buf.decode("utf-8", errors="replace")

print("=== 原始 SSE 总字节 ===", len(raw.encode("utf-8")))
lines = raw.split("\n")
print("=== 逐事件解析 ===")
for i, line in enumerate(lines):
    if not line.startswith("data:"):
        continue
    body = line[5:].strip()
    if not body:
        continue
    raw_events.append(body)
    try:
        env = json.loads(body)
    except Exception as e:
        print(f"[{i}] 外层 JSON 解析失败: {e}")
        print("     片段:", body[:200])
        continue
    etype = env.get("type")
    data = env.get("data", "")
    if etype == "result":
        try:
            scene = json.loads(data)
            els = scene.get("elements", [])
            print(f"[{i}] type=result OK  elements={len(els)}")
            types = {}
            for el in els:
                types[el.get("type")] = types.get(el.get("type"), 0) + 1
            print("     类型分布:", types)
            # 逐元素体检
            ids = {el.get("id") for el in els}
            for el in els:
                problems = []
                t = el.get("type")
                if t in ("rectangle", "ellipse", "diamond") and not isinstance(el.get("label"), (dict, type(None))):
                    problems.append(f"label 不是对象: {type(el.get('label')).__name__}")
                if isinstance(el.get("label"), dict) and not str(el["label"].get("text", "")).strip():
                    problems.append("label.text 为空")
                if t == "text" and not str(el.get("text", "")).strip():
                    problems.append("text 元素缺 text")
                if t == "frame":
                    problems.append("含 frame 元素（前端需要 children 补丁）")
                if t == "arrow":
                    for k in ("start", "end"):
                        b = el.get(k)
                        if isinstance(b, dict) and b.get("id") not in ids:
                            problems.append(f"{k}.id 悬空 -> {b.get('id')}")
                if problems:
                    print(f"     [!] {el.get('id')} ({t}): {'; '.join(problems)}")
        except Exception as e:
            print(f"[{i}] type=result 但内层 JSON 解析失败: {e}")
            print("     收到长度:", len(data), "前 120 字符:", repr(data[:120]))
            print("     后 80 字符:", repr(data[-80:]))
    else:
        print(f"[{i}] type={etype} data={str(data)[:60]!r}")

print("=== 结尾原始文本（最后 200 字符）===")
print(repr(raw[-200:]))
