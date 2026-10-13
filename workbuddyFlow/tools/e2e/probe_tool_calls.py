#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
probe_tool_calls.py —— v2-55 步骤 2：逐候选实测 tool_calls（函数调用）支持度

背景：agent 模式（v2-55）靠 tool_calls 驱动，候选模型（尤其经中转站）
对 tools 参数的支持参差。探针不过的候选在 agent 模式下不入选。

测什么（对 agent loop 最要命的三件事）：
  1) 能否稳定返回 tool_calls（而非把"调用建议"写成纯文本）
  2) 一轮里能否并行发起多个工具调用
  3) 中文参数（工具名/参数值）是否原样保留

口径：
  - 从 application.yml 读生效的 llm.candidates[]（注释掉的不读，同 v4 巡检）
  - 非流式一次小请求（max_tokens=4096，提示里要求"一轮内同时调用两个工具"）
  - 判据只看结构化字段 choices[0].message.tool_calls，不猜正文
  - 密钥只在内存使用，绝不写入输出/打印
用法：
  D:/Python-Home/python.exe workbuddyFlow/tools/e2e/probe_tool_calls.py
"""
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

YML = r"F:\ProgramData\IDEA\flowchart\src\main\resources\application.yml"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "probe_tool_calls_report.json")
PROXY_FREE = urllib.request.build_opener(urllib.request.ProxyHandler({}))

KEYS = ('base-url', 'api-key', 'model', 'tier', 'max-tokens')

# 极小工具集：read_contract（带枚举参数）+ submit_scene（嵌套对象参数）。
# 与 v2-55 首批工具同名同形，测出来的结论可以直接迁移。
TOOLS = [
    {"type": "function", "function": {
        "name": "read_contract",
        "description": "读取某元素类型的契约片段",
        "parameters": {
            "type": "object",
            "properties": {"section": {"type": "string",
                                       "enum": ["shape", "arrow", "line",
                                                "text", "frame"]}},
            "required": ["section"],
        }}},
    {"type": "function", "function": {
        "name": "submit_scene",
        "description": "提交整份场景 JSON",
        "parameters": {
            "type": "object",
            "properties": {"scene": {
                "type": "object",
                "properties": {"elements": {"type": "array"}},
                "required": ["elements"]}},
            "required": ["scene"]}}},
]

# 明确要求"同一轮同时调两个工具"（测并行）+ 中文文本（测参数保真）
PROMPT = ("请同时做两件事（一轮内同时调用两个工具）："
          "1) 调用 read_contract 查 arrow 类型的契约；"
          "2) 调用 submit_scene 提交一个只含一个矩形"
          "（标签文字为「订单系统」）的场景。不要输出任何正文。")


def parse_candidates(path):
    with io.open(path, encoding='utf-8') as f:
        lines = f.readlines()
    cands, cur, in_cands = [], None, False
    for raw in lines:
        s = raw.strip()
        if s == 'candidates:':
            in_cands = True
            continue
        if not in_cands:
            continue
        if s.startswith('- name:'):
            if cur:
                cands.append(cur)
            cur = {'name': s[len('- name:'):].strip()}
            continue
        if cur is not None and not s.startswith('#'):
            m = re.match(r'^([A-Za-z0-9\-]+):\s*(.*)$', s)
            if m and m.group(1) in KEYS:
                cur[m.group(1)] = m.group(2).strip()
    if cur:
        cands.append(cur)
    return cands


def probe(cand, timeout=180):
    payload = {
        "model": cand.get('model'),
        "messages": [{"role": "user", "content": PROMPT}],
        "tools": TOOLS,
        "tool_choice": "auto",
        "stream": False,
        "max_tokens": 4096,
    }
    body = json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(cand['base-url'], data=body, method='POST')
    req.add_header('Content-Type', 'application/json')
    req.add_header('Authorization', 'Bearer ' + cand['api-key'])
    t0 = time.time()
    try:
        with PROXY_FREE.open(req, timeout=timeout) as r:
            raw = r.read().decode('utf-8', 'replace')
            elapsed = time.time() - t0
    except urllib.error.HTTPError as e:
        return {"name": cand['name'], "ok": False,
                "error": "HTTP %s: %s" % (e.code, e.read()[:300])}
    except Exception as e:
        return {"name": cand['name'], "ok": False,
                "error": "%s: %s" % (type(e).__name__, e)}

    try:
        data = json.loads(raw)
        msg = data['choices'][0]['message']
        finish = data['choices'][0].get('finish_reason')
        calls = msg.get('tool_calls') or []
        names = [c['function']['name'] for c in calls]
        args_ok = True
        zh_kept = False
        for c in calls:
            fn = c['function']
            try:
                args = json.loads(fn.get('arguments') or '{}')
            except Exception:
                args_ok = False
                continue
            if fn['name'] == 'submit_scene':
                els = args.get('scene', {}).get('elements', [])
                zh_kept = any('订单' in json.dumps(el, ensure_ascii=False)
                              for el in els) if els else False
        parallel = len(calls) >= 2
        return {
            "name": cand['name'], "model": cand.get('model'),
            "ok": len(calls) >= 1 and args_ok,
            "tool_calls": names, "parallel": parallel,
            "chinese_args_kept": zh_kept, "args_parse_ok": args_ok,
            "finish_reason": finish, "elapsed_s": round(elapsed, 1),
            "content_head": (msg.get('content') or "")[:80],
        }
    except Exception as e:
        return {"name": cand['name'], "ok": False,
                "error": "parse: %s; head=%s" % (e, raw[:200])}


def main():
    cands = parse_candidates(YML)
    print("生效候选 %d 个" % len(cands))
    results = []
    for c in cands:
        print("→ 探测 %s ..." % c['name'], flush=True)
        r = probe(c)
        results.append(r)
        if r.get('ok'):
            print("   ✓ tool_calls=%s parallel=%s 中文参数=%s %.1fs"
                  % (r['tool_calls'], r['parallel'],
                     r['chinese_args_kept'], r['elapsed_s']))
        else:
            print("   ✗ %s" % r.get('error', '无 tool_calls'))
    with io.open(OUT, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("报告已写入", OUT)
    return 0 if any(r.get('ok') for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
