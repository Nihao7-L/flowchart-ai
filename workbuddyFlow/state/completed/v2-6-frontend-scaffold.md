# 进行中计划 · v2-6 前端脚手架空壳

> 来源：`plans/masterPlan/m0-harness.md` 的 v2-6 子计划（工作台副本）；亦为 `m6-frontend.md` 的前置。

## 元信息
- 任务编号：v2-6（与 masterPlan 对应）
- 状态：完成（09-12：npm ci 退出码 0、npm run build 退出码 0、npm run lint 零告警、npm run dev HTTP 200；门禁三步等价执行全绿）
- 来源模块：M0 工程底座（harness） / 解锁 M6 前端模块
- 创建：2026-09-12 + AI

## 目标
在代码根新建 `frontend/`（Vite + React + TypeScript strict + ESLint）空壳，使 `npm install` / `npm run dev` / `npm run build` 可跑通；并让 `run-verify.ps1` 的前端检查步能识别到它。

## 边界
- 允许新增：`frontend/` 脚手架（package.json、vite 配置、tsconfig strict、eslint 配置、最小 `App` 入口）。
- 允许修改：无业务代码（本任务只搭空壳，不接 Excalidraw、不接后端）。
- 禁止改动：后端（`controller/service/llm/graph`），只消费其未来接口。

## 分层边界
落在代码根 `frontend/`，与后端 `src/` 平级；仅未来通过 REST + SSE 与后端 `controller` / `session` 交互。当前是空壳，不含渲染逻辑。

## 前置
- Node 18+ / npm（已探明本机 node 22、npm 10.8 可用）。
- 本任务不依赖 v2-3，但完成后 `run-verify.ps1` 的前端检查步（步骤 2/3）将不再跳过。
- 注意目录命名：`run-verify.ps1` 查的是 `frontend/`（非 `flowchart-frontend/`），故本任务建 `frontend/`；M6 正式前端在此目录生长。

## 步骤
1. 在代码根 `flowchart/` 初始化 Vite React-TS 项目到 `frontend/`（保留现有目录结构，不覆盖 `pom.xml` 等）。
2. `tsconfig.json` 开启 `strict: true` 及合理 `noUnusedLocals` 等。
3. 接入 ESLint（含 TypeScript 规则），`package.json` 增 `lint` 脚本。
4. 提供最小 `App.tsx`（如一行欢迎文本），确保可渲染。
5. 本地验证：`npm install` → `npm run build`（CI 等价）→ `npm run dev`（空壳可起）。
6. 回到 `run-verify.ps1`：此时 `frontend/` 存在，步骤 2/3 将执行 `npm ci` + `npm run build`；确保该步通过。

## 验收口径
1. `frontend/` 下 `npm run build` 成功（产物生成、无 TS / ESLint 硬错）。
2. `npm run dev` 能启动开发服务器（空壳页面可访问）。
3. `powershell -File run-verify.ps1` 的步骤 2/3 识别到 `frontend/` 并执行通过（与 v2-3 的 PASS 不冲突）。
4. `m0-harness.md` 与 `m6-frontend.md` 状态跟踪里 v2-6 置完成。

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 干净 checkout 跑 npm run build | 构建成功，退出码 0 |
| 异常 | 故意在 tsconfig 写错误类型 | 构建失败，退出码非 0，日志指出错误位置 |

## 完成后
- 状态行改「完成」；
- 移入 `state/completed/`；
- 更新 `plans/masterPlan/m0-harness.md` 与 `m6-frontend.md` 对应状态行；
- `state/activeLog/2026-09-12.md` 追加一行带证据记录。
