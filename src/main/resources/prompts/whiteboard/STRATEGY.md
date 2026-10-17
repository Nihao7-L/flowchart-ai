# 提示词策略（分层模板 + 热加载）

本文件是**给人和 CI 看的策略说明**，不发给模型。改提示词前先读它。

配套：`system-prompt.md`（内核）、`diagrams/*.md`（图型模板）、`CHANGELOG.md`（版本史）。

---

## 1. 为什么要分层

v10.13.0 时内核 md 到 9987 字节，字节闸门上限是 10000 —— **只剩 13 字节**。
从此「加一种图的规则」和「改通用规则」互相绑架：想给时序图加两句话，
就得从通用规则里删两句话。这是死结，不是勤奋能解决的。

分层后：**内核只装所有图型共用的东西，每种图的专项规则各自一个文件、按需注入。**
v10.15.0 再往前推一步：内核 md 6531B → **3223B（-50.7%）**，图型模板 1 → **25 种**。

| 层 | 文件 | 什么时候发 | 预算 |
|---|---|---|---|
| 内核层 | `system-prompt.md` | **永远发** | 与契约段合计 < 10000B（闸门 `singleBlockBudget`）；当前 6580B |
| 契约层 | 由 `schemas/scene.schema.json` 现算 | **永远发** | 3355B，不占 md 预算 |
| 图型层 | `diagrams/<type>.md` | **命中关键词才发** | 内核 + 最大图型模板 < 12000B（闸门 `systemBlockWithDiagramTemplateBudget`） |

## 1.1 图型 ≠ 版面（v10.15.0 的关键解耦）

**图型（diagramType）是"这是什么图"，版面（layout）是"用哪套排版算法"。**
两者数量完全不对等：图型 25 种，版面家族只有 generic 与 sequence 两套。

模板 frontmatter 里的 `layout` 决定用哪套版面（不写 = generic）：

```markdown
---
diagramType: gantt      # 图型：模型照抄这个值
layout: generic         # 版面：复用通用排版
---
```

为什么必须拆开：在拆之前，`SubmitSpecTool` 只认**布局家族名**当合法图型，于是
「加一种图型」被绑架成「写一套排版器」—— 模型照模板声明了 `gantt`，却被判
**「未知图型」**打回修正轮，白烧一轮。现在合法图型 = 布局家族 ∪ 已注册图型模板，
排版按 `layout` 路由。**加图型仍然零改 Java。**

## 2. 装配顺序（不要调）

```
内核 md  +  契约段  +  [图型模板段]
└── 全请求共享 ──┘     └── 唯一变量 ──┘
```

内核与契约对所有请求逐字节相同，放在最前 = **KV-cache 的稳定前缀**；
图型模板是唯一的变量，放在最后。

> ⚠️ 把图型段插到中间会把"内核 + 契约"这个公共前缀切碎，每换一种图就丢一次缓存。
> 这条由 `PromptServiceContractTest.diagramTemplateAppendedAfterContract` 锁着。

## 3. 加一种新图型（三步，不需要改 Java）

① 在 `diagrams/` 下丢一个 md，文件名随意，frontmatter 必写 `diagramType` 与 `keywords`：

```markdown
---
diagramType: mindmap
version: 1.0.0
title: 思维导图
keywords: 思维导图, 脑图, mindmap
layout: generic
---

中心主题在正中，一级分支放射状展开……
```

② `layout` 只有 `generic` 与 `sequence` 两个值可选（省略 = generic）。
**只有"版面必须特殊定制"的图才写 `sequence`** —— 目前只有时序图（生命线 + 消息逐行）。

③ 跑一遍 `mvn -o test`（`DiagramPromptRegistryTest` 会自动把它算进覆盖面、路由与预算断言）。

### 写模板的四段式（照这个写，模型才画得对）

| 段 | 写什么 |
|---|---|
| 一句话定义 | 「X 图 = …」——先让模型确认它面对的是哪种图 |
| **必须声明 diagramType** | 不声明就按通用图排，版面全错 |
| 节点怎么建 | 什么形状、label 里写什么、哪些东西**不要**单独建节点（比如 ER 图的属性） |
| 连线什么语义 | direction / relation 用哪个值、标签写什么 |
| 常见画错 | 这种图最容易犯的错（比如流程图的回流线压主链） |

### 关键词的坑

路由是**最长关键词优先**的包含匹配。图种一多，短词会互相抢。已踩过并锁死的：

| 用户说 | 该命中 | 不该被抢走 |
|---|---|---|
| 组织架构图 | org-chart | 架构图（architecture） |
| 跨职能流程图 | swimlane | 流程图（flowchart） |

规则：**容易冲突的那个图型，关键词要比对手更长更具体**（写「跨职能流程」而不是「跨职能」）。
新加图型后跑 `DiagramPromptRegistryTest.longestKeywordDisambiguates` 验一遍。

**关键词是确定性的，不是让模型判断**：按 `keywords` 在用户原话里做包含匹配，
命中多个取**最长关键词**（"流程图"比"流程"具体）。刻意不加 LLM 分类那一跳 ——
那要多付一次调用，而我们的 SLA 是百秒级。

## 4. 热加载

三层来源，**后者覆盖前者**（抄 Spring AI Lab 的优先级思路）：

| 优先级 | 来源 | 说明 |
|---|---|---|
| 低 | classpath 内置 `diagrams/*.md` | 打进 jar，保底可用 |
| 高 | 外部目录 `chartflow.prompts.external-dir` | 同名 `diagramType` 覆盖内置；新图型是新增，不挤掉内置 |

配置（默认空 = 只用内置，不监听）：

```yaml
chartflow:
  prompts:
    external-dir: /opt/chartflow/prompts   # 留空或注释掉 = 关闭热加载
```

- 改动后**无需重启**：`WatchService` 监听目录，防抖 250ms 后整体重编译。
- **失败保旧**：某个文件解析失败只跳过它，该图型继续用上一次成功的版本
  （内置版本或上一版外部版本），并打 WARN。改坏提示词不会让出图链路挂掉。
- **删掉外部文件即回滚**：下次重编译自动回退到 classpath 内置版本，不需要"撤销"操作。

> classpath 内置那份在 jar 里，监听不到也不必监听 —— 开发时靠 IDE 复制 resources，
> 生产时靠重启。

## 5. 改提示词的纪律

1. **改 md 就升版本号**：`system-prompt.md` 治理头里的 `版本 vX.Y.Z`，
   `promptVersion()` 从那里现读（写死常量会漂移，历史上因此记错过大半年的版本）。
2. **改 md 就补 CHANGELOG**，且**新版写在文件最上面**
   （`changelogLatestEntryIsCurrentVersion` 会拦"追加到文件尾"）。
3. **只能直接编辑源文件**，绝不从"渲染过的 Markdown 视图"复制粘贴 —— 那样会丢掉
   L0 治理头、把行内反引号转义掉。
4. 改完必跑：`mvn -o test`（382+）与 `mvn -o checkstyle:check`。
5. 字节算不过时**先删旧的再添新的**，不要去抬闸门。闸门是护栏不是橡皮筋。

## 6. 借鉴来源（2026-10-17 调研）

| 来源 | 抄了什么 |
|---|---|
| tldraw Agent Starter Kit | 提示词不是一个大字符串，而是 **PromptPart 拼装**；Mode 决定"这个任务能看到哪些 part" |
| Claude Code Skills / Cursor Rules | **文件即模板 + 条件触发 + 渐进式披露**；`description` 是触发面 |
| Langfuse | 版本 + label（production）部署的**运行期治理**思路；回滚 = 把标签挪回上一版 |
| JPrompt | `{{> partial}}` 片段复用、**WatchService 增量热更**、级联依赖更新、Actuator 健康检查 |
| Spring AI Lab Skill 系统 | **三层优先级**（运行时注册 > 外部目录 > classpath 内置）+ `auto-init` |
| draw.io 官方实践 | "Write the diagram type first" —— 图型声明必须前置且显式 |

没抄的：**意图路由（Router Prompt）**——先让小模型分类再分发。我们已是模型在
`submit_spec` 里自声明 `diagramType`，且已有"未注册降级 generic"兜底，
多加一跳只增加延迟。
