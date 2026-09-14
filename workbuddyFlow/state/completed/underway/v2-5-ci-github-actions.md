# v2-5 GitHub Actions CI（云端门禁）

> 来源：`plans/masterPlan/m0-harness.md` 的 v2-5 子计划。
> 说明：本任务未单独在 `plans/underway/` 建工作台副本（一次性"重建 + 真跑"，直接落本文件归档）。

## 元信息
- 任务编号：v2-5
- 状态：完成（2026-09-12）
- 关联模块：`plans/masterPlan/m0-harness.md`（M0 工程底座）
- 创建：2026-09-12 + AI ｜ 完成：2026-09-12 + AI

## 目标
把 `run-verify.ps1` 的三步门禁搬到 GitHub 服务器上：提交即自动跑。补上本地门禁的两个盲区——① 只在你记得跑的时候才跑；② 只在你这一台机器上成立。

## 边界
- 允许新增：`.github/workflows/ci.yml`
- 允许修改：`workbuddyFlow/run-verify.ps1`（补前端 `lint` 一步，使两边对齐）
- 禁止改动：业务包任何文件

## 分层边界
只落在**仓库级 CI 配置层**（`.github/workflows/`）与门禁脚本本身，不进任何 Java 包与前端业务代码。

## 前置
- v2-3 `run-verify.ps1` 可用（三步已成型）—— ✅
- v2-6 `frontend/` 存在（否则前端 job 无对象）—— ✅
- v2-7 `checkstyle.xml` 存在（否则后端第 3 步必红）—— ✅
- `frontend/package-lock.json` 已入库（CI 用 `npm ci` 依赖它）—— ✅
- **本任务暴露的隐藏前置**：以上产物必须**真的进了 git**，见「实测与证据」第 2 条。

## 步骤
1. 勘察现状：确认 `.github` 是否存在、旧配置能否复用 —— ✅ 09-12，见下第 1 条
2. 写 `ci.yml`：两个并行 job（后端 / 前端），三步展开写，不调 PowerShell —— ✅
3. `run-verify.ps1` 补前端 `lint`（与 CI 对齐，避免"本地绿、云端红"）—— ✅
4. 落盘 + 语法校验（js-yaml 解析 + PowerShell 解析器）—— ✅
5. 用户 push，CI 真跑一次 —— ✅ 09-12 17:13，run #2 全绿

## 实测与证据（2026-09-12）

### 1. 勘察结论：不是「修正」，是「重建」
计划原表述「v2-5 GitHub Actions **修正**并真跑」与事实不符：`reframing` 与 `main` **两个分支都没有 `.github` 目录**，旧配置只躺在历史提交 `ecbaa68`（09-09）里。
旧版三处对不上现状：① 前端目录写 `flowchart-frontend/`，现为 `frontend/`；② 触发分支写 `main/master`，而工作在 `reframing`；③ 只跑 `mvn compile` + `mvn test`，**缺第三步 checkstyle**（v2-7 刚做出来，不接进去等于白做）。
→ 已在 `m0-harness.md` 步骤 5 更正表述。

### 2. 门禁阻塞点勘察（差点让首跑必红）
`git ls-files frontend` = **0**——`frontend/` 整个目录、`checkstyle.xml`、`src/test/java/**` 三个测试类、`workbuddyFlow/plans|state` 共 **36 个文件从未 `git add` 过**（上一次提交 `17f107a` 走 IDE 提交面板，未跟踪分组只勾了一部分）。
按当时状态 push，CI **两个 job 必红**：backend 的 `mvn checkstyle:check` 找不到 `${project.basedir}/checkstyle.xml`；frontend 的 `defaults.run.working-directory: frontend` 目录不存在。
→ 用户补齐后提交 `67cacc9`；复核 `git ls-files`：frontend 13 / `checkstyle.xml` 1 / `src/test` 6，工作区零未提交项。
→ **固化教训**：交付后必须用 `git status --porcelain -uall` 核对未跟踪文件，不能只看"提交成功"。

### 3. 真跑结果（run #2）
- run id `34685285222`，分支 `reframing`，commit `67cacc9`，事件 `push`，**success**，09:13:35Z → 09:14:18Z（43s）
- 后端 job 39s：安装 JDK 17（0s，命中缓存）→ `mvn clean test` 25s → `mvn checkstyle:check` 8s
- 前端 job 17s：安装 Node 22 → `npm ci` 5s → `npm run build` 2s → `npm run lint` 1s
- 两个 job **step 级全部 success**，无跳步、无取消
- 对照：run #1（`34323265486`，09-09，`main`@`ecbaa68`）**failure**——旧配置前端目录写错。**#1 红 → #2 绿**即为本次重建有效的证据
- 43s 偏快的原因：09-09 那次 run 的 `setup-java` / `setup-node` 收尾步骤已把 Maven / npm 缓存存下，本次命中（"安装 JDK 17" 耗时 0s 是旁证）

### 4. 已知限制
- **job 日志正文需登录才能看**：`/actions/jobs/{id}/logs` 匿名返回 `403 Must have admin rights`；公开页面只渲染 step 列表、不含日志内容。故"测试是否真跑"须由用户在登录态浏览器核对（本轮已核对：`Tests run: 58, Failures: 0, Errors: 0, Skipped: 0` 与 `You have 0 violations.`）。
- 运行的**状态与耗时**可匿名核查：`api.github.com/repos/{owner}/{repo}/actions/runs` 与 `/runs/{id}/jobs`（含 step 级状态与时间戳）。

## 验收口径
1. `ci.yml` 三步与 `run-verify.ps1` 三步一一对应（文件头注释互标对应表；唯一有意差异：CI 用 `npm ci` 严格按 lockfile，本地默认 `npm install` 增量）—— ✅
2. **CI 每次 push 真跑且绿** —— ✅ run #2 全绿（43s，两 job step 级 success）；日志由用户核对 58 测试 + 0 违规
3. 首跑暴露的问题（36 文件未入库）已闭合 —— ✅ 提交 `67cacc9` 后复核入库

## 覆盖测试数据
| 场景 | 输入 | 期望 | 实测结果 |
|---|---|---|---|
| 正常 | push 到 `reframing` | 两个 job 全绿 | ✅ run #2 success |
| 异常 | 代码含 checkstyle 违规 | 后端 job 红 | CI 未构造；本地 v2-7 变异验证已证（187 列行 → `LineLength` FAIL、退出码 1） |
| 异常 | 前端 lint 报错 | 前端 job 红 | CI 未构造；`--max-warnings 0`，机制同本地 |
| 边界 | 同一分支连续 push | 上一次未跑完的运行被自动取消 | 已配 `concurrency.cancel-in-progress: true`，未构造实测 |
| 边界 | 产物未入库致 job 找不到目录 | 首跑即红 | ✅ 勘察阶段提前发现并修复，未真实发生 |

## 完成后
- ✅ 移入 `state/completed/`
- ✅ 更新 `plans/masterPlan/m0-harness.md` 状态行（M0 全闭合）
- ✅ `docs/architecture.md` 第七节补 CI 小节
- ✅ `docs/Metadata.md` 变更记录登记
- ✅ `state/activeLog/2026-09-12.md` 追加带证据记录
