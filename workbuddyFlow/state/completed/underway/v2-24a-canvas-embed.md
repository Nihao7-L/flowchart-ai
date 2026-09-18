# v2-24a 画布嵌入 + model/IR + SSE 消费

> 状态：**完成**（2026-09-16 交付；验收期缺陷 1~12 全部闭合，12 项含 P0 五处）
> 归档：2026-09-18 由 `plans/underway/` 移入 `state/completed/underway/`（原始内容未回改）
> 完成结论：画布嵌入 + model/IR 状态层 + `sceneAdapter` 适配层 + SSE 全量 `result` 消费全部落地；文末两条遗留（布局不归前端、长流程竖长条）**均已由后端 v2-36 关闭**（决定 A2 删前端兜底布局；布局改由 `graph/SceneLayout` 产出纵横比受控的坐标）；证据见 `state/activeLog/2026-09-16.md` 与 `2026-09-18.md`
> 所属模块：M6a（前端渲染镜像层）
> 所属阶段：S1 看得见
> 前置：v2-23 设计稿已确认；M1 v2-11 SSE 协议已定型；npm install @excalidraw/excalidraw 已完成

## 目标

在 `frontend/` 中实现三栏布局，嵌入 Excalidraw 画布，消费后端 SSE 事件，将场景 IR 渲染到画布。

## 边界

- 允许新增：`@excalidraw/excalidraw` 嵌入、model/IR 状态层、SSE 消费（只读）
- 允许修改：前端调用 `POST /api/chat`（SSE）
- 禁止改动：后端（M1 已定），只消费其接口；不含 `onChange` 坐标回写（归 M6b①）

## 分层边界

落在 `frontend/src/`；只与后端 `controller` 通过 SSE 交互。

## 前置

- [x] M0 v2-6 前端脚手架空壳可跑
- [x] M1 v2-11 SSE 事件协议定型
- [x] v2-23 设计稿确认（三栏布局：左导航240px | 中画布flex:1 | 右聊天340px）
- [x] `npm install @excalidraw/excalidraw` 完成

## 步骤

1. 创建 `src/types.ts`（ChatEvent / ChatMessage / SceneIR 类型）
2. 创建 `src/components/Sidebar.tsx`（左导航栏）
3. 创建 `src/components/Canvas.tsx`（Excalidraw 画布 + 位置同步）
4. 创建 `src/components/ChatPanel.tsx`（右聊天栏 + 消息列表 + 输入框）
5. 重写 `src/App.tsx`（三栏布局 + SSE 消费 + 坐标回写）
6. 重写 `src/App.css`（完整样式 + 深色主题）
7. 更新 `vite.config.ts`（API 代理到 localhost:8080）

## 验收口径

1. `npm run build` TypeScript 0 错误
2. `npm run dev` 启动后 `http://localhost:5173` 显示三栏布局
3. 中间画布能渲染 Excalidraw 白板
4. 右侧聊天框输入消息能发到后端（需后端运行）
5. 后端 SSE 推送的 result 事件能渲染到画布

## 状态跟踪

- [x] src/types.ts
- [x] src/components/Sidebar.tsx
- [x] src/components/Canvas.tsx
- [x] src/components/ChatPanel.tsx
- [x] src/App.tsx（三栏布局 + SSE）
- [x] src/App.css（样式 + 深色主题）
- [x] vite.config.ts（API 代理）
- [x] npm run build 验证（tsc 0 错误 + vite exit 0；拆包生效）
- [x] npm run lint 验证（09-16 09:58，exit 0；修掉 3 个 error 后）
- [x] 门禁后端两步等价验证（09-16 09:58：32 测试绿 + checkstyle 0 违规）
- [ ] npm run dev 验证（待用户本地跑 + 后端启动；验收画布样式正常/首屏先出侧栏/画布异步出"加载中"）

## 验收暴露的缺陷（09-16，已修复）

1. **【P0，本计划内】缺 Excalidraw 样式表** → 画布区无样式、欢迎页图标炸成巨幅。
   - 现状：`frontend/src/` 全仓无 `@excalidraw/excalidraw/index.css` 导入（`App.tsx → ./App.css`、`main.tsx → ./index.css` 是仅有的两处）。
   - 该包（0.18.1）`exports` 提供 `"./index.css" → ./dist/prod/index.css`（144KB），JS（502KB）内不含 CSS → 必须显式 import。
   - **修复（09-16 09:45，用户授权「修改好」）**：`frontend/src/main.tsx` 首行加 `import '@excalidraw/excalidraw/index.css';`，置于 `import App from './App'` 之前。
   - 验收：`npm run build` 绿；`grep` 产物 JS 命中 `.welcome-screen-decor-hint--help svg{width:85px…}` 规则 → Excalidraw 0.18 以 JS 注入样式，随懒加载块生效。待用户 `npm run dev` 视觉确认正常尺度欢迎页。

2. **【新增，本计划内，09-16 09:45 修】前端加载慢** → 首屏白屏/卡顿。
   - 根因：`@excalidraw/excalidraw`（~500KB JS + 144KB CSS）在 `Canvas.tsx` 顶部**静态全量引入**，首屏必须等它解析完才画，无代码分割（code-splitting）。
   - 修复：
     - `frontend/src/App.tsx`：`import Canvas` → `const Canvas = lazy(() => import('./components/Canvas'))`，并用 `<Suspense fallback={<div className="canvas-loading">画布加载中…</div>}>` 包裹。
     - `frontend/src/App.css`：补 `.canvas-loading` 占位样式。
     - `frontend/vite.config.ts`：`optimizeDeps.include:['@excalidraw/excalidraw','react','react-dom']` + `build.rollupOptions.output.manualChunks` 拆 `excalidraw` / `react-vendor` 独立 chunk。
   - 验收：`npm run build` 通过；`dist/assets/` 验证 Excalidraw 重依赖（katex/cytoscape/mermaid/locale）拆成 100+ 按需 chunk，首屏主包不再同步拖入整个 Excalidraw 图。
   - 说明：属"感知提速 + 不阻塞首屏"，非削减总字节（Excalidraw 体积固有）。

3. **【本计划内，09-16 09:33 修】Excalidraw 界面为英文** → 右键菜单（Cut/Copy/Paste…）与左侧属性面板（Stroke/Background/Fill…）全英文。
   - 根因：Excalidraw 界面语言由 `langCode` 属性驱动，默认 `en`（`dist/types/excalidraw/types.d.ts:435 langCode?: Language["code"]`）。
   - 修复：
     - `frontend/src/components/Canvas.tsx`：`<Excalidraw>` 加 `langCode="zh-CN"`（`"zh-CN"` 在包内受支持列表；中文包 `dist/prod/locales/zh-CN-LNUGB5OW.js` 存在，构建产物含 `zh-CN-*.js` 按需 chunk）。
     - `frontend/src/components/Sidebar.tsx`：侧栏按钮 `Library` → `素材库`（对齐 Excalidraw 中文包译法，其 zh-CN locale 将 Library 译为「素材库」）。
   - 验收：`npm run build` 绿（tsc 0 错误、exit 0）；langCode 值合法（类型通过）。待用户 `npm run dev` 视觉确认。

4. **【P1，越界项，归 M6b①】`onChange` 直达 PATCH，请求刷屏**
   - 现状：`Canvas.tsx` L57 `onChange={handleChange}` → `App.tsx` L120-126 `PATCH /api/model/positions`（该端点已于 v2-34 演进为 `PUT /api/model/canvas`；此处记录的是当时现状）；Excalidraw 的 `onChange` 在挂载/选中/悬停/拖拽每帧/撤销/AI 回填时均触发（页面一加载即发）。
   - 收敛口径：坐标真实变化 + 拖拽结束（pointerup）后发一次 + 防抖；且仅回写带 `customData` 本方标记的用户元素（`constraint.md` 一.5：Excalidraw 会重生成 id，只能靠 `customData` 认领归属）。
   - 说明：本计划「边界」原定不含坐标回写（归 M6b①），此处属实现时提前带入；**是否就地收敛（可留在 v2-24a）或另开 v2-24b，待人拍板**。

5. **【环境，非代码】`src/main/resources/application.yml` 在工作区消失** → 后端启动即崩（`${llm.base-url}` / `${llm.api-key}` / `${llm.model}` 无默认值）。
   - 归属：M1 环境项，不属 v2-24a 代码范围；恢复方式待用户决定（自行重建 or 从 `37cba9e^` 取回，含原 key）。
   - 附注：`docker-compose.yml` 只传 `LLM_API_KEY`，缺 `base-url` / `model`，同样起不来（陈旧，待修）。

6. **【本计划内，09-16 09:58 修】界面风格与组件收敛（用户 09-16 决定）**
   - `去深色主题`：用户选「干脆不要深色主题」→ 全站固定白底。`App.tsx`（删 `isDark` state / `app light|dark` 类名）、`Sidebar.tsx`（删主题切换按钮与 `onToggleTheme`/`isDark` 入参）、`App.css`（删深色块 + `.btn-theme`/`.sidebar-footer`）、`index.css`（`color-scheme: light`）、`Canvas.tsx`（`theme="light"`）。
   - `删「素材库」按钮`：用户选「不要了」→ `Sidebar.tsx` 删按钮与 `onToggleLibrary` 入参、`App.tsx` 删传参、`App.css` 删 `.btn-library`。Excalidraw 自带素材库面板仍可从画布右上角按钮打开。
   - 附带修掉：深色块里 `.msg.agent` / `.msg.user` 只改背景不改文字色（深底深字不可读）——随深色主题一并移除，问题消失。

7. **【本计划内，09-16 09:58 修】构建/lint 红三项（此前被管道吞退出码掩盖）**
   - `Suspense` 边界缺失（**属被文件锁吞掉的改动**）：`App.tsx` import 了 `Suspense` 但 JSX 未使用，`Canvas` 为 `lazy` → 首屏无兜底边界即抛错白屏；已补 `<Suspense fallback={画布加载中…}>`。
   - 类型路径：`@excalidraw/excalidraw/types/types` → `@excalidraw/excalidraw/types`（TS2307；该错误把 `api` 退化成 `any`，掩盖了下一项）。
   - 废弃 API：`updateScene({commitToHistory:false})` → `{captureUpdate: CaptureUpdateAction.NEVER}`（TS2353）。
   - 未使用导入 `useRef`（TS6133）；`while (true)`（eslint no-constant-condition）；两处显式 `any`（eslint）→ 按 `scene.schema.json` 补 `SceneElement` 类型。
   - 证据：`tsc -b` / `npm run lint` / `npm run build` 三者 exit 0；后端 `clean test` 32 测试绿 + checkstyle 0 违规。
   - 更正：此前日志「三次 npm run build 均绿」结论有误（`| tail` 吞了退出码，vite 因 tsc 先失败而无输出，被误读为通过）。

8. **【本计划内，09-16 12:40 修】"契约 vs 转换器"缺口导致出图空白（P0）**
   - 现象：聊天显示"已生成 10 个元素"，画布**纯白无任何元素**；浏览器控制台才有 `console.error`。
   - 根因：LLM 输出中只要含 **`frame` 元素**，`convertToExcalidrawElements` 就抛 `TypeError: Cannot read properties of undefined (reading 'forEach')`（转换器对 frame 强制 `children.forEach`）；而 `scene.schema.json` 的 frame 分支是 `additionalProperties:false` 且**未定义 `children`** -> 契约与转换器互不兼容，frame 永远渲染不出来。又因**整批**转换失败 + 异常被 `catch` 吞掉 -> "生成了但画布全空"。
   - 实测矩阵：`frame 无 children` THROW / `frame children=[]` PASS / `text 缺 text` THROW(`replace`) / `10 元素无 frame` PASS 16 / `同 10 元素 + 一个 frame` THROW。
   - 修复：新增适配层 `src/lib/sceneAdapter.ts`（把转换逻辑从组件里抽出来，纯函数可测）：frame 在适配层补 `children: []`（不改契约）；并加逐元素隔离降级（整批失败 -> 逐元素定位毒元素 -> 幸存者整批重转以保住箭头绑定 -> 全败才 fatal）。
   - 交互后果修复：新增 `onRenderIssue` 上报口 -> `App.tsx` 去重后以 error 消息显示在聊天区，**不再静默**。
   - 回归固化：新增 `frontend/tools/scene-smoke.mjs`（`node tools/scene-smoke.mjs`，零新增依赖）。
   - 遗留待拍板：frame 语义不完整，是否改为"后端显式要求 `children: []`"或"契约去掉 frame"。

9. **【本计划内，09-16 12:40 修】场景所有权与视口接管：用户手绘被覆盖 / 视口被抢（P0）**
   - 现象：用户手绘的图案在 AI 生成后被清空；且用户准备作画时视口被夺走（"跳转到另外一个画布"）。
   - 根因：`Canvas.tsx` 收到新 IR 时 `updateScene({ elements: <AI 元素> })`，**语义是替换整个场景**；同时**无条件** `scrollToContent`。
   - 修复 A（场景合并）：AI 元素打 `customData.chartflowModel = true`；更新时 `elements = [...AI元素, ...用户元素]`，只替换带标记的部分；「新建对话」只摘 AI 元素、保留手绘。
   - 修复 B（视口取景）：`scrollToContent` 仅在「AI 元素 id 签名变化（新一轮生成）」时执行一次，且**取景框 = AI 元素 ∪ 用户元素** —— 只框 AI 元素会把用户图案留在框外（观感=跳到另一个画布），完全不框又会让 AI 图落到视口外（退化成"生成了看不到"），故取并集。
   - 修复 C（缺陷#4 就地收敛，原归 M6b①）：`onChange` 改 400ms 防抖 + 仅回写 AI 元素 + 坐标签名无变化不发请求 + 卸载清理计时器；**该缺陷因此可在 v2-24a 内闭合，不再挂 M6b①**。
   - 门禁：前端 tsc/eslint/vite build 三绿；后端 35 测试 + checkstyle 0 违规；冒烟 6/6 PASS。

10. **【本计划内，09-16 13:12~14:06 修】生成后画布空白真因：绑定式箭头缺坐标 -> 取景边界 NaN（P0）**
   - 现象：聊天显示"已生成 N 个元素"，画布空白，只剩一个**「滚动回到内容」**按钮（该按钮本身就是线索：元素在场景里，只是落在视口外）。用户反馈"还是原来的情况，还会出现空白"。
   - 取证手段（新增 dev 探针 `window.__chartflow`，见 `Canvas.tsx`，仅 `import.meta.env.DEV` 下挂载）：直接读 `getSceneElements()` / `getAppState()`，用 `getCommonBounds()` 算取景边界、用 static canvas 非白像素判断"到底画出来没有"。此前所有结论只能靠肉眼猜，有了探针一次 eval 就能问清。
   - 根因（实测数据）：LLM 输出绑定式箭头 `{id:"arrow1", type:"arrow", start:{id:..}, end:{id:..}}` **不带 x/y**（契约里 arrow 的 x/y 是可选的），而 Excalidraw 的 skeleton 要求 arrow 必须有 x/y -> 转换产物 `x=undefined` -> `getCommonBounds` 返回 `[NaN,NaN,NaN,NaN]` -> `scrollToContent` 用 NaN 算视口 -> 视口损坏 -> 元素全被画到视口外。
     - 证据 A：`nullXY=["arrow1|arrow|x=undefined,y=undefined", ...]`、`bounds=["NaN","NaN","NaN","NaN"]`、`scrollBtn=true`、static canvas 非白像素 **0**。
     - 证据 B（对照）：仅把 x/y 补成 0，`bounds=["0.5","-1.52","720","240"]`、`scrollBtn=false`、非白像素 **18843**。
     - 必然性：提示词明确"箭头优先用 start.id/end.id 绑定" -> 这条路**每次都会踩**，所以它不是偶发。
   - 修复 A（适配层补几何）：`sceneAdapter` 新增 `withArrowGeometry` —— 绑定式箭头缺几何时用被绑定元素推导：起点 = 起点元素**边界**朝终点方向的交点，`points=[[0,0],[终点-起点]]`。用边界交点而非中心点，是因为中心点会让箭头**穿过图形、与框内 label 文字重叠**（第一次修复后的截图实测）。
   - 修复 B（尺寸兜底）：`freedraw` 转换后 `width/height` 为 `undefined`，是 NaN 的**第二个来源**（`x + width`）；`ensureFiniteSize` 按 `points` 包围盒补尺寸，x/y 一并兜底。
   - 修复 C（`scrollToContent` 参数）：原代码传 `fitToViewport: true`，而 Excalidraw 0.18 的 opts 是**互斥联合**（`{fitToContent?; fitToViewport?: never}` | `{fitToViewport?; fitToContent?: never}`），`fitToViewport` 是"适配 frame 视口"专用分支；改为 `fitToContent: true` 并延后一帧（等 `updateScene` 提交完再算 bounds）。
   - 附带（契约 vs 渲染器，同类缺口第二例）：**`freedraw` 在 Excalidraw 里取景边界算不出来**（points 合法、x/y/w/h 齐全，`getCommonBounds` 依然 NaN）。AI 生成图表本不该输出手绘路径（那是人类领域的表达形式），故：适配层跳过 freedraw（用户手绘不经此路径，不影响手绘）；提示词明确禁止；`PromptServiceTest` 同步改为断言"7 种可生成 type + 明确禁用 freedraw"。
   - 回归固化：`scene-smoke.mjs` **6 -> 10 用例**（新增：绑定式箭头缺 x/y 时边界必须有限、箭头端点必须落在图形边缘、全类型混合 bounds 无 NaN、freedraw 被安全跳过）。
   - 门禁：前端 `tsc -b` / `eslint` / `vite build` 三绿（新增 `src/vite-env.d.ts` 以启用 `import.meta.env` 类型）；后端 `clean test` **43 测试**全绿 + checkstyle 0 违规；`scene-smoke.mjs` **10/10 PASS**。
   - 端到端实测（真实浏览器 + 真实 LLM，非模拟）：场景 A（纯生成）`bounds=[100,150,620,200]` 无 NaN、`scrollBtn=false`、static canvas 非白像素 `0 -> 20383`，截图呈现三个节点 + 两条边缘连线箭头；场景 B（先放用户元素再生成）`userMarkerKept=true`，手绘保留。
   - 新增可复用工具：`frontend/tools/canvas-browser-check.sh`（浏览器端画布体检，不依赖后端 / 不调用 LLM：注入典型场景后查 bounds、视口、绘制像素，输出 `PASS|FAIL`；自检 PASS）。

11. **【本计划内，09-16 13:12~14:06 修】后端校验层补全：6 类畸形数据此前能静默通过（P1）**
   - 背景：`GraphValidator` 此前**完全没校验 `label`**，也未校验 points 元素结构、枚举值、数值范围、id 唯一性、箭头端点引用完整性。
   - 后果（浏览器 22 用例矩阵实测）：`label` 写成字符串**不报错但静默丢文字**（图形在、字没了）；`points` 含非数字**不报错但产生 NaN 坐标**（图形画到画布外）；箭头端点**悬空**不报错但箭头失去跟随关系。这类"不抛错、可见地坏掉"比抛错更难查。
   - 修复：`GraphValidator` 补 `checkLabel`（必须是 `{text}` 对象且 text 非空）、`checkPointsShape`（每点必须是 2 个数字）、`checkOptionalEnum`（fillStyle / strokeStyle / textAlign / fontFamily）、`checkOptionalPercent`（opacity 0~100）、`checkOptionalMin`（fontSize ≥1，strokeWidth/roughness ≥0）、`checkBinding`（start.id / end.id 必须指向本场景已存在元素）、id 唯一性。实现上改两遍扫描：先收 id 全集，再逐元素校验（引用完整性必须先有全集）。
   - 测试：`GraphValidatorTest` **6 -> 14**（label 非对象 / label 缺 text / 端点悬空 / 重复 id / points 非法 / 非法枚举 / opacity 超范围 / text 缺 text）。
   - 证据：`clean test` -> `Tests run: 43, Failures: 0, Errors: 0` + `BUILD SUCCESS`；`checkstyle:check` -> `You have 0 Checkstyle violations.`

12. **【本计划内，09-16 15:00~15:20 修】报错后界面永久卡死 + 复杂图等待无反馈（P1）**
   - 现象：用户实测复杂图（5 层微服务电商架构）时，聊天栏显示出错误气泡后，**输入框与发送按钮一直保持 disabled**，实测从第 110 秒卡到第 390 秒，唯一出路是刷新页面。画布全空（`sceneCount=0`、`canvasNonWhite=0`）。
   - 根因（前端侧）：`App.tsx` 只在读循环退出（`done`）后才 `setIsLoading(false)`，等于**把"解锁界面"这件事完全托付给服务端把响应体正常收尾**。而服务端错误路径上响应恰恰不会正常收尾 —— 根因在后端 `sendError` 用了 `emitter.completeWithError(...)`：错误内容已经作为 `error` 事件推给客户端了，通道层再报错会让容器走 ERROR dispatch，而响应头已是 `Content-Type: text/event-stream`，容器找不到能把错误体写成 SSE 的消息转换器，日志实证抛 `HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream'`。响应既不收尾也不断开 → `reader.read()` 永远等不到 `done` → `isLoading` 永为 true。
   - 修复（前端，本计划职责）：收到终态事件（`result` / `error`）后立即 `await reader.cancel()` 并退出读循环 —— 界面解锁不再依赖服务端行为，服务端再反常也最多是"前端多发一次取消"。
   - 附带修复（等待可见化）：`ChatPanel` 增加**纯前端**计时，显示"正在生成... 已等待 N 秒"，超过 90 秒附一句"复杂架构图本轮实测需 100~260 秒"。刻意不引入服务端心跳：复杂图实测要等 50~111 秒，用户需要一个"还在动"的信号来区分"在跑"与"卡死"，而这件事前端自己就能做，零协议改动。
   - 验证（真实浏览器 + 真实 LLM）：把 8080 临时切成指向死端口的实例后，失败后 **8 秒内** `inputDisabled=false` / `stillLoading=false`，且能继续发第二条消息并再次拿到清晰错误（修复前：永久卡死）；成功路径两个复杂场景分别 `已生成 47 个元素` / `已生成 41 个元素`，浏览器错误均为 `[]`。
   - 门禁：前端 `tsc -b` / `eslint` 均 exit 0。
   - 遗留（未修，待拍板）：长流程会被 LLM 排成"竖长条"（实测宽 729 × 高 2130），适配后 Excalidraw 自动把 zoom 降到 0.2，文字要手动放大才看清 —— 是否给取景加"最小 zoom 下限"，或改提示词约束布局纵横比。
