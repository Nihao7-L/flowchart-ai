#!/bin/bash
# 浏览器端画布体检（不需要后端、不调用 LLM）
#
# 用途：验证「后端 IR -> 画布」这条链路在真实浏览器里确实出图。查三件事：
#   1. 取景边界是否有限   —— 出现 NaN 会让整张图都不可见（2026-09-16 踩过两次）
#   2. 有没有「滚动回到内容」按钮 —— 有 = 元素在场景里但落在视口外
#   3. static canvas 的非白像素数 —— 直接回答"到底画出来了没有"
#
# 前置：前端 dev server 已起（默认 http://localhost:5173）；必须是 dev 构建，
#       因为探针 window.__chartflow 只在 `import.meta.env.DEV` 下挂载（见 Canvas.tsx）。
# 用法：bash frontend/tools/canvas-browser-check.sh [url]
# 输出：结果写入 /tmp/canvas-browser-check.txt（agent-browser 的输出直接进管道会被中断，
#       所以统一落文件），跑完 `cat /tmp/canvas-browser-check.txt` 查看。
exec > /tmp/canvas-browser-check.txt 2>&1

URL="${1:-http://localhost:5173}"
echo "=== canvas-browser-check @ ${URL} ==="
echo "START $(date)"

agent-browser open "$URL" || { echo "FAIL: 打不开页面"; exit 1; }
agent-browser reload
agent-browser wait 4500

echo "--- 注入典型场景并检查 ---"
agent-browser eval '(async function(){
  var adapter = await import("/src/lib/sceneAdapter.ts");
  var exc = await import("/node_modules/.vite/deps/@excalidraw_excalidraw.js");
  var api = window.__chartflow;
  if (!api) return JSON.stringify({ fatal: "探针 window.__chartflow 不存在（是否用了生产构建？）" });

  // 典型后端 IR：三个形状 + 标签 + 绑定式箭头（缺 x/y，正是历史翻车点）+ frame + text
  var scene = { elements: [
    { id:"s1", type:"ellipse",   x:0,   y:0, width:120, height:60, label:{text:"开始"} },
    { id:"s2", type:"diamond",   x:300, y:0, width:140, height:90, label:{text:"校验"} },
    { id:"s3", type:"rectangle", x:620, y:0, width:120, height:60, label:{text:"成功"} },
    { id:"t1", type:"text",      x:0,   y:200, text:"说明文字", fontSize:16 },
    { id:"f1", type:"frame",     x:-40, y:-60, width:900, height:400, name:"登录流程" },
    { id:"a1", type:"arrow",     start:{id:"s1"}, end:{id:"s2"} },
    { id:"a2", type:"arrow",     start:{id:"s2"}, end:{id:"s3"} }
  ]};

  var conv = adapter.convertScene(scene.elements);
  api.updateScene({ elements: conv.elements });
  await new Promise(function(r){ requestAnimationFrame(function(){ requestAnimationFrame(r); }); });
  api.scrollToContent(api.getSceneElements(), { fitToContent:true, animate:false });
  await new Promise(function(r){ requestAnimationFrame(function(){ requestAnimationFrame(r); }); });

  var els = api.getSceneElements();
  var bounds = exc.getCommonBounds(els);
  // Excalidraw 有多层 canvas：索引 0 是 static 场景层，元素就画在它上面；
  // 其余层是全幅透明/纯色底，用来判断"画了没有"没有意义（实测恒等于全幅面积）。
  var cs = document.querySelectorAll(".excalidraw canvas");
  var staticCanvas = cs[0];
  var nonWhite = 0;
  if (staticCanvas) {
    var ctx = staticCanvas.getContext("2d");
    var d = ctx.getImageData(0, 0, staticCanvas.width, staticCanvas.height).data;
    for (var j = 0; j < d.length; j += 4) { if (d[j]<250||d[j+1]<250||d[j+2]<250) nonWhite++; }
  }

  var problems = [];
  if (bounds.some(function(v){ return !Number.isFinite(v); })) problems.push("取景边界含 NaN: " + bounds.join(","));
  if (document.querySelector(".scroll-back-to-content")) problems.push("出现「滚动回到内容」按钮（内容在视口外）");
  if (nonWhite === 0) problems.push("canvas 上没有任何绘制内容");
  if (conv.fatal) problems.push("转换 fatal: " + conv.fatal);
  if (conv.dropped.length) problems.push("被跳过元素: " + conv.dropped.join(","));

  return JSON.stringify({
    VERDICT: problems.length ? "FAIL" : "PASS",
    problems: problems,
    sceneCount: els.length,
    types: els.map(function(e){ return e.type; }).join(","),
    bounds: Array.from(bounds).map(function(v){ return Number.isFinite(v) ? Math.round(v) : "NaN"; }),
    canvasNonWhite: nonWhite
  }, null, 1);
})()'
echo "eval_exit=$?"

agent-browser close
echo "DONE $(date)"
