# 企业级 Context Engineering 文件清单（flowchart 项目现状对照）

> 用途：照企业级标准，列一个完整 Context Engineering 项目该有哪些文件，并标注 flowchart 项目当前具备状态。
> 方法论依据：Obsidian 参照库（四层上下文栈 / 四策略写选压隔 / 四失败模式 / Harness 五层 / Manus·NotionAI·Claude Code 实例）
> 创建：2026-09-22 ｜ **最后核对：2026-09-24（静态层改读 md + 口径收口 + v2-42 落地后）**

图例：
- ✅ 已具备（文件已落盘，内容到位，且**运行时真的读它**）
- 🟨 已具备但**运行时未读**（文件在位、内容也不错，但没有代码去读 → 改它不生效）
- 🟡 设计稿已写，但代码/接入未实现（运行时未生效）
- 🆕 缺失，需新建

---

## 一、静态层 Static Layer（稳定前缀 · KV-cache · 每轮固定携带）

| 文件 | 状态 | 作用 |
|---|---|---|
| `src/main/resources/prompts/whiteboard/system-prompt.md` | ✅ | 静态层主体：角色 / 约束(8 条) / 执行流 / 输出格式 / 能力与边界 / 边界情况。**v5.10.0，4947 字节**。**2026-09-24 起 `PromptService.buildSystemBlock()` 直接读它** + 注入契约 ⇒ "改文本即生效"闭环打通，无第二份文案 |
| `src/main/resources/schemas/scene.schema.json` | ✅(读入) | 契约区（G2 全量 JIT 注入）。`additionalProperties:false` 硬拒非法字段，是 Harness① Limits 的硬约束。⚠️ 由 `PromptService` **当纯文本读入**；后端**不解析**它（`GraphValidator` 是手写校验器）→ 它是坏 JSON 时后端测试照样全绿（2026-09-24 实测踩中并已修复） |
| `src/main/resources/prompts/whiteboard/CHANGELOG.md` | ✅ | 版本变更记录（已记到 **5.9.0**，含每次的 A/B 数据）。属文档侧资产，不进 prompt（但 `PROMPT_VERSION` 常量需与它对齐） |
| `src/main/resources/prompts/prompt-library.yaml` | ✅ | 多版 prompt 注册表（版本 / 用途 / 启用开关），支撑 A/B 与回滚 |
| `src/main/resources/prompts/README.md` | ✅ | prompt 资产总索引与规范 |

> 静态层正文必须完全稳定（版本号等元数据不进正文），否则 KV-cache 失效、token 浪费。v5.7.0 起已删 frontmatter。

## 二、动态层 Dynamic Layer（每轮变 · 用户需求 / 当前场景 / 校验 issues / 修正块）

| 文件 | 状态 | 作用 |
|---|---|---|
| `src/main/resources/context/user-message-template.md` | 🟡 | 动态层骨架 —— **未做**。当前动态层由 `PromptService.buildUserBlock()` **在 Java 里硬拼**（当前场景 / 结构摘要 + 修改规则 + 用户需求） |
| `src/main/resources/context/correction-template.md` | 🟡 | 修正块模板 —— **未做**。当前由 `PromptService.buildFixBlock(issues)` 在 Java 里硬拼 |
| `src/main/java/.../context/SessionContextStore.java` | 🟡 | 动态层数据源（取历史 / 上一轮 issues）—— **未做**。当前 `AssembleInput` 收 `currentSceneJson` + `issues`，由 `GenerationService` 传入 |

> 动态层是 Context Engineering 的"灵魂"。本项目**静态层已资产化（md）**，动态层**仍在 Java 里**（不是可版本化的 md 模板）—— 与静态层当初同一个取舍：**先跑通、后资产化**。

## 三、装配层 Assembler / Serving（拼装静态+动态 · rank 首尾 · fit_token_budget）

| 文件 | 状态 | 作用 |
|---|---|---|
| `src/main/java/.../context/ContextAssembler.java` | ✅ | 拼装器：静态层进 `system` 消息、动态层进 `user` 消息；修正轮把 issues 追加进 user 块；每轮落回放。**v2-41 已落地（2026-09-24）** |
| `src/main/java/.../context/ContextBudget.java` | ✅ | `rank()` + `fit_token_budget()`。当前形态：**固定布局**（system 一块 + user 一块）+ **契约区 HIGH 永不降级白名单**；其余三档无内容可压（M2/M4 未接），刻意不写死代码 |
| `src/main/java/.../context/ContextReplay.java` | ✅ | 每轮回放落 `target/context-replay/`。只存**元数据**（role / 字符数 / token 估算 / zone + `promptVersion`），不存正文 |
| `src/main/java/.../context/AssembleInput.java` | ✅ | 组装入参（7 参 + `Extras` 子记录收 history / memory / toolResults 预留位） |
| `src/main/java/.../client/Message.java` | ✅ | 消息类型（`system` / `user` / `assistant` / `tool` 白名单已放行后两者，供 M4 预留） |
| `src/main/java/.../service/PromptService.java` | ✅ | 静态层正文加载（读 md + 注入契约）+ 动态层拼装 + **v2-42 大图增量投喂**（`EDIT_INLINE_MAX_ELEMENTS=30`，超阈值走结构摘要 / 被改元素全文 / 引用骨架） |
| `src/main/java/.../context/PromptVersion.java` | 🆕 | 版本号读取 —— 未做。当前版本号是 `GenerationService.PROMPT_VERSION` **常量**（`"v5.10.0"`，已与 `CHANGELOG.md` 最新一条对齐，但仍是硬编码、非从文件读） |
| `assistant` 前缀 prefill `{` | 🆕 | 未做。在 user 后追加一条 assistant 前缀消息仅 `{`，强制续写合法 JSON，从机制上根除围栏问题 |

> 装配层**已从"设计稿"变成"运行时能力"**（v2-41）。静态层已闭环"改文本即生效"；动态层仍落在 Java。

## 四、治理层 Harness（Limits / Instructions / Checks / Recovery / Review）

| 文件 | 状态 | 作用 |
|---|---|---|
| `src/main/java/.../graph/GraphValidator.java` | ✅ | 确定性门禁①：契约硬校验（必填字段 / relation 枚举 / 数值非字符串 / **自环 / 绑 frame / 两式二选一 / line 绑端点 / group 白名单**）。**已在 M1 交付**（2026-09-24 按 md 口径把 5 条校验从"放开"收回） |
| `src/main/java/.../graph/SceneDrift.java` | ✅ | 确定性门禁②：编辑保真（未点名元素几何不得变，G7）。**已在 v2-37 交付** |
| `src/main/java/.../context/validators/AestheticValidator.java` | 🆕 | 确定性门禁③：美观检查（frame 不越界 / G9 frame 不上色）—— 未做。当前仅有离线探针 `tools/e2e/probe_frame_audit.py` |
| `workbuddyFlow/plans/masterPlan/m14-evaluation.md` | ✅🟡 | 评估回归门禁（Harness⑤ Review）：A/B 对照 `promptVersion`，设计稿已写、未实施 |
| `.workbuddy/ab/cases.json` | ✅(测试) | 24 条多样化测试集（规范 12 + 野路子 12），黄金集雏形，不进 jar |
| `.workbuddy/ab/run_ab.py` | ✅(测试) | A/B 生成 + 量化分析 + 截图渲染驱动，不进 jar |
| `.workbuddy/ab/cases_adv.json` + `adv_battery.py` | ✅(测试) | **2026-09-24 新增**：20 组刁难 / 奇葩数据电池（强制自环 / 绑 frame / 两式混给 / line 绑定 / text 加 group / dots·dashed·diagonal 填充 / 超大复杂图），跑的是"新运行时提示词 + 真实 LLM 输出"，不进 jar |

> Harness 是企业级与"玩具 prompt"的分水岭。本项目两道确定性闸门（契约 / 保真）已在流水线里真生效。

## 五、文档 / 索引（项目地图）

| 文件 | 状态 | 作用 |
|---|---|---|
| `workbuddyFlow/AGENTS.md` | ✅ | 项目地图（索引 + 四拍 + 状态机），跨会话不失忆入口 |
| `workbuddyFlow/plans/underway/m10-static-layer-design.md` | ✅ | 上下文架构设计（静态/动态分离、rank、fit_token_budget、四失败模式、方法论映射） |
| `workbuddyFlow/state/completed/underway/v2-41-context-assembler.md` | ✅ | 装配层接入方案 + **收尾记录（实际落地形态与设计的 7 处差异）**。2026-09-24 已交付并归档 |
| `workbuddyFlow/plans/underway/m10-system-prompt-draft.md` | ✅ | 白板 system prompt 草稿状态文档 |

---

## 关键结论（2026-09-24 下半场更新）

1. **静态层闭环了** —— 2026-09-24 下半场：`PromptService` 改为**直读 `system-prompt.md`**，Java 硬编码正文删除 ⇒ `prompts/` 下的 markdown **首次被代码真正读取**。同时把与后端矛盾的 5 条规则按 md 收紧（后端改），坐标语义一条反向改 md。测试 184 通过 / 0 失败，checkstyle 0 违规。
2. **缺口收缩为 2 类**：
   - **资产化（仅剩动态层）**：动态层模板仍是 Java 字符串（`buildUserBlock` / `buildFixBlock`），改一次要改代码、也无法按版本 A/B。
   - **收尾项**：`PromptVersion`（版本号读取，现为硬编码常量 `v5.10.0`）、assistant prefill、`promptVersion` 进生成日志、美观检查器、动态层模板化。
3. **资源层与治理层不欠账** —— 契约 / CHANGELOG / 版本注册表 / 两道确定性门禁（契约 + 保真）/ A/B 测试集 + 20 组刁难电池都在。
4. **落地优先级建议**：
   - P1：`PromptVersion` 从 `CHANGELOG.md` 或独立版本文件读（替掉硬编码常量）+ `promptVersion` 写进生成日志（= v2-43 剩余部分）。
   - P2：动态层模板资产化（`user-message-template.md` / `correction-template.md`）；补 `AestheticValidator`。
5. 按红线：`git` 写操作由用户执行；本轮代码改动系用户当轮明确授权。
