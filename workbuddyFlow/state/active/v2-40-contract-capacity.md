# v2-40 — 契约容量扩展（表达力）

> 状态：**进行中**（2026-09-18 用户拍板 5 项；**P0 已落地并通过门禁**）
> 模块归属：**`m9-contract-capacity.md`（2026-09-18 拍板新开）**
> 前置：`v2-38`（LayoutEngine 接口 + ELK 竖排为默认）已完成
> 依据：`../../docs/diagram-grammar.md`（图型语法与元素能力矩阵，2026-09-18 实测）· `../../docs/constraint.md`（两道闸门红线）

## 一、要解决的问题（用户原话）

> 「画不了复杂的图形我觉得 scene.schema.json 还有这个原因，这个太过于局限了，太过于死板了，
> 就算现在再怎么测试都没用」
> 「后端可以生成什么我觉得可以看看前端这个 Excalidraw 可以渲染什么东西」

**判断成立，且有量化证据**：契约把一份表达能力很强的格式砍成了「6 类元素 + 约 30 个字段」，
而渲染器侧的能力（12 种箭头端点、多层分组、可嵌套画框、圆角、虚线、超链接、垂直对齐）
**一项都传不进去** —— 不是渲染器不支持，是契约与前端白名单两道闸门把它挡住了。

**"再怎么测试都没用"这半句也对**：测试只能暴露症状。需求空间（要支持哪些图型）没定义之前，
契约无法判断"扩够了没有"。所以本计划配合 `diagram-grammar.md` 一起推进。

## 二、方案：把契约分成「意图层」和「渲染层」，中间加一道编译

**核心决定**：不把 Excalidraw 的字段直接开放给 LLM，而是让 LLM 只表达**语义**，
由后端**编译**成渲染字段。

| 层 | 谁产出 | 内容 | 例子 |
|---|---|---|---|
| **意图层** | LLM | 语义、关系、层次、粗略位置 | `"group": "订单域"`、`"relation": "one-to-many"`、`"role": "module"` |
| **编译层** | 后端（新） | 语义 → 视觉的**规范映射表** | `one-to-many` → `endArrowhead: "crowfoot_many"`；`module` → 圆角 + 边框虚线 + 配色 |
| **渲染层** | 后端产出 | Excalidraw 能直接吃的字段 | `groupIds`、`frame` + `frameId`、`roundness`、`strokeStyle`…… |

**为什么必须这样分**（而不是"全开字段让 LLM 自己选"）：

- LLM 猜不准几何：`roundness: {type: 3}` 这种数值它只会瞎写，而"这是模块"是它擅长的。
- **样式一致性需要单一出处**：同一个图里"模块"长什么样，必须由规范决定，不能每次由 LLM 即兴发挥。
- 语义是**可校验的**（"one-to-many 必须有两端实体"），字段是**不可校验的**。
- 这条分界线就是九步工作流里「**定规范**」的实体：规范 = 语义 → 视觉的映射表。

## 三、分阶段（每阶段独立可交付、可回滚）

### P0 — 无损扩字段（向后兼容，风险最低）—— ✅ **已完成（2026-09-18）**

契约新增**全部可选**字段，前端 `SKELETON_KEYS` 同步，后端透传。不改现有语义。
实际改动 5 处、门禁证据见**第八节**。

| 字段 | 加到哪 | 实测状态 |
|---|---|---|
| `endArrowhead` / `startArrowhead` | arrow | ✅ 探针：与绑定共存 |
| `strokeStyle` | shape（原本只有 arrow/line） | ✅ 探针：保留 |
| `roundness` | shape | ✅ |
| `link` | 全部 | ✅ |
| `verticalAlign` / `autoResize` | text | ✅ |
| `fillStyle` 枚举补 `zigzag` | shape | ✅ |
| `frame.children` | frame（**改由后端算，见 P1**） | ✅ 对照实验已证归属作用 |

> ⚠️ **两道闸门必须同时改**：契约（`scene.schema.json`）+ 前端白名单（`SKELETON_KEYS`）。
> 只改契约 → 字段被白名单静默丢弃，症状是"改了没反应"，最难查。

### P1 — 层次（8/9 条测试题的核心诉求）

- 契约：`shape.group`（模块名的**有序数组**，最深→最浅，对齐 `groupIds` 语义）+ `frame.children`
- 后端：由 `group` 派生 `groupIds`；由分组 + 几何算出每个 `frame` 的成员 → 写 `children`；
  最终归属由转换器反填为成员 `frameId`（**不再补空数组**）
- 布局：`frame` 参与布局（v2-39 的 5.4，与本阶段共用同一套成员计算）
- 验收：框与内容归属正确（`frameId` 非空）、框可嵌套、拖动框带走成员

### P2 — 关系语义（让"边"能表达含义）

- 契约：`arrow.relation`（枚举：`flow` / `call` / `data` / `async` / `dependency` / `one-to-one` /
  `one-to-many` / `many-to-many` / `inherit` / `block`）
- 编译表（规范）：`flow`→实线三角；`async`→虚线开三角；`data`→点线；
  `one-to-many`→`crowfoot_many`；`block`→`bar` 起点；`inherit`→`triangle_outline`
- 验收：ER 图能画出基数；时序图能区分同步/异步

### P3 — 图型语法（"选图型"这一环）

- 契约：顶层加 `diagramType`（`flowchart` / `architecture` / `state` / `sequence` / `dfd` /
  `deployment` / `decision` / `gantt` / `mixed`）
- 每条图型一份**语法约束**（进入提示词）：允许的元素组合、必需元素（时序图必须有生命线）、
  布局模式（分层 / 列式 / 时间轴）、校验规则
- 布局侧同步：`layout()` 不再对"无图形元素"的图直接返回（时序图/甘特图当前**零布局收益**）

### P4 — 前端可用性（用户已批）

- `.chat-input` 由单行 `<input>` 改 `textarea`（多行提示词现在被剥掉全部换行，实测 62 行 → 送达 1 行）
- 顺带确认导出能力（九步的最后一步"导出交付"）
- 样式调整按用户意见执行

## 四、边界（禁止改动）

- ❌ **不把几何计算责任交给 LLM**：`x` / `y` 仍是种子值，布局仍由后端算。
- ❌ **不破坏现有 6 类元素的语义**：新增字段**全部可选**，旧场景 JSON 必须原样能渲染（回归锁）。
- ❌ **不把 `children` 交给 LLM 填**（09-17 决定的动机不变）；但适配层不再补空 —— 改由后端算、适配层填。
- ❌ **不碰 `SceneDrift` / 编辑模式坐标冻结语义**（否则与 v2-37 打架）。
- ⚠️ **新增枚举值时必须同时改校验器**：`GraphValidator` 的 `FILL_STYLES` / `ARROWHEADS` / `VERTICAL_ALIGNS` 是硬编码集合 —— 契约放了、校验器没放，LLM 一用就判非法，白耗一整轮（约 130 秒）。这是"扩字段"的**第 3 道闸门**（P0 期间实测发现）。
- ⚠️ **契约扩展必须同步 `SKELETON_KEYS`**（已在 `constraint.md` 立成红线）—— 现在还有**自动守卫**：`frontend/tools/scene-smoke.mjs` 最后一条用例读 schema 与源文件做差集，漏同步直接红。

## 五、步骤

1. 新建 `diagram-grammar.md` —— ✅ 已完成（2026-09-18）
2. 修正 `constraint.md` 的 frame children 条 —— ✅ 已完成（2026-09-18）
3. P0：扩字段 + 白名单 + **校验器枚举** + 契约测试 + 冒烟守卫 —— ✅ **已完成（2026-09-18，见第八节）**
4. P1：`group` → `groupIds` / `frame.children` 编译（与 v2-39 的 5.4 合并实现）
5. P2：`relation` → 端点形状/线型映射表 + ER 图验收
6. P3：`diagramType` + 图型语法进提示词 + 布局对非图形图生效
7. P4：输入框 textarea + 导出确认

## 六、验收口径

1. 探针 `probe_contract_capacity.py` 全绿（P0 后每个新字段都在转换产物里出现）；
2. **旧场景回归**：改动前的场景 JSON 渲染结果**逐元素一致**（含 frame）；
3. frame 归属正确：`frameId` 非空率 100%，框可嵌套；
4. ER 图验收：能画出 1:N 基数（鸦脚），且箭头仍绑定；
5. 时序图验收：**布局生效**（不再是种子坐标上屏），26 个 text 的互叠对从 72 降到可控；
6. 复杂提示词电池：题 2 / 题 4 复跑，元素数与重叠指标不劣化；
7. 门禁全绿：后端测试 + checkstyle 0 违规 + 前端三绿 + 冒烟 + E2E。

## 七、已拍板（2026-09-18 用户）

| # | 问题 | 决定 |
|---|---|---|
| 1 | 契约扩展的边界原则 | **语义编译**（LLM 只说语义，后端编译成渲染字段）—— 见第二节 |
| 2 | P0 是否立刻做 | **立刻做** → ✅ 已完成，见第八节 |
| 3 | 图型优先级 | **先做部署图 / 决策树 / 甘特图**（三类缺口性质完全不同，见 `../../plans/masterPlan/m9-contract-capacity.md` 第 6.1 节） |
| 4 | `group` 写法 | **自由文本**（`shape.group: "订单域"`），后端据此算 frame |
| 5 | 模块归属 | **新开 `m9-contract-capacity.md`**（M1 已归档，按约定不回改） |

## 八、P0 落地记录（2026-09-18）

**改动的 5 处** —— "扩字段"必须同时改，**漏一处就是"改了没反应"**（白名单外的键不报错、只是不生效）：

| # | 文件 | 改动 |
|---|---|---|
| 1 | `src/main/resources/schemas/scene.schema.json` | 新增 4 个 definition：`arrowhead`（12 种）/ `roundness` / `verticalAlign` / `link`；shape 补 `strokeStyle`、`roundness`、`link`、`fillStyle` 补 `zigzag`；arrow 补 `startArrowhead`、`endArrowhead`、`link`；line / text / frame 补 `link`；text 补 `verticalAlign`、`autoResize` |
| 2 | `graph/GraphValidator.java` | `FILL_STYLES` 补 `zigzag`；新增 `VERTICAL_ALIGNS`、`ARROWHEADS`（12 种，与渲染器枚举逐字对齐）；shape / arrow / text 各补对应 `checkOptionalEnum` |
| 3 | `frontend/src/types.ts` | 新增 `SceneArrowhead` 联合类型；`SceneElement` 补 6 个字段 |
| 4 | `frontend/src/lib/sceneAdapter.ts` | `SKELETON_KEYS` 补 `roundness` / `link` / `startArrowhead` / `endArrowhead` / `verticalAlign` / `autoResize`；顺带修正 frame `children` 的过时注释 |
| 5 | `frontend/tools/scene-smoke.mjs` | 新增 2 条用例：**字段透传**（含"端点形状与绑定共存"断言）+ **契约↔白名单自动守卫**（读 schema 与源文件做差集，漏同步直接红） |

**门禁证据（2026-09-18 11:22）**：

- 后端：`Tests run: 125, Failures: 0, Errors: 0` + `You have 0 Checkstyle violations` + `BUILD SUCCESS`（原 121 + 新增 4 条）
- 前端：`tsc -b` / `eslint . --ext ts,tsx` / `vite build --emptyOutDir false` 三者 **exit=0**
- 冒烟：**12/12 PASSED**（原 10 + 新增 2）

**有意留到 P1 的**：`frame.children` 仍由适配层补 `[]`（行为不变）。2026-09-18 已确认它有归属语义，但"成员列表"需要分组信息才能算 → 与 v2-39 的 5.4 合并实现。

**P0 之后的通道状态**：字段能传到渲染器了，但**还没有人往里填**（LLM 按方案 B 不填样式、后端编译层尚未建）→ 这是 P1/P2 的活。
