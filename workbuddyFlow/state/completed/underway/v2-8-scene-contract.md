# v2-8 出图契约（元素场景 IR）

> 状态机：本文件是 S1 当前唯一活跃项，置于 `state/active/`（同刻只 1 个）。
> 落盘口径：本计划已落盘——`resources/schemas/scene.schema.json`（8 元素类型，arrow 用 `oneOf` 强制 start/end 或 points 二选一）已写入；typed 版 `diagram.schema.json` 已删除；ADR-4 已推翻重写为「坐标由布局确定性生成并存入 IR」；`architecture.md` 与 `m6a-render.md` 已同步。

## 元信息
- 任务编号：v2-8（与 `plans/masterPlan/m1-generation.md` 下 v2-8/v2-9 子计划一致）
- 状态：落盘完成（待归档）
- 来源模块：M1 生成（`plans/masterPlan/m1-generation.md`）
- 创建：2026-09-14 + Agent
- 方向决策（已锁定）：路线 2「元素场景」——后端 IR 从"图(nodes/edges)"降为"元素场景(elements)"；ADR-4 推翻重写成"坐标由布局确定性生成并存入 IR"；图类型判别字段取消（flowchart/architecture/mindmap 不再区分）。

## 目标
固化 `scene.schema.json`：后端 LLM 输出与前端渲染共用的**唯一真相源**（Element Scene IR）。推翻原 typed 版 `diagram.schema.json`（已删除：14:31 写入未提交那份，落盘时移除）。元素是 Excalidraw skeleton（程序化创建元素的官方简化格式）的精简子集。

## 边界
- 允许新增：`resources/schemas/scene.schema.json`；文档同步（ADR-4 / architecture / roadmap / m6a-render）。
- 允许修改：`architecture.md` 中 ADR-4 及 IR 相关描述；删除 `diagram.schema.json`（typed 版）。
- 禁止改动：现有后端业务调用方（`FlowchartData`/`MindmapData` 字段语义本任务不动）；`PromptService`/`ParserService` 实际改造留到 v2-8 落盘后或 v2-9；元素级 ops 协议（add/move/delete…）留 M3/M6b；真实布局算法（elkjs/dagre）落地在 M6a。

## 分层边界
- 契约在 `resources/schemas/`（数据层）。
- 校验服务（`graph/SchemaService` 加载 + 结构校验）属 `graph/` 包（按 architecture 2.4 包表管"图模型 + 校验"）。
- 不触碰 controller/service 业务层、前端代码、真实布局。

## 前置
1. 删除 `diagram.schema.json`（typed 版，14:31 写入未提交）。
2. 待用户本地用 `excalidraw.production.min.js` 验证：`convertToExcalidrawElements` 对「无坐标 skeleton 元素」是否自动布局——决定 IR 里坐标是"必填"还是"可省略由前端补"。当前草案按 ADR-4 新立场：**坐标作为数据存**（生成时由布局确定性算出，用户拖拽后回写）。

## 步骤
1. 落盘 `scene.schema.json`（8 元素类型，草案见文末）。
2. 改 ADR-4（推翻原「IR 坐标无关」，改为「坐标由布局确定性生成并存入 IR；用户拖拽经 `PATCH /api/model/positions` 回写」）。
3. 同步 docs：`architecture.md` IR 描述 + 不变式 #6；`roadmap.md`；`m6a-render.md` 渲染链路（元素直接映射，不再「图→布局→重建」）。
4. （可选）`SchemaService` 加载 + 结构校验骨架，为 v2-9 的 `field/reason/hint` 语义校验打底。

## 验收口径
1. `scene.schema.json` 合法 draft-07，8 类元素均可被加载校验。
2. 结构非法输入（缺 `id` / `type` 非法 / 未知元素 / 数组外多字段 / arrow 既无 `points` 也无 `start+end`）给 **path 级报错**，而非笼统 `Exception`。
3. ADR-4 新文案与 schema 一致；architecture/roadmap/m6a 引用同步无断链。
4. （若动代码）`run-verify.ps1` 全绿。

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 平铺 shape+arrow(绑定) | rectangle + arrow{start,end} | 通过 |
| 自由箭头 | arrow{points} | 通过 |
| 标注/画框/手绘 | text + frame + freedraw | 通过 |
| 缺 id | shape 无 id | 拒，path=nodes[i].id |
| 非法 type | type:"triangle" | 拒 |
| arrow 两路皆无 | arrow 无 points 无 start/end | 拒 |
| 多余字段 | shape 带未定义字段 | 拒（additionalProperties:false） |

## 完成后
- 状态行改「完成」；移 `state/completed/underway/`；
- 更新 `m1-generation.md` 中 v2-8 状态行；
- `state/activeLog/2026-09-14.md` 追加带证据记录；
- 从 `state/active/` 移出再归档。

---

## 附：scene.schema.json（已落盘为 resources/schemas/scene.schema.json）

8 种元素：`rectangle` / `ellipse` / `diamond`（形状）、`arrow`、`line`、`text`、`frame`、`freedraw`。

```json
{
  "$schema": "https://json-schema.org/draft-07/schema#",
  "$id": "https://chartflow.local/schemas/scene.schema.json",
  "title": "Diagram Scene（元素场景 IR）",
  "description": "后端与前端共用的唯一真相源。场景=有序元素数组；数组顺序即 z 序（谁在后谁在上）。坐标为数据：生成时由布局算法确定性算出，用户拖拽后回写。元素是 Excalidraw skeleton 的精简子集。",
  "type": "object",
  "required": ["elements"],
  "additionalProperties": false,
  "properties": {
    "elements": { "type": "array", "items": { "$ref": "#/definitions/element" } }
  },
  "definitions": {
    "label":   { "type": "object", "required": ["text"], "additionalProperties": false,
                 "properties": { "text": { "type": "string", "minLength": 1 } } },
    "point":   { "type": "array", "description": "相对坐标 [dx,dy]",
                 "items": { "type": "number" }, "minItems": 2, "maxItems": 2 },
    "binding": { "type": "object", "description": "端点绑定：指向某元素 id",
                 "required": ["id"], "additionalProperties": false,
                 "properties": { "id": { "type": "string", "pattern": "^[A-Za-z0-9_-]+$" } } },

    "element": { "oneOf": [
      { "$ref": "#/definitions/shape" }, { "$ref": "#/definitions/arrow" },
      { "$ref": "#/definitions/line" },  { "$ref": "#/definitions/text" },
      { "$ref": "#/definitions/frame" }, { "$ref": "#/definitions/freedraw" }
    ] },

    "shape": {
      "type": "object", "required": ["id", "type", "x", "y", "width", "height"],
      "additionalProperties": false,
      "properties": {
        "id": { "type": "string", "pattern": "^[A-Za-z0-9_-]+$" },
        "type": { "enum": ["rectangle", "ellipse", "diamond"] },
        "x": { "type": "number" }, "y": { "type": "number" },
        "width": { "type": "number", "exclusiveMinimum": 0 },
        "height": { "type": "number", "exclusiveMinimum": 0 },
        "angle": { "type": "number", "description": "弧度，默认 0" },
        "label": { "$ref": "#/definitions/label" },
        "strokeColor": { "type": "string" }, "backgroundColor": { "type": "string" },
        "fillStyle": { "enum": ["hachure", "cross-hatch", "solid"] },
        "strokeWidth": { "type": "number", "minimum": 0 },
        "roughness": { "type": "number", "minimum": 0 },
        "opacity": { "type": "integer", "minimum": 0, "maximum": 100 }
      }
    },

    "arrow": {
      "type": "object", "required": ["id", "type"],
      "additionalProperties": false,
      "properties": {
        "id": { "type": "string", "pattern": "^[A-Za-z0-9_-]+$" },
        "type": { "const": "arrow" },
        "x": { "type": "number" }, "y": { "type": "number" },
        "points": { "type": "array", "items": { "$ref": "#/definitions/point" }, "minItems": 2 },
        "start": { "$ref": "#/definitions/binding" },
        "end": { "$ref": "#/definitions/binding" },
        "label": { "$ref": "#/definitions/label" },
        "strokeColor": { "type": "string" }, "strokeWidth": { "type": "number", "minimum": 0 },
        "strokeStyle": { "enum": ["solid", "dashed", "dotted"] },
        "opacity": { "type": "integer", "minimum": 0, "maximum": 100 }
      },
      "oneOf": [
        { "required": ["start", "end"] },
        { "required": ["points"] }
      ]
    },

    "line": {
      "type": "object", "required": ["id", "type", "x", "y", "points"],
      "additionalProperties": false,
      "properties": {
        "id": { "type": "string", "pattern": "^[A-Za-z0-9_-]+$" },
        "type": { "const": "line" },
        "x": { "type": "number" }, "y": { "type": "number" },
        "points": { "type": "array", "items": { "$ref": "#/definitions/point" }, "minItems": 2 },
        "strokeColor": { "type": "string" }, "strokeWidth": { "type": "number", "minimum": 0 },
        "strokeStyle": { "enum": ["solid", "dashed", "dotted"] },
        "opacity": { "type": "integer", "minimum": 0, "maximum": 100 }
      }
    },

    "text": {
      "type": "object", "required": ["id", "type", "x", "y", "text"],
      "additionalProperties": false,
      "properties": {
        "id": { "type": "string", "pattern": "^[A-Za-z0-9_-]+$" },
        "type": { "const": "text" },
        "x": { "type": "number" }, "y": { "type": "number" },
        "text": { "type": "string", "minLength": 1 },
        "fontSize": { "type": "number", "minimum": 1 },
        "fontFamily": { "enum": [1, 2, 3], "description": "1=手绘 2=Helvetica 3=等宽" },
        "textAlign": { "enum": ["left", "center", "right"] },
        "angle": { "type": "number" },
        "strokeColor": { "type": "string" }
      }
    },

    "frame": {
      "type": "object", "required": ["id", "type", "x", "y", "width", "height"],
      "additionalProperties": false,
      "properties": {
        "id": { "type": "string", "pattern": "^[A-Za-z0-9_-]+$" },
        "type": { "const": "frame" },
        "x": { "type": "number" }, "y": { "type": "number" },
        "width": { "type": "number", "exclusiveMinimum": 0 },
        "height": { "type": "number", "exclusiveMinimum": 0 },
        "name": { "type": "string", "description": "画框标题，可空" }
      }
    },

    "freedraw": {
      "type": "object", "required": ["id", "type", "x", "y", "points"],
      "additionalProperties": false,
      "properties": {
        "id": { "type": "string", "pattern": "^[A-Za-z0-9_-]+$" },
        "type": { "const": "freedraw" },
        "x": { "type": "number" }, "y": { "type": "number" },
        "points": { "type": "array", "items": { "$ref": "#/definitions/point" }, "minItems": 2 },
        "strokeColor": { "type": "string" }, "strokeWidth": { "type": "number", "minimum": 0 },
        "opacity": { "type": "integer", "minimum": 0, "maximum": 100 }
      }
    }
  }
}
```

### 箭头「points 或 start/end 二选一」设计说明
- 草案用 `oneOf` 而非 `anyOf`：强制"两种写法恰好选一种"。若某 arrow 同时带 `start/end` 和 `points`，两个分支都匹配 → `oneOf` 判失败（明确拒绝二义性）；若两者皆无 → 也失败（明确拒绝缺信息）。这比之前草案的 `anyOf` 更严格、更不容易产生"既绑定又带路径"的脏数据。
- `x`,`y` 在 arrow 里改为**可选**：绑定箭头（start/end）的几何由 Excalidraw 从两端形状推导，不必人工给坐标；自由箭头（points）的 `x`,`y` 作为起点原点仍可给。
- 详见下方文字回复中的「怎么选」判断表。

## 落盘记录（2026-09-14）

- 落盘时间：2026-09-14
- 已写入：`src/main/resources/schemas/scene.schema.json`（合法 draft-07，6 类元素 oneOf + arrow oneOf 二选一）
- 已删除：`src/main/resources/schemas/diagram.schema.json`（旧 typed 版，14:31 写入未提交）
- 已改：`architecture.md` ADR-4 推翻重写 + 2.1/2.2/2.4/不变式#6 同步
- 已同步：`m6a-render.md` 渲染链路（元素场景 IR 含坐标 → convert 渲染）
- 待用户 commit（工作树改动，未提交）
- 后续：SchemaService 加载+校验（graph/，为 v2-9 语义校验 field/reason/hint 打底）；真实布局算法在 M6a
