#!/bin/bash
# 前端 dev server 启动（E2E 探针 window.__chartflow 只在 dev 构建下挂载，必须 dev 模式）
# 显式绑 127.0.0.1：避免 localhost 在 Windows 上先解析到 ::1 导致 Playwright 连不上。
# 直跑 vite.js 而非 `npm run dev` —— 后者在这个非 TTY 沙箱里会 5 秒内异常退出（EXIT=1、无错误输出）。
exec > /tmp/frontend.log 2>&1
# managed node：版本目录会随 WorkBuddy 升级而变（曾写死 22.22.2-2，实际已升到 22.22.2-3 导致找不到 node），
# 故按 glob 取版本号最大的一个；一个都没有就退回 PATH 上的 node。
NODE_DIR="$(ls -d /c/Users/22719/.workbuddy/binaries/node/versions/*/ 2>/dev/null | sort -V | tail -1)"
if [ -n "$NODE_DIR" ]; then
  export PATH="$NODE_DIR:$PATH"
fi
cd "F:/ProgramData/IDEA/flowchart/frontend" || exit 1
echo "START $(date) node=$(command -v node)"
node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort
echo "EXIT=$? $(date)"
