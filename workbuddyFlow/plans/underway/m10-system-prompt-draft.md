# M10 静态层 · System Prompt 企业化方案

> 状态：**v4.0.0 已按用户给定模板（八段骨架）重写（2026-09-23）** —— `system-prompt.md` 已重写为 v4.0.0（角色 / 目标 / 语气与风格 / 输出格式 / 能力与边界 / 规则 / 工具与权限 / 边界情况，逐段对齐模板并补齐错误对象、工具与权限、知识截止等此前缺失的槽位）；**仍未被 Java 引用，运行时继续用 `PromptService` 硬编码旧 prompt，项目行为零变化**
> 草稿位置：`src/main/resources/prompts/whiteboard/`（设计稿，非运行时；打磨合格后再由 v2-41 装配接入生效）
> 关联：M10 上下文工程 / 静态层；参考样例 `F:\ProgramData\Workbuddy Data\Workbuddy\2026-09-19-13-48-35\prompts`
> 创建：2026-09-21 · 回退：2026-09-22

## 〇、当前状态：v4.0.0 已按用户模板重写（2026-09-23）

**决策**：用户给定一份 system prompt 骨架（八段：角色 / 目标 / 语气与风格 / 输出格式 / 能力与边界 / 规则 / 工具与权限 / 边界情况）+ 合规自检清单 + 技巧表，要求「按这个来」。已逐段对齐填充重写——v3.1.0 的自行增删章节不再适用。

- `system-prompt.md` → **v4.0.0**（八段严格对齐、顺序一致；85 行 / 5.6KB；规则由 8 条收敛为 6 条）
- **相对 v3.1.0 的主要变化**：补 `# 语气与风格`；补 `# 工具与权限`（原被当噪声删除，现写实为"无工具 / 无写操作 / 数据边界"）；补 `# 能力与边界` 的"知识截止"槽位；`# 输出格式` 增**错误对象 schema** + 越界示例（示例即期望行为正例，含"不硬画"）

**模板合规自检**（逐项对照用户清单）：

| 自检项 | 状态 | 落点 |
|---|---|---|
| 角色是否具体（非 helpful assistant） | ✅ | 「ChartFlow 白板的 AI 绘图助手」+ 一句话职责定位 |
| 目标能否写成 输入→输出→可量化质量标准 三行 | ✅ | `# 目标`（含 5 条量化指标） |
| 七/八段是否齐全 | ✅ | 八段全、顺序与模板一致 |
| 约束是"说该做什么"还是只堆"禁止什么" | ✅ | `# 规则` 6 条均为正面表述（"该做的一个不漏，不该加的一个不加"） |
| 输出格式是否用 schema/模板锁死 + 错误对象 | ✅（⚠ 前置条件见下） | `{{SCENE_SCHEMA}}` 全量注入 + `{"error":{...}}` 三码枚举 |
| 是否有 1–3 个示例且与期望行为一致 | ✅ | 2 例：正常流程 + 越界澄清（不含"硬凑一张图"这类不想鼓励的模式） |
| 是否明确允许"我不知道"，并给澄清/升级路径 | ✅ | 规则 6 + `# 边界情况`（含糊 → 给 2–3 个具体方向让用户挑） |
| 工具与权限是否写明 / 高风险动作二次确认 | ✅ | `# 工具与权限`（无工具、无写操作 → 不存在需二次确认的动作） |
| 是否**没**放任何凭据、内部规则、权限结构 | ✅ | 无密钥、无内部工程编号（v3.1.0 已删 c12/c09/A-B 等编号） |
| 长度是否精简（有无可删空话） | ✅ | 85 行 / 5.6KB，单段单主题、无跨段重复 |
| 是否有版本号 + 变更日志 + 测试集 | ✅ | frontmatter `prompt_version` + `CHANGELOG.md` + `.workbuddy/ab/cases.json`（24 条） |
| 是否一次只改了一个变量 | ✅ | 本轮唯一变量 = prompt 文本结构与槽位（模型 / 调用方式 / 解析 / 渲染通道全不变） |

**仍处草稿态（未接运行时）**：

| 项 | 状态 |
|---|---|
| `src/main/resources/prompts/whiteboard/system-prompt.md` | v4.0.0 重写完成，**未被任何代码引用** |
| Java 源码（`PromptService` / `GenerationService` / `GraphValidator`） | **零改动**，运行时仍用 `PromptService` 硬编码旧 prompt |
| 生效条件 | 需 v2-41 `ContextAssembler` 接入（`{{SCENE_SCHEMA}}` 替换为 schema 全文、可选 prefill `{`）+ **错误对象后端放行**（见下）+ 重跑 A/B 验证 |

> ⚠️ **错误对象的硬前置条件**：根 schema 为 `required:["elements"]` + `additionalProperties:false`，`{"error":{...}}` 会被 `GraphValidator` 判非法并触发修正轮重试（`MAX_ROUNDS=3`，每轮白烧 100~150s）——v1.0.0 曾因此删除该设计。启用前必须：① `GraphValidator` 白名单放行 `{"error":{...}}`；② `GenerationService` 收到后直接以 SSE `error` 事件下发 `message`，不进入修正轮。二者均属 v2-41 范围。

> 关键含义：**项目运行时行为没有任何变化**。v4.0.0 是设计稿，接入前不影响出图。

**迁回/生效条件**：v2-41 装配重构 + 错误对象后端放行 + A/B 验证（5 条量化质量标准达标、c12 不再 0 元素、c09 不再泄漏、耗时不退化）。

## 一、为什么走企业化标准

参考样例（另一工作区 `2026-09-19` 那版）的**结构**确实企业化，但**内容**与当前 flowchart 项目脱节（schema 缺 line/freedraw、relation 枚举自相矛盾、让 LLM 输出契约外的 `error`/`_confidence` 字段）。

本方案**借结构、砍冲突**：
- 借：元信息头（带 promptVersion）、护栏编号表 + 原因列、步骤化任务流程、独立 CHANGELOG / 注册表
- 砍：一切与 `scene.schema.json` + `GenerationService` 实际行为冲突的设计（error / 置信度 / 速查副本漂移）
- **关键修正（用户 09-21 收尾）**：角色定位从「图表生成」改为「白板 AI 助手」（什么都能生成，不限于图）；版本管理元数据（变更历史 / prompt_version 等）不放进喂给 LLM 的正文——改用 YAML frontmatter（不喂 LLM）+ CHANGELOG.md 承载

## 二、目录布局（草稿态）

```
.workbuddy/prompts-draft/          # ★ 草稿区（未启用；非项目源码、不进 jar）
├── _DRAFT.md                      # 草稿说明（状态 / 回退原因 / 迁回条件）
├── README.md                      # 管理规范、文件职责、版本/审批流程、装配方式
├── prompt-library.yaml            # 注册表（元数据 + 审批状态）
└── whiteboard/                    # 白板 AI 生成 Prompt（M10 静态层核心资产）
    ├── system-prompt.md           # 定稿候选：LLM system 消息正文（顶部 YAML frontmatter 不喂 LLM）
    └── CHANGELOG.md               # 版本变更
```

**迁回后**的目标布局（打磨合格时执行）：

```
src/main/resources/prompts/        # 部署单一真相源（进 jar，运行时 ClassPathResource 读取）
├── README.md
├── prompt-library.yaml
└── whiteboard/
    ├── system-prompt.md
    └── CHANGELOG.md

src/test/resources/prompts/whiteboard/   # 回归 fixtures（M14 填充）
├── examples/
└── tests/
```

> 注意：`workbuddyFlow/` 是规划树，只放计划/设计说明，**不留 prompt 正文副本**（避免双份漂移）。

## 三、System Prompt 结构设计（草稿内容摘要）

> ⚠️ **本节记录 v1.0.0 的结构（历史）**，其中「护栏 G1-G7 / 5 步任务流程 / 契约速查 / 可追溯性」等段落在 v2.0.0→v4.0.0 演进中已重组或删除。**当前结构（v4.0.0 八段）以第〇节 + `system-prompt.md` 为准。**

| 区块 | 作用 | 备注 |
|------|------|------|
| 元信息头（YAML frontmatter） | prompt_id / prompt_version / status / owner / compatible_models / schema_source / injection | **不喂 LLM**；ContextAssembler 取 `prompt_version` 写回放日志（Q3-A） |
| 角色定义 | 「白板 AI 助手」：唯一职责 = 把需求转成白板元素 JSON；图表/图案/标注/笔记排版等任意元素内容都行 | 不回答/不解释/不闲聊 |
| 上下文 | 所属系统 / 下游 / 技术约束 / 契约来源 | 指明 `scene.schema.json` 是 system prompt 的契约区，运行时全量注入 |
| 护栏规则 G1-G7 | 不可协商硬约束 + 原因列 | G6 绑定箭头 / G7 编辑保真（对齐 PromptService/SceneDrift） |
| 任务流程 | 5 步思考骨架（意图→元素→结构→坐标→自校验） | 意图不明确不报 error，交后端 |
| 契约关键速查 | type/必填/relation 枚举速查 | 明确「完整 schema 运行时注入，以 schema 为准」 |
| 输出格式 | 只输出一个 JSON 对象 | 无解释/无围栏 |
| 可追溯性 | z-order / relation / group 决策可追溯 | 替代原 error/_confidence |
| 变更历史 | **已移除**（不进 LLM） | 属版本管理元数据，与 LLM 任务无关、浪费 token；统一由 CHANGELOG.md 承载 |

## 四、与 M10 的衔接

- **Q3-A**：`prompt_version`（在 frontmatter）由 M11 在改 prompt 文本时递增；M10 每轮组装带上并写入 `context-replay-<session>-<round>.json`
- **G2 全量 JIT**：`ContextAssembler` 运行时从 `scene.schema.json` 全量注入契约区（= system prompt 的组成部分），system-prompt.md 不复制全文，零漂移
- **rank()**：G1~G7 + 绑定箭头 + ≤40 固定在 system 首尾，治 Lost in the Middle
- **fit_token_budget()**：契约区固定高优先级不降级；历史/RAG/工具描述按优先级压缩（M2/M4 接入后）
- **frontmatter 剥离**：ContextAssembler 读 `system-prompt.md` 时只把 `---` 之后的正文作为 system 消息发给 LLM，frontmatter 仅用于取版本号

## 五、A/B 对比测试（2026-09-21 · 真大模型 raw 对 raw）

**目的**：验证新白板 prompt 相对旧硬编码 prompt 是否「不退化 / 有改进」，并暴露内容待打磨点。

**方法**（脚本 `F:\ProgramData\IDEA\flowchart\.workbuddy\compare\compare_prompt.py`）：
- 同一批 20 条输入（5 固定复杂 + 10 随机 + 5 杂乱），与 `visual/final_run` **逐条一致**，保证「同一份数据」可比
- **两版 prompt 都重跑**（旧 = `PromptService.buildPrompt` 文案 / 新 = `system-prompt.md` 正文 + schema 全量注入），raw 对 raw
- 同条件：模型 `mimo-v2.5-pro`、单条 `user` 消息、无 temperature/max_tokens（与 `OpenAiCompatibleProvider` 一致），唯一变量 = prompt 文本
- 指标：元素/图形/箭头(绑定)/分组/框 + 护栏违规（G1 freedraw、G3 frame.children、G4 渲染层字段、G5 >40、契约外字段、NaN、分组规则）

**为什么不用历史 `visual/final_run` 直接当基线**：那是**布局后**渲染场景，`group` 语义字段已被适配层剥掉，无法对比分组/frame 护栏；故做同条件重跑，历史数据仅作规模旁证。

**产物**：`.workbuddy/compare/report.md`（对比报告）、`metrics.json`、`{old,new}/raw/*.txt`、`{old,new}/scenes/*.json`。

**结论 A · 新版明确改进**：

| 维度 | 旧 prompt | 新 prompt | 判定 |
|---|---|---|---|
| G2 纯 JSON（不加围栏） | 20/20 全带 ```json 围栏 | 0/20，全纯 JSON | ✅ 护栏真生效 |
| 超长需求（固定5 数据流） | 反问用户「选哪种方案」→ 0 元素、解析失败 | 39 元素/6 模块，frame 名与 group 逐字对齐 | ✅ 不翻车 |
| 元素规模（G5 ≤40） | 固定2 吐 120 个元素 | 固定2 = 40 个 | ✅ 收敛 |
| 渲染层字段（G4） | 3 个用例混入 `roundness` 等 | 0 | ✅ 干净 |

> G2 那条最值钱：旧版靠后端 `extractJson` 剥围栏才没出错，一旦模型多写几行散文就整轮失败（固定5 就是这么翻的）。

**结论 B · 待回修问题（迁回前的门槛）**：
1. **frame 样式字段违规反而更多**（旧 2 例 → 新 3 例）：schema 不允许 `frame` 带 `backgroundColor`/`strokeColor`，但用户要求「用颜色区分模块」时模型自然给框上色。**要么 schema 放开 frame 样式，要么 prompt 明说「颜色加在图形上、别加在框上」**
2. **一处字段类型错误**：固定3 输出 `"height": "50"`（字符串，契约要 number）→ 下游算成 NaN
3. **耗时约 1.9×**：新版平均 72.7s/用例 vs 旧版 38.1s（正文更长 + 5 步流程诱发更多推理）
4. **指令泄漏**：自相矛盾那条（「越简单越好但要很多细节」）新版把 prompt 自己的 5 步流程画成了图；虽标了「内部思考不要输出」，歧义输入下仍被外泄

**另记（结构性瑕疵）**：
- 新 prompt 文本自相矛盾：G2 禁 markdown 围栏，但「输出格式」段自身用 ```json 围栏示例
- 示例锚定：极简需求（如「画个图」）会照搬「输出格式」示例（id `n1` / 坐标 100,100 / label「开始」）

## 六、下一步

1. **回修 4 项问题**（归 M11 prompt 打磨）：frame 样式口径 / 字段类型 / 耗时精简（压 5 步流程）/ 指令泄漏
2. **回修后迁回 + 重跑 A/B**：达到「不退化」再迁回 `src/main/resources/prompts/whiteboard/`
3. **v2-41 装配重构可先行**：v2-41 挂在 `PromptService` 现有内容块上（「只改组装方式、不改文本语义」），与 prompt 文本是否换版**解耦**，不必等本文档定稿
4. **M11 接管 promptVersion**：改 prompt 文本时递增版本号
5. **examples/ + tests/**：M14 评估回归门禁时填充

## 七、Prompt 资产该放在哪（2026-09-23）

**先把两条链路分清，否则一定放错位置：**

| | 消费者 | 载体 | 本项目的对应物 |
|---|---|---|---|
| **A. 产品运行时** | 产品内部的 LLM 调用（Spring Boot → `OpenAiCompatibleProvider` → 大模型） | 静态层资源，**进 jar** | `src/main/resources/prompts/whiteboard/system-prompt.md` + `schemas/scene.schema.json`（由 `ContextAssembler` 装配） |
| **B. 开发期 AI 助手** | Cursor / Claude Code / WorkBuddy 这类**帮你写代码**的助手 | 仓库内规则文件，宿主按目录与作用域加载 | `workbuddyFlow/AGENTS.md`（已有）；如需可另加 `.cursor/rules/*.mdc` |

- `.cursor/rules` / `.claude/rules` / `AGENTS.md` 都属于 **B**：只在"AI 帮你写这个仓库的代码"时生效，**永远不会进入产品运行时**。把角色设定、契约口径写到这里 → 产品根本读不到。
- 产品运行时的 prompt 只能待在 **A**，而且**只保留一份**（进 jar、可评审、可版本化）。

**逐类内容归属：**

| 内容 | 归属 | 理由 |
|---|---|---|
| 角色 / 目标 / 约束 / 执行流 / 输出格式（不随请求变） | A 静态层 system prompt | 前缀稳定 → KV-cache 命中；可评审、可回滚 |
| 契约全文（`scene.schema.json`） | A，但**运行时注入**（G2），prompt 正文不复制 | 单一真相源；schema 改版不必同步改 prompt（constraint.md「四处同改」教训） |
| 用户当轮需求 / 当前场景 / 上一轮校验 issues | **动态层**（每轮变的 user 消息） | 放进静态层会破坏前缀缓存 |
| 元素类型 / `relation` 枚举的**语义说明** | 可留在 prompt，但只作 glossary | 纯枚举值不表意；必须标"以注入契约为准"以防漂移 |
| 开发期约定（构建命令 / 红线 / run-verify / 目录结构） | B（`workbuddyFlow/AGENTS.md`） | 只对"写代码的助手"有意义 |
| 版本号 | A 的 frontmatter + `CHANGELOG.md` | 不进正文（不喂 LLM） |
| 凭据 / 内部权限结构 | **哪都不放** | — |

**校验方式**：每次改 prompt 只改一个变量，用 `.workbuddy/ab/cases.json`（24 条，12 规范 + 12 野路子）当基线重跑，对比 `analysis.json` 指标——即 Anthropic 说的 *calibrating the system prompt*：发现一个 failure case 就补一条规则，再回归。

**草稿区（`.workbuddy/prompts-draft/`）的定位**：只作临时工作区，**正式文本必须收敛回 A 的唯一位**。**2026-09-24 核对：已收敛** —— 草稿区 `ChartFlow-白板AI-system-prompt.md` 与 canonical `src/main/resources/prompts/whiteboard/system-prompt.md` **逐字节一致**（均为 **v5.8.0，2382 字符 / 4600 字节**），此前「v4.1.0 ↔ 13090B 旧版正文」的两份不同文本状态**已作废**。
⚠️ 但**收敛的是这两份 markdown**；运行时实际用的是 `PromptService` 的 **Java 字符串**，它与这两份 markdown 在 **3 条规则上仍互相矛盾**（freedraw / 箭头绑 frame / 坐标语义，逐条对照见 `state/active/m10-system-prompt.md` 的「三处口径冲突」）。**拍板前不要接入运行时**，否则"改哪份生效"仍然说不清。
