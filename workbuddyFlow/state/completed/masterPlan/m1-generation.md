# 模块计划 · 核心生成链路（S1 看得见）

> 状态：**完成**（2026-09-18 S1 闭合；v2-8 ~ v2-11 + v2-32 ~ v2-37 全部交付）
> 归档：2026-09-18 由 `plans/masterPlan/` 移入 `state/completed/masterPlan/`（原始内容未回改）
> 完成结论：NL → IR → 带坐标 IR → 校验 → SSE 下发全链路闭合。终态证据：后端 `clean test` **116 例**全绿 + checkstyle 0 违规；真实大模型端到端（`workbuddyFlow/tools/e2e/e2e_real_llm_layout.py`）出图零重叠、零 NaN、边标签不盖线，且编辑模式冻结既有坐标；详见 `state/activeLog/2026-09-18.md`
> 关联架构：`architecture.md` 2.4 包表（`controller` / `service` / `llm` / `graph`）、ADR-3 / ADR-4
> 所属阶段：**S1 看得见**（与 M6a 同阶段交付；执行序见 `plans/masterPlan/roadmap.md`）
> 关联前置：`state/completed/underway/m1-pre-cleanup.md`（旧链路代码清算，已完成）
> 覆盖任务：v2-8 ~ v2-11（S1 主干）+ v2-32 ~ v2-35（补记，见文末补记节）+ **v2-36**（后端分层布局，2026-09-17/18）+ **v2-37**（几何保真校验，2026-09-17）

## 元信息
- 模块编号：M1
- 状态：完成
- 创建：2026-09-11 + AI
- 归档：2026-09-18（S1 闭合，模块整体完成）

## 目标
把"自然语言 → 结构化图 IR"的主链路做扎实：契约固化、端到端校验、生成→校验→修正闭环、SSE 进度协议定型；并交付**最小会话上下文（内存版）**，使 `/api/chat` 的多轮对话成立（Redis 持久化归 M5）。

## 边界
- 允许新增：`schemas/` 目录、`GraphValidator`、SSE 事件类型定义、`session/` 包（**仅内存实现**：`SessionStore` 接口 + 内存实现 + `PUT /api/model/canvas` 画布现状同步入口；Redis 版归 M5）
- 允许修改：`service` 生成编排、`controller` 接口、`llm` 调用
- 允许删除：与目标架构无关的旧链路代码（前置清算已完成，见关联前置文件）
- 禁止改动：前端渲染层（属 M6a / M6b）；`session/` 只允许新增内存实现，**不得引入 Redis 或持久化**（归 M5）

## 分层边界
落在 `controller` / `service` / `llm` / `graph` 包，并新增 `session/` 的**内存实现**；不触碰 `rag` / `tools` / `agent`（后续阶段）。原因：本模块只解决"单次出图正确 + 多轮上下文"，不引入检索 / 工具 / Agent，也不做持久化。

## 前置
- M0 v2-3 run-verify 可用
- 后端基线可编译
- 旧链路已清算（2026-09-14）：`static/`、`DiagramService`、`plantuml` 依赖、`/api/generate`、`/api/download`、两个旧请求 record 均已删除

## 步骤
0. 前置 · 旧链路代码清算（**已完成** 2026-09-14，详见 `state/completed/underway/m1-pre-cleanup.md`）
1. v2-8 出图 JSON Schema 契约固化（路线2 元素场景：`resources/schemas/scene.schema.json`，prompt 组装与解析共用同一份契约）
2. v2-9 `GraphValidator`：端到端校验，issue 带 `field / reason / hint`
3. v2-10 生成 → 校验 → 修正循环（最多 3 轮，修正 prompt 携带 issues 重调）
4. v2-11 SSE 进度事件协议定型（事件类型文档化，前后端共同遵守）
5. v2-11 附带交付：**最小会话上下文**（`SessionStore` 接口 + 内存实现 + 坐标回写入口），支撑多轮对话（Redis 持久化归 M5）

## 验收口径
1. 任意合法 NL 输入产出 100% 符合 Schema 的 IR，否则带 issues 重试
2. 校验失败明确定位 `field` 并给 `hint`，不只报数量
3. 连续 3 轮不通过 → 以 `error` 事件收尾并附 issues 摘要（原措辞「自动升级人工 / 记 tech-debt」已失去载体：`tech-debt-tracker.md` 于 2026-09-11 删除，改为「如实报错 + 摘要」，见 2026-09-17 A3）
4. 前端能按定型后的 SSE 事件类型解析进度
5. 第二轮对话（"再加一个节点"）后端能取到当前图上下文；`PUT /api/model/canvas` 可把画布现状（坐标 / 删除 / 手绘 / 解绑）回写内存 model
6. 单次生成的总耗时受 `TOTAL_BUDGET_MS` 约束：预算耗尽必须在 SSE 通道被容器掐断**之前**主动发 `error` 收尾；不允许出现"流被静默截断、前端毫无提示"（2026-09-16 补，见下方缺陷节）

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | "画一个登录流程图" | 合法 IR，校验 0 issue |
| 异常 | 让 LLM 返回缺字段的 JSON | 校验报 field 级 issue，带 hint |
| 边界 | 返回自环边 / 孤立节点 | 校验识别并给 reason |
| 多轮 | 第二轮说"再加一个节点" | 后端从内存 session 取到当前 model，返回**全量** `result`（S1 只走全量；增量 `ops` 归 S3 / v2-25） |

## 验收期缺陷与修复（2026-09-16）
| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 复杂图（约 30 个元素）生成后画布空白；后端日志 `AsyncRequestTimeoutException`，随后生成线程 `IllegalStateException: ResponseBodyEmitter has already completed` 裸崩 | ① SSE 通道上限写死 `120_000L`，而实测**单轮 LLM 就要约 100 秒**、代码却允许最多 3 轮 → 第 2 轮必然越界 ② `llm.timeout-seconds: 60` **形同虚设**：JDK 请求计时在收到响应首字节后停止，实测 `ttfb=8.2s` 而 `total=97.7s`，60 秒闸门从未触发 ③ 通道被掐断后代码继续 `send`，`IllegalStateException` 打穿线程 ④ 前端流读完未收终态事件却无任何提示 | ① 通道上限改由 `GenerationService.TOTAL_BUDGET_MS`(420s) 推导（恒等对齐，消灭魔数）② 新增 `planRound(deadline)`：单轮上限 = `min(300s, 剩余预算 − 20s 收尾)`，不足 10s 直接带原因收尾 ③ `LlmProvider.chat(prompt, timeoutSeconds)` + `OpenAiCompatibleProvider.sendAsync().get()` 加 Future 层真·总时限（覆盖"连接 + 响应头 + 正文"）④ `generate()` 与 `sendEvent`/`sendError` 增补兜底（真实异常类型是 `IllegalStateException`）⑤ `App.tsx` 以 `settledRef` 判定终态，缺失时显式提示"生成中断" |

证据（端到端，新代码独立端口实测）：同一句"计算机组成原理架构图"需求 `thinking`(0.11s) → `validation`(140.15s) → `result`(140.17s)，**25 个元素成功返回**；`Exception in thread` 与 `AsyncRequestTimeoutException` 计数均为 0。**140 秒 > 旧上限 120 秒** —— 旧代码必炸、新代码通过，构成因果闭环。

| 2 | 报错后**界面永久卡死**：错误气泡已显示，但输入框与发送按钮一直 `disabled`，只能刷新页面（实测卡死 280 秒以上） | `sendError` 用 `emitter.completeWithError(...)`。错误内容已作为 `error` 事件推给客户端，通道层再报错会让容器走 ERROR dispatch；而响应头已是 `text/event-stream`，找不到能写 SSE 的 `HttpMessageConverter`，日志实证抛 `HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream'` → 响应既不收尾也不断开 → 前端 `reader.read()` 永远等不到 `done` → `isLoading` 永为 true | ① 后端 `sendError` 改 `complete()`（错误已由 error 事件表达）；测试加锁 `verify(emitter, never()).completeWithError(any())` ② 前端收到终态事件即 `await reader.cancel()` 退出读循环，不再依赖服务端收尾 ③ 单轮上限 150s→300s、总预算 280s→420s（SSE 上限自动推导为 440s）④ `ChatPanel` 加纯前端等待计时（"已等待 N 秒"），不引入服务端心跳 |

复杂图实测（2026-09-16，真实浏览器 + 真实 LLM，均一次通过无重试）：**47 元素**的微服务架构图（LLM 50.3s，`canvasNonWhite=37391`）、**41 元素**的带分支下单支付流程图（LLM 111.0s，`canvasNonWhite=9880`）；两次 `bounds` 均有限、`nonFiniteXY/nonFiniteWH` 均为 0、浏览器错误为空、后端 `Exception` 计数 0。**并发生成会显著互相拖慢**（同一账号吞吐共享）——同一需求单独跑 50s，与另 2 个请求重叠时超过 150s。

## 状态跟踪
- [x] 前置 · 旧链路代码清算（2026-09-14）
- [x] v2-8 JSON Schema 契约（2026-09-14 落盘 scene.schema.json + ADR-4 重写）
- [x] v2-9 GraphValidator（2026-09-15，手写校验器替代 networknt）
- [x] v2-10 生成→校验→修正循环（2026-09-15，GenerationService ≤3 轮）
- [x] v2-11 SSE 事件协议 + 内存会话上下文（2026-09-15，SessionStore + DiagramController SSE）
- [x] 补记 v2-32 `GraphValidator` 字段级校验补全（2026-09-16，见文末补记节）
- [x] 补记 v2-33 `SceneBinder` 吸附归一化 + 编辑模式 prompt（2026-09-16）
- [x] 补记 v2-34 连线去重 + 画布现状同步 `PUT /api/model/canvas`（2026-09-16）
- [x] 补记 v2-35 Bug A：只有用户手绘时把场景提升为"可编辑"（2026-09-17）
- [x] **v2-37** 编辑模式几何保真校验 `SceneDrift`（2026-09-17，证据见 `state/activeLog/2026-09-17.md`）
- [x] **v2-36** 后端分层布局 `SceneLayout`（2026-09-17/18，新建全量重排 / 编辑只给新增定位；证据见 `state/activeLog/2026-09-18.md`）→ 归档件 `state/completed/underway/v2-36-layout.md`

## 补记：v2-32 ~ v2-35（2026-09-17 归档补录）

> 为什么有这一节：这四项在**代码里有编号**（javadoc / 注释里写着 v2-32…v2-35），但 `plans/masterPlan/`、`plans/underway/`、`state/completed/`、`state/activeLog/` 全都查不到 —— 按现行规则（归档以**计划文件**为单位）它们**永远归不了档**。
> 补录的做法：不为已完成的旧工作伪造计划文件，改为在所属模块计划里如实登记"哪个编号交付了什么、证据在哪"。编号与内容按代码注释的表述回填，不做追认性美化。

| 编号 | 交付内容 | 代码位置 | 证据 |
|---|---|---|---|
| v2-32 | `GraphValidator` 字段级校验补全：`label` 必须是 `{text}` 对象、points 每点两个数字、枚举 / 百分比 / 最小值合法、id 唯一、箭头端点引用完整性（实现改两遍扫描：先收 id 全集再逐元素校验） | `graph/GraphValidator.java` | activeLog 2026-09-16（`GraphValidatorTest` 6 → 14 例） |
| v2-33 | `SceneBinder` 吸附归一化（两端都落在图形上的两点箭头升级为绑定式）+ `PromptService` 编辑模式（把当前场景塞进 prompt，"删掉某节点"不再被理解成"重画一张"） | `graph/SceneBinder.java`、`service/PromptService.java` | activeLog 2026-09-16 |
| v2-34 | `SceneDeduper` 连线去重（丢掉与用户手绘重复的线、LLM 自画的重复线）+ **画布现状同步** `PUT /api/model/canvas`（positions / removedIds / userElements / unboundArrows 四类一起回写，取代只回写坐标的 `PATCH /api/model/positions`） | `graph/SceneDeduper.java`、`model/CanvasSyncRequest.java`、`controller/DiagramController.java`、`session/` | activeLog 2026-09-16 |
| v2-35 | Bug A 修复：会话里只有用户手绘（`elements` 为空、手绘在 `_userElements` 旁路）时，把用户手绘提升为"当前场景"让 LLM 就地修改，落库时清掉旧旁路（`buildSeedScene` + take-over）；前端对应的场景合并与视口取景修复记在 M6a | `service/GenerationService.java`、`frontend/src/components/Canvas.tsx` | activeLog 2026-09-17 |

## v2-37 编辑模式几何保真校验（2026-09-17，S1 收尾）

- **缺口**：`PromptService` 已向 LLM 承诺"没有要求改动的元素必须原样保留 id / type / 几何"，但**没有任何一层在守这条承诺** —— `GraphValidator` 只看单元素字段合法性，`detectRewrite` 只看"整图 id 是否全被换掉"，于是"保留 id、却把坐标整体重排"这条路径无人管。
- **为什么必须先于 v2-36**：编辑模式一旦走进"全量重排"，用户亲手拖出来的排布会在他说"改个颜色"的时候被抹掉 —— 布局落地前，这条承诺必须先变成可执行的校验。
- **交付**：新增 `graph/SceneDrift`（只比对、不改数据），在 `GenerationService` 编辑语境第一轮接入（与 `detectRewrite` 并列）。
- **判据（保守优先，宁可漏报不误报）**：两版都存在的可比元素 ≥ 3 个、其中漂移 ≥ 3 个且占比 ≥ 50%，才判"整图重排"；**整体平移（位移向量一致且尺寸未变）放行**；两端都绑定的箭头（几何由端点推导）与 `freedraw` 不参与；坏 JSON 交给 `GraphValidator` 报，不重复告警。
- **证据**：`SceneDriftTest` 14 例 + `GenerationServiceTest` 新增 2 例（整图重排触发修正轮 / 整体平移不触发）；`mvn clean test` **Tests run: 102, Failures: 0, Errors: 0** + `checkstyle` 0 违规。详见 `state/completed/underway/v2-37-edit-fidelity.md`。
