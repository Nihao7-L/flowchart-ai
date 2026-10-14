# v2-57 · 增量编辑工具 update_elements（承接 M6b v2-25 的 ops 部分）

> 状态：**已实现 + 真实 LLM E2E 通过（2026-10-14）**——门禁 326/0、checkstyle 0；E2E 5 步 5 SCENE_OK，update_elements 3/3 编辑步被模型采用、未动元素零误伤、编辑耗时 6~9s（对比新建 26~33s 快 3~4 倍）。**未提交 git**。遗留：删中间节点时模型未自动补桥接边（ops 忠实执行了删除，"删 A 补 A前→A后"的语义模型没想到）——观察项，可选提示词增量。
> 上游计划：`plans/masterPlan/m6b-interaction.md` v2-25（ops 协议）· `plans/masterPlan/m4-loop-engineering.md`（工具演进挂在 agent loop 上）
> 前置已满足：v2-55 agent loop 核（dbed51a/8914af7）· 编辑保真 v2-37 · M3 工具体系（对账结论：已被 v2-55 实质覆盖，见 m3-tools.md 头部）
> 用户验收要求（2026-10-14 拍板）：**做完后生成/编辑效果不得比现状差**（不回退红线）

## 一、目标

把「AI 改图 = 整图重画（全量 result）」升级为「AI 改图 = 增量 ops」：新增 agent 工具
`update_elements`，模型按 id 发 add / update / remove 操作，后端在**当前场景基线**上
应用后走同一条 SceneGate 管线校验。

白话：今天 AI 哪怕只改一个框的颜色，也要把整张图重新抄一遍再交——抄的过程可能抄丢
抄错；v2-57 之后 AI 只交"改了哪几个"，没交的一律保持原样，从机制上消灭"整图重画"
这一类事故。

## 二、设计

### 工具契约

```
update_elements({ "ops": [
  { "action": "add",    "element": {...完整新元素} },
  { "action": "update", "element": {...完整替换体, id 必填且必须已存在} },
  { "action": "remove", "id": "t3" }
] })
```

- **update = 整元素替换**，不做字段级合并。理由：合并语义（深度合并？数组怎么办？）
  出脏数据的面大；整元素替换与全量协议的字段语义 100% 一致，模型零新知识。
- **基座选择**（多轮增量）：
  1. `ctx.validScene()` 非空（本次 run 已有一次成功提交）→ 以它为基座；
  2. 否则 `ctx.previousJson()` 非空（编辑模式首次）→ 以它为基座；
  3. 两者皆空（新建模式调了 update）→ fail 并点名"新建场景请用 submit_scene"。
- 应用顺序：remove → update → add（先删后加，规避"删了又引用"的中间态）。
- **id 纪律**：update/remove 的 id 必须存在于基座（缺失 → fail 点名 id）；add 的 id
  不得与基座现有 id 冲突（冲突 → fail 点名）。模型没写 id 的 add 由后端补 `id` 与
  `bind` 后续管线一致？**不补**——fail 点名"add 必须带 id"，与契约"元素必有 id"同口径。
- 应用完成后组装 `{"elements":[...]}`，**原样交给 `SceneGate.process`**（bind → dedup
  → sanitize → claimFramesOnly → parseGate → validate + audit + 空场景 + 编辑保真）。
  校验零 issue → `validScene` → AgentLoop 判 COMPLETED。
- **编辑保真天然变强**：ops 按 id 操作，未动元素 id/几何 100% 保留，`detectRewrite`
  的 id 重叠率判据几乎不可能误伤；SceneDrift 照常跑（首次提交口径不变）。

### 接线改动点（最小面）

| 文件 | 改动 |
|---|---|
| `agent/tools/UpdateElementsTool.java` | 新增（复用 SceneGate，无新依赖） |
| `agent/tools/AgentTools.java` | defaultRegistry 注册第 5 个工具 |
| `agent/AgentLoop.java` | COMPLETED 判定：`SubmitSceneTool.NAME` **或** `UpdateElementsTool.NAME` 命中且 ok |
| 提示词（工具 description 自带） | "编辑已有图时优先 update_elements；只有新建/用户明确要求重画才 submit_scene"——**不改 system-prompt.md**（契约段与字节预算零变化，规避 v10.x 版本连锁） |

### 不回退保障（验收红线）

1. 增量产物与全量产物**走同一条校验管线**，标准零放松；
2. `submit_scene` 保留不动，模型任何时候可退回全量（能力不删、只加）；
3. E2E 对比（任务 4）：同一批编辑提示词增量 vs 现状全量各跑一遍，对比
   编辑保真 / 元素保留率 / 校验通过率 / 观感——增量任何一项**劣于**全量即不达标；
4. 门禁全绿（`mvn -o test`（当前 313/0）· tsc 0 · scene-smoke 22/22）。

## 三、边界

- 允许新增：`agent/tools/UpdateElementsTool.java` + 对应单测
- 允许修改：`AgentTools.java`（注册）、`AgentLoop.java`（完成判定一行）
- 禁止改动：system-prompt.md、前端、GraphValidator 判定逻辑、scene.schema.json

## 四、风险表

| 风险 | 缓解 |
|---|---|
| 模型仍惯性全量 submit_scene（描述引导不够） | description 写明优先级；E2E 观察 tool_call 分布，不行再加 few-shot |
| ops 语义模型理解偏差（update 只发差量字段） | fail 文案点名"update 必须给完整替换体"；观察期不实现合并 |
| 多轮 update 后基座越改越偏 | 基座永远取 validScene（校验通过的最新态），偏离会被校验器拦 |
| 新建模式误调 | fail 引导回 submit_scene |

## 五、验收口径

1. 单测：ops 应用（add/update/remove/id 纪律/基座选择/新建模式拒绝）；
2. 门禁全绿（红线数字不降）；
3. 真实 LLM E2E：编辑场景（改颜色 / 删节点 / 加节点）走 update_elements 成功，
   未涉及元素 id+几何逐字节保留；
4. 不回退对比：增量 vs 全量在同等编辑提示词下质量不劣化（用户红线）。
