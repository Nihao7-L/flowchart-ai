# M10 系统提示词工程（白板 AI）

> 状态：**已完成 · 2026-10-10 归档**（v2-41 / v2-42 / v2-43 全部交付；模块整体移入 `state/completed/masterPlan/`）
> 模块归属：**M10 上下文工程 → S2 静态层（系统提示词）**
> 前置：v2-40（契约容量扩展）已归档
> 依据：`../../docs/constraint.md`（两道闸门红线）· `src/main/resources/prompts/whiteboard/system-prompt.md`（**唯一真相源，v10.2.0 / 7462B**）· `plans/masterPlan/m10-context-engineering.md`（模块计划，含「落地记录」）

## 〇、归档说明（2026-10-10）

本文件此前停在 09-24 的 v5.10.0 / 4947B / 184 测试，实际工作已推进到 10-10 的
**md v10.2.0 / 7462B / 266 测试** —— 即「**做完了没归档**」，而非「卡在进行中」。
同日完成 v2-43 收尾（版本号治理）与账本对齐：

| # | 事项 | 结果 |
|---|---|---|
| 1 | `promptVersion` 从 md 治理头**现读**，删掉硬编码常量 | ✅ `PromptService.promptVersion()` + `GenerationService.promptVersion()` |
| 2 | 补 CHANGELOG 漏记段（v9.0.0~9.0.4 / 10.0.0 / 10.1.0 / 10.2.0） | ✅ 2026-10-10 按工作日志与治理头如实补记 |
| 3 | 新增 3 条守卫测试锁死版本号不许撒谎 | ✅ `PromptServiceContractTest` 13 例全绿 |
| 4 | 本文件 + masterPlan 状态行更新、模块归档 | ✅ |

**新增守卫（防止再犯）**：`promptVersionComesFromGovernanceHeader`（版本号必须真从 md 读出、且等于治理头值）·
`changelogCoversCurrentPromptVersion`（CHANGELOG 必须记到当前版）·
`changelogLatestEntryIsCurrentVersion`（CHANGELOG 最新条目必须就是当前版）。
先写测试再补 CHANGELOG，实测确实先红后绿 —— 守卫有效，不是摆设。

> 判据「不许再犯」：这三条任一触发，都说明**改了 md 忘了同步账本**，
> 也就是 v8.0.1 那种「安静地记错」的前兆。

## 一、要解决的问题

运行时 `PromptService.buildPrompt()` 曾硬编码旧版提示词（角色=图表生成助手、7 种类型、禁 freedraw），
与 09-23 起确立的白板 AI 定位（角色=白板 AI 助手、11 种类型）严重脱节。
处置分三步：① 重写一份经过 A/B 验证的白板版提示词；② 用 `v2-41 ContextAssembler` 把"每次调模型前拼什么"从 `StringBuilder` 升级为 `system` + `user` 双消息组装；③ 把静态层正文的来源**收敛到唯一一份 md**，并让它与后端校验口径完全一致。

## 二、已完成的里程碑

### 白板定位反转（2026-09-23）

- ChartFlow 从"图表生成器"正名为**白板（whiteboard）AI**：Excalidraw 能画能写都算（图表/手绘/笔记/图文混排），"写"是一等公民。
- **已同步进所有项目文档**：`README.md`、`AGENTS.md`、`Metadata.md`、架构文档、`scene.schema.json` 标题。
- **前端 `sceneAdapter` 白名单已补**（工作树未提交）：`KNOWN_EXCALIDRAW_TYPES` 含 11 类 + freedraw 取景边界 NaN 修复 + magicframe→frame 降级 + `completeBaseElements` 补基类字段。
- **当日全量验证通过**：后端 166 测试 0 fail、前端 17/17、tsc 通过、`scene-smoke.mjs` 17/17 PASS。

### 参照库建设（2026-09-22）

- 建立 Obsidian 企业级上下文参照库：39 份上线产品 system prompt + 官方方法论，全量译中、Mermaid 图解、图谱连通。
- 关键发现：工业级用**容器标签**硬分离静态/动态（Claude Code `<env>`+`<system-reminder>`）；无大厂在 prompt 里写 step1→step5 思考流程。

### 系统提示词重写 v2.0→v5.10.0（2026-09-22~24）

正文文件：`src/main/resources/prompts/whiteboard/system-prompt.md`（工作副本同步在 `.workbuddy/prompts-draft/ChartFlow-白板AI-system-prompt.md`，两份逐字节一致）

| 版本 | 关键变更 | 体积 |
|---|---|---|
| v2.0 | 参照库方法论重写，XML/Markdown 分区 | 13090B |
| v2.2.0 | 修"画老虎被拒"，freedraw 升级 | 13090B |
| v3.0→v4.0 | 按用户模板八段重写 | 8590B |
| v4.1→v4.2 | 修契约硬伤（箭头对象绑定）、补语气段 | 8254B |
| v5.0 | **新策略：除系统错误外永远有产出**（不再输出 error 对象） | 9897B |
| v5.1 | 恢复 error 对象（仅系统错误时）、可读性修正 | 11156B |
| v5.2→v5.3 | 目标重定位 + 四项去重瘦身 + 逐句压缩 | 4500B |
| v5.4 | 全文去重（消灭 8 组重复） | 3615B |
| v5.5 | +3 条最致命护栏 + 继续精简 | 3268B |
| v5.6 | **11 条护栏全补**（5 硬护栏 + 6 编辑规则） | 3271B |
| v5.7 | **压到 1500 token（实测 1487）** + 阈值拍板 15 | 2293B |
| v5.8 | 按用户手改增量修订：**8 条约束**（含 **freedraw 默认禁**），删掉与执行流重复的「目标」「工具与权限」两节 | 2382 字符 |
| **v5.9** | **口径收口**：约束 4 坐标语义改为「最终落点、后端不重排」（见下）；其余 5 条冲突由后端代码承担 | 4648 字节 |
| **v5.10** | **刁难电池修复**：边界情况新增「要求违反契约」档（不照做→画合法最近似结构 + 【AI 备注】text；禁返回空 elements） | 4947 字节 |

### A/B 验证（2026-09-23~24）

- **真·A/B（48 调用 + 48 截图）**：渲染层字段泄漏 3→0、箭头绑 frame 8→0、主动拒绝 0→8。
- **v5.7 10 例 A/B**：element OLD=102/NEW=124；平均耗时 50.3s/37.9s；G8/G9/G10 全 0；NEW 在野路子兜底远优于旧版。
- **v5.7 20 例奇葩 A/B**：element OLD=210/NEW=181；g4 渲染层字段泄漏 7→0；freedraw OLD=0/NEW 最多 12/例。
- **v5.8.0 修复重测**：P0 算大数翻车 0→4；越界任务硬猜示意生效。

### v2-41 上下文组装落地（2026-09-24）

- 新增 `client/Message.java` + `context/` 四文件（`AssembleInput` / `ContextBudget` / `ContextReplay` / `ContextAssembler`）；`PromptService` 拆出 `buildSystemBlock()` / `buildUserBlock()` / `buildFixBlock()`；`GenerationService` 改注入 `ContextAssembler`、调 `chat(List<Message>, int)`。
- **只搬家、不改字**：新旧两边的双引号字面量逐条比对 = `76 : 76，MISMATCH 0`。
- 验证：`mvn -o test` = **180 通过 / 0 失败**（原 175 + 新增 5）。
- 计划已归档 `state/completed/underway/v2-41-context-assembler.md`；实际落地形态与计划原描述的差异见 `plans/masterPlan/m10-context-engineering.md`「落地记录」。

### 静态层收敛到唯一 md + 口径收口（2026-09-24 下半场）

- **运行时改读 md**：`PromptService.buildSystemBlock()` 直接读 `system-prompt.md` + 注入契约；**Java 硬编码那份文案整体删除**（从此只有一份正文，不存在漂移）。md 的 relation 清单用 `PromptServiceTest` 守卫 `RelationCatalog.ALL_RELATIONS`。
- **口径反转 5 条**（md 为准 → 后端收紧）：自环判非法 / 禁绑 frame / start+end 与 points 互斥 / line 禁绑端点 / group 只贴图形。同步改了 5 个反向断言的老测试。
- **坐标语义反向修 md**（唯一"不能按 md 收紧后端"的一条）：md 约束 4「坐标给近似值（后端重排）」→「坐标为最终落点，后端不重排，请按最终效果摆放、彼此别压住」。理由：后端 v2-41 起确实不重排，且**同一份 system 消息里紧跟 md 的契约 `scene.schema.json` 描述早已写明"v2-41 起后端不再整体重排"**——原文案让 md 与契约自相矛盾。
- **死字段清理**：`GenerationService.sceneLayout`（`LayoutEngine`，只赋值不读）移除，构造器与测试同步。
- 验证：`mvn -o test` = **181 通过 / 0 失败**；checkstyle **首次 0 违规**（门禁转绿）；`tsc --noEmit` 0；冒烟 17/17。

### v2-42 编辑模式增量（2026-09-24 下半场）

- `PromptService.EDIT_INLINE_MAX_ELEMENTS = 30`；编辑模式元素数**超过** 30 时，user 块不再整份贴场景，改「**结构摘要 + 被改元素全文 + 引用骨架**」。
- 骨架带 `id / type / 几何 / label` ⇒ 输出侧「未点名元素原样保留」仍有依据，**无需后端做合并**。
- 小图（≤30）路径文案逐字节不变，零漂移。
- 验证：`mvn -o test` = **184 通过 / 0 失败**（+3 例 `PromptServiceTest`）。

## 三、当前状态（2026-09-24 下半场核对）

| 物件 | 状态 |
|---|---|
| **运行时静态层正文来源** | ✅ **唯一一份** = `src/main/resources/prompts/whiteboard/system-prompt.md`（`PromptService.buildSystemBlock()` 直读 + 注入契约） |
| **canonical 文件** `system-prompt.md` | **v10.2.0 / 7462 字节**（2026-10-10 核对；此前本表记的 v5.10.0 / 4947B 已过期 12 天） |
| **提示词 CHANGELOG** | `src/main/resources/prompts/whiteboard/CHANGELOG.md`，最新 **10.2.0**（2026-10-10 补齐 v9/v10 漏记段） |
| **运行时版本号** | ✅ **不再硬编码**：`PromptService.promptVersion()` 从 md 治理头现读（治理头缺失则回退 `unknown` + WARN，不抛异常） |
| **运行时 Java 文案** | ✅ **已删除**（不再存在第二份正文，口径冲突从源头消失） |
| **上下文组装** | **v2-41 已落地**：`system` + `user` 双消息；大图编辑走 v2-42 紧凑块 |
| **前端 sceneAdapter 白名单** | 已含 11 类（含 freedraw）；另有 10-10 修的 `bindContainerTexts` 等三处渲染缺陷 |
| **契约文件 `scene.schema.json`** | 2026-09-24 修过一处坏 JSON；10-10 冒烟 **22/22** |
| **门禁（2026-10-10 实跑）** | 后端 **266 通过 / 0 失败** · **checkstyle 0 违规** ·（tsc / scene-smoke 为 10-10 当日口径，本轮未重跑） |

### 口径冲突 → ✅ 全部收口（2026-09-24）

**用户拍板：以 `system-prompt.md` 为准。** 6 条的最终归属：

| # | md 约束 | 落地动作 | 谁改 |
|---|---|---|---|
| 1 | 自环箭头（约束 3） | 重新判非法（v2-41 曾放开，现收回） | 后端 |
| 2 | 箭头绑 frame（约束 3） | 重新判非法（v2-41 曾放开，现收回） | 后端 |
| 3 | arrow 的 start/end 与 points（约束 3） | **两式都给也判非法**（v2-41 曾允许共存，现收回） | 后端 |
| 4 | line 只作装饰分隔线（约束 7） | line 的 start/end 绑定判非法 | 后端 |
| 5 | group 只能贴矩形/椭圆/菱形（约束 6） | 非图形元素带 group 判非法 | 后端 |
| 6 | **坐标语义（约束 4）** | 原文「给近似值（后端重排）」**改为**「最终落点、后端不重排」 | **md** |

> 第 6 条为什么反着改：v2-41 已主动移除后端整体重排（`GenerationService` 只做 `claimFramesOnly`，不动几何），
> 而契约 `scene.schema.json` 的描述**本来就写着"v2-41 起后端不再整体重排"** —— 同一份 system 消息里
> md 说"会重排"、契约说"不重排"，模型只能二选一。恢复重排又会把图收成窄条，所以只能改 md。
>
> 代价记档：第 1 条让**状态机自循环**这个合法能力暂时画不出来（需自循环可改用 points 画回原点）；日后若要，单独放开即可。

## 四、待解决

### P0 — 阈值 15 同步 → ✅ 已完成（2026-09-24）

5 处全部同步：`ScenePlanner.MODULE_THRESHOLD` / `scene.schema.json:60` / `frontend/src/types.ts:120` / canonical `system-prompt.md` / 测试注释；grep 复核无残留 `12`。

### P1 — freedraw 白名单 → ✅ 已完成（09-23 落盘，`sceneAdapter.ts` 约 850-874）

### P2 — 接入运行时 → ✅ 已完成（2026-09-24 两轮）

第一轮（v2-41）：升级为 `system` + `user` 双消息组装。
第二轮：**静态层正文改读 md**（`system-prompt.md` 为唯一来源），Java 硬编码文案退役。

### P3 — A/B 发现的缺陷

| # | 严重度 | 问题 | 状态 |
|---|---|---|---|
| 1 | P0 | q07 算大数 NEW=0（过度保守） | ✅ v5.8.0 已修（0→4） |
| 2 | P1 | 越界任务（写诗/代码/天气）退化成 1 个 text | ✅ v5.8.0 已改为「text + 硬猜最简示意」 |
| 3 | P1 | freedraw 未禁止，单例最多 12 个 | ✅ v5.8.0 已改为「freedraw 默认禁」 |
| 4 | P2 | c05 架构图 12→8 元素疑似退化 | 待看截图判定 |
| 5 | P1 | **刁难电池**：强迫违规（自环/绑frame/两式混给）时模型返回**空 elements** → 空白画板 | ✅ v5.10.0 已修（复测 3/3 转合法非空） |
| 6 | P2 | 超大复杂图（大型状态机 / 多层组织架构）**单次超时** >360s | 记档待观察（LLM/网络单次上限，非校验问题） |

## 五、下一步

**M10 模块本身已收尾**（2026-10-10）。后续事项都不属于 M10：

1. **提交**：所有改动仍在工作树未提交（`git` 写操作由用户执行）
2. **技术债（v2-41 遗留，可选）**：`ContextAssembler` 位于 `context` 包却注入 `service.PromptService`，
   属包级反向依赖。原计划「M10 收尾时把 `PromptService` 迁入 `context/`」——
   本轮判断**不做**：包名归属是纯结构调整，改它要动 import 全树（`GraphValidator` 等），
   收益为零而回归面不小，留到有实际动因时再动。
3. **未完成的旁支（不属于 M10）**：v2-43 原计划的「独立生成日志落点」至今 grep 零命中，
   `promptVersion` 只进回放日志。M7（可观测性）激活时一并解决更合适。
