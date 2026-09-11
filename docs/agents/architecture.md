# architecture.md — 核心架构

> 只回答一个问题：**代码分成哪些块、谁可以调用谁、数据怎么流。**
> 读它的时机：新增文件、新增依赖、改动调用关系之前。上限 120 行。

## 一、后端包边界（跨包调用只允许箭头方向）

| 包 | 职责 | 允许调用 |
|---|---|---|
| `controller/` | REST + SSE 接口，只做参数校验与编排 | `service/` |
| `service/` | 生成编排：Prompt → LLM → 解析 → 校验 → 修正 | `llm/ graph/ rag/ tools/ session/` |
| `llm/` | Provider 抽象 + Gateway 网关（收口全部 LLM 调用） | — |
| `graph/` | 图模型、校验、布局、SVG / Mermaid 导出 | — |
| `rag/` | 检索增强（阶段 2 建） | `llm/` |
| `tools/` | 工具注册与执行（阶段 3 建） | `rag/ llm/` |
| `agent/` | ReAct 规划与执行（阶段 4 建） | `tools/ llm/ graph/` |
| `session/` | 会话与记忆（阶段 5 建） | — |
| `infra/` | Trace / 指标 / 审计（阶段 7 建） | — |

两条规矩：**同层不互相调用；下层不知道上层存在。** 新增跨包依赖前，先改这张表。

## 二、关键数据流

```
[生成] 前端 POST /api/generate
      → controller 参数校验
      → service 组装 Prompt（可选注入 rag 召回片段 + tools 结果）
      → llm Gateway 调模型
      → graph 解析图 JSON → 校验
      → 不通过 → service 带 issues 再调（最多 3 轮）
      → 通过 → 返回图 JSON + 校验报告

[过程] 全程 SSE 推事件（思考 / 工具 / 校验 / token），协议见 features.md 的 SSE 条目
[Agent] agent 循环：Thought → Action(tools) → Observation 回喂 LLM → 再决策（步数上限兜底）
[会话] session 存消息与状态；Redis 优先，不可用降级内存
```

## 三、架构不变式（改代码时不得破坏）

1. 所有 LLM 调用必须经 `llm/` 网关收口，业务代码不得直连 Provider
2. 出图必须走结构化 JSON 契约 + 校验循环，禁止裸文本直出
3. 校验问题必须带 field / reason / hint 明细，不允许只报数量
4. Agent 每一步的 Observation（观察结果，即工具执行返回的内容）必须回喂 LLM，再决策下一步
5. 新增跨包依赖前先更新第一节的表；文档与代码不一致时先改文档

## 四、前端架构

- React 18 + React Flow + Zustand + Vite + TypeScript（strict）
- 状态统一进 Zustand store，关键状态持久化到 localStorage
- **先设计后编码**：阶段 6 先出 HTML 设计稿（色板 / 布局 / 组件规范），用户确认后再实现
