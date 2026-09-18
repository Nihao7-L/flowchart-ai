# v2-37 编辑模式几何保真校验（SceneDrift）

> 状态：**完成**（2026-09-17 落盘并通过门禁；代码待用户 commit）
> 所属模块：M1（生成链路），关闭"编辑模式下几何漂移无人管"这个缺口
> 所属阶段：**S1 看得见**（S1 收尾项之一）
> 前置：无（纯比对逻辑，不依赖外部服务、不新增依赖）
> 创建：2026-09-17 + AI ｜ 归档：2026-09-17
> 关联：`plans/masterPlan/m1-generation.md`（已登记）；**本项必须先于 `plans/underway/v2-36-layout.md` 落地**

## 元信息
- 任务编号：v2-37
- 状态：完成
- 来源模块：`plans/masterPlan/m1-generation.md`
- 创建：2026-09-17 + AI

## 目标
让 `PromptService` 已向 LLM 承诺的"没有要求改动的元素必须原样保留 id / type / 几何"**变成可执行的校验**：编辑语境下，若 LLM 保留了 id 却把整张图重新排了一遍，必须被检出并进修正轮。

## 边界
- 允许新增：`graph/SceneDrift.java` + `SceneDriftTest.java`
- 允许修改：`service/GenerationService.java`（编辑语境首轮接线）、`graph/GraphValidator.java`（补注释说明为何不校验 `frame.children`）、`resources/schemas/scene.schema.json`（frame 分支补 `description`）
- 禁止改动：`llm/`、`session/`、`controller/` 的接口签名、`frontend/`、以及 `scene.schema.json` 的**必填字段集**（只在 frame 分支补 description，不动 `required` / `additionalProperties`）
- 禁止：把 `SceneDrift` 做成"改数据"的组件 —— 它只**比对**，不修改、不重排

## 分层边界
落在 `graph/`（纯计算），由 `service/` 在编辑语境第一轮调用 —— 与包表箭头方向一致（`service/` → `graph/`，`graph/` 不调任何人）。
与 `detectRewrite` 是**两个不同的洞**：后者查"整图 id 是否被全换成新的"，前者查"id 还在、但几何被整体重排"。二者并列跑，互补不重叠。

## 前置
无。纯 JSON 比对，单测不需要起 Spring 容器、不需要 LLM。

## 步骤
1. 定义"可比元素"与"漂移"：只比**两版都存在且带 id** 的元素；带尺寸类型（rectangle / ellipse / diamond / frame）比 x / y / width / height，端点类型（arrow / line / freedraw）比点集。
2. 定判据（保守优先，宁可漏报不误报）：可比元素 ≥ 3、其中漂移 ≥ 3 且占比 ≥ 50%，才判"整图重排"。
3. 放行两类合法变更：① **整体平移**（位移向量一致且尺寸未变）；② **几何由端点推导**的箭头（两端 `start` / `end` 都带 `id`）与 `freedraw` 不参与比对。
4. 接入 `GenerationService` 编辑语境首轮（`editMode && !takeOver && round == 1`），在 `detectRewrite` 之后追加，issue 进同一批 `issues` 走修正循环。
5. 坏 JSON 不在此处报（交给 `GraphValidator`），`catch` 返回 `null` 避免重复告警。

## 验收口径
1. 编辑语境"保留 id 但整图重排" → 产生 `ValidationIssue` 并触发修正轮
2. 编辑语境"整体平移" → 首轮通过即发（不产生 issue）
3. 单元素挪动 / 仅改文字 / 仅改颜色 → 不误报
4. 可比元素 < 3 或漂移占比 < 50% → 不报（避免小图误伤）
5. 后端 `mvn clean test` 全绿 + `checkstyle:check` 0 违规

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 完全相同 | 两版字节一致 | 通过（null） |
| 舍入容差 | 坐标差 < 2px | 通过 |
| 单元素挪动 | 4 元素里只动 1 个 | 通过 |
| 仅改文字 | 坐标不动、label 变 | 通过 |
| 整图重排 | 4 元素坐标全换 | **报 issue** |
| 整体平移 | 4 元素位移向量一致、尺寸不变 | 通过 |
| 改尺寸 | 某元素 width 变化 | **报 issue** |
| 可比元素过少 | 只有 2 个元素保留 id | 通过（跳过） |
| 少数漂移 | 6 个里 2 个漂移（< 50%） | 通过 |
| 新增元素 | 多出 N 个新元素 | 不参与比对，不报 |
| 删除元素 | 少了 N 个元素 | 不参与比对，不报 |
| 两端绑定箭头 | 几何被重算 | 通过（几何由端点推导） |
| 自由线路径变 | freedraw 点集变 | **报 issue** |
| 坏 JSON | 非法 JSON | 返回 null（交给 `GraphValidator`） |

## 完成结论（2026-09-17）
- **交付**：新增 `src/main/java/io/github/nihaoljx/flowchart/graph/SceneDrift.java`（`@Component`，只比对不改数据；常量 `GEOMETRY_TOL=2.0` / `MIN_KEPT=3` / `MIN_DRIFTED=3` / `DRIFT_RATIO=0.5`；`isUniformTranslation` 用**中位数**而非平均值判定整体平移 —— LLM 顺手改一个元素不会带偏中位数）；`GenerationService` 编辑语境首轮接线。
- **联锁（为什么必须先做）**：`PromptService:104~105` 已承诺"没有要求改动的元素必须原样保留 id / type / 几何（x/y/width/height）"，但此前后端**没有任何一层在守这条承诺**（`GraphValidator` 只看单元素字段合法性、`detectRewrite` 只看整图 id 是否全换）。而 v2-36 的"全量重排布局"会**直接打破**该承诺 ⇒ 本项必须先于 v2-36 落地，且 v2-36 必须分"新建全量重排 / 编辑保留坐标"两种模式（见 `v2-36-layout.md` 决策 3），否则两者互相打架。
- **证据**：`SceneDriftTest` 14 例；`GenerationServiceTest` 新增 2 例（`flagsGeometryRelayoutDuringEdit` / `allowsUniformTranslationDuringEdit`，用打桩 Provider 断言 LLM 调用次数与 `buildFixPrompt` 是否被调）；`mvn clean test` → **Tests run: 102, Failures: 0, Errors: 0, Skipped: 0** + `BUILD SUCCESS`；`mvn checkstyle:check` → **You have 0 Checkstyle violations**。
- **未做**：真实 LLM 端到端复跑（需起后端 + 消耗真实调用，按协作红线须先征得同意）；浏览器 E2E 未复跑（本次未改前端，判定不受影响）。
- **状态**：代码与测试已落盘，**待用户 commit**（`git` 写操作由用户执行）。
