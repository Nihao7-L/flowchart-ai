#!/bin/bash
# ChartFlow 离线 E2E 套件跑批器（自带起前端、跑完回收）
#
# 离线 E2E（e2e_arrow_unbind / e2e_bugB_vanishing_arrow / e2e_crud_cycles /
# e2e_delete_cascade / e2e_drag_integrity / e2e_drag_stress）靠 harness.py
# 在浏览器里打桩 /api/chat，只验前端逻辑，**不需要后端、不需要 LLM**，
# 只需前端 dev server（127.0.0.1:5173，且必须是 dev 构建 —— 探针
# window.__chartflow 只在 import.meta.env.DEV 下挂载）。
#
# 本脚本负责：① 用安全姿势起前端（managed node glob + vite.js 直跑 +
# --host 127.0.0.1 --strictPort，避开 npm run dev 在非 TTY 沙箱异常退出）② 等健康
# ③ 依次跑 6 个离线 E2E ④ 回收前端 ⑤ 以失败数作退出码。
#
# 用法：  bash tools/e2e/run-e2e.sh [--with-probes]
#   --with-probes   额外跑 probe_*.py 探针（同样只需前端）
#
# 前置：  frontend/node_modules 已装（否则先 `npm install`）。
#         Playwright 浏览器在仓库根 .playwright（脚本自动设 PLAYWRIGHT_BROWSERS_PATH）。
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"          # workbuddyFlow（文档/工具子项目）
REPO="$(cd "$ROOT/.." && pwd)"             # 仓库根（frontend/src 在此）
FRONTEND="$REPO/frontend"
PORT=5173
HOST=127.0.0.1

# --- Playwright 浏览器路径（仓库根 .playwright；转 Windows 路径更稳）---
PB="$REPO/.playwright"
if command -v cygpath >/dev/null 2>&1; then PB="$(cygpath -w "$PB")"; fi
export PLAYWRIGHT_BROWSERS_PATH="$PB"

# --- 选 python：优先能用 playwright 的；managed 3.13 没装，回退系统 python ---
PY="$(command -v python3 || command -v python)"
if ! "$PY" -c "import playwright" >/dev/null 2>&1; then
  for cand in "D:/Python-Home/python.exe" "C:/Python312/python.exe" "C:/Python313/python.exe"; do
    if [ -x "$cand" ] && "$cand" -c "import playwright" >/dev/null 2>&1; then PY="$cand"; break; fi
  done
fi
if ! "$PY" -c "import playwright" >/dev/null 2>&1; then
  echo "未找到装有 playwright 的 python，请先 pip install playwright"; exit 4
fi

# --- 选 node：managed 版本目录最大者（避免写死版本号随升级失效）---
NODE_DIR="$(ls -d /c/Users/22719/.workbuddy/binaries/node/versions/*/ 2>/dev/null | sort -V | tail -1)"
if [ -n "$NODE_DIR" ]; then export PATH="$NODE_DIR:$PATH"; fi

# --- 前置检查 ---
if [ ! -d "$FRONTEND/node_modules" ]; then
  echo "缺少 $FRONTEND/node_modules，请先在该目录执行 npm install"; exit 3
fi

# --- 起前端（vite.js 直跑，绕过 npm run dev 沙箱异常）---
FE_LOG="$REPO/.e2e-frontend.log"
echo "启动前端 dev server -> $FE_LOG"
( cd "$FRONTEND" && exec node node_modules/vite/bin/vite.js --host "$HOST" --port "$PORT" --strictPort ) >"$FE_LOG" 2>&1 &
FE_PID=$!

# --- 等前端健康（最多 90s，首启冷编译可能较慢）---
ready=0
for i in $(seq 1 90); do
  if curl -s -o /dev/null "http://$HOST:$PORT/"; then ready=1; break; fi
  # 前端进程已死则提前退出
  if ! kill -0 $FE_PID 2>/dev/null; then break; fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "前端未能就绪（90s 超时或进程退出），详见 $FE_LOG"; kill $FE_PID 2>/dev/null; exit 2
fi
echo "前端已就绪 http://$HOST:$PORT/"

# --- 跑离线 E2E ---
cd "$HERE"
fail=0
OFFLINE="e2e_arrow_unbind e2e_bugB_vanishing_arrow e2e_crud_cycles e2e_delete_cascade e2e_drag_integrity e2e_drag_stress"
for t in $OFFLINE; do
  echo ""
  echo "==== $t ===="
  if "$PY" "$HERE/$t.py"; then
    echo "  -> PASS"
  else
    echo "  -> FAIL"
    fail=$((fail+1))
  fi
done

# --- 可选：探针 ---
if [ "${1:-}" = "--with-probes" ]; then
  for p in probe_text_metrics probe_frontend_geom probe_elbow_arrow probe_frame_membership probe_frame_audit probe_contract_capacity; do
    echo ""
    echo "==== $p ===="
    if "$PY" "$HERE/$p.py"; then
      echo "  -> PASS"
    else
      echo "  -> FAIL"
      fail=$((fail+1))
    fi
  done
fi

# --- 回收前端 ---
kill $FE_PID 2>/dev/null
wait $FE_PID 2>/dev/null

echo ""
echo "========================================"
echo "离线 E2E 汇总：失败 $fail 个"
echo "========================================"
exit $fail
