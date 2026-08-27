# flowchart-ai

用自然语言描述业务或想法，AI 自动生成 **流程图 / 思维导图 / 架构图**，并渲染为 SVG / PNG 直接下载；也可输出 **Mermaid** 源码由浏览器端渲染。

> 教学型项目：从零搭建一个「文字 → LLM → 图表」的 Spring Boot 应用，覆盖 Prompt 工程、LLM 多 Provider 网关（路由 / Fallback / 缓存 / 重试 / 成本审计）、结构化输出、SSE 实时进度、单元测试与容器化。

## ✨ 特性

- 🤖 **AI 驱动**：基于 LLM（OpenAI 兼容协议），默认接入 MiMo / Kimi，文字一键成图
- 🔀 **多 Provider 网关（Harness Engineering）**：固定 / 轮询 / 加权路由、故障自动 Fallback、结果缓存、指数退避重试、Token 成本审计——装饰器模式叠加，不侵入业务代码
- 📊 **四种产物**：流程图（flowchart）、思维导图（mindmap）、架构图（architecture），以及 **Mermaid** 源码（浏览器端渲染）
- 🖼️ **多格式导出**：SVG 矢量 / PNG 位图 / MMD 源码，浏览器直接下载
- 📡 **SSE 实时进度**：生成过程中实时推送「重试 / 切换模型 / Token 用量」等事件
- 📈 **成本统计**：`/api/stats` 返回累计调用、Token 总量与估算成本
- 📖 **API 文档**：集成 springdoc-openapi，启动即获交互式 Swagger 文档
- 🐳 **容器化部署**：多阶段 Dockerfile + docker-compose，一行命令上云（密钥不进镜像）

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.2.0 + Java 17 |
| AI | LLM（OpenAI 兼容协议，默认 MiMo `mimo-v2.5` / Kimi `kimi-k2.7-code-highspeed`） |
| 渲染 | PlantUML 1.2024.7 → SVG / PNG；Mermaid 由前端 mermaid.js 渲染 |
| 前端 | 原生 HTML / CSS / JS（零框架） |
| 文档 | springdoc-openapi 2.3.0（Swagger UI） |
| 部署 | Docker / docker-compose |

## 架构

```
用户输入文字
   ↓
PromptService   ── 按 type 选择模板 + 加载 JSON Schema，拼出结构化 Prompt
   ↓
LlmProvider     ── 统一接口，实际是一条装饰链（见下）
   ↓
ParserService   ── 解析 LLM 返回的 JSON，按 Schema 校验
   ↓
DiagramService  ── JSON → PlantUML 源码 → 渲染 SVG / PNG
（Mermaid 模式跳过 PlantUML，直接把 mermaid 文本交给前端渲染）
   ↓
前端展示 + 下载（SVG / PNG / MMD）
```

**LlmProvider 装饰链（多 Provider 网关）**：

```
RoutingLlmProvider   ── 按策略选主/备模型（fixed / round_robin / weighted）
  └ FallbackLlmProvider  ── 主用失败自动切下一个
      └ CachingLlmProvider   ── 相同输入命中缓存直接返回
          └ OpenAiCompatibleProvider  ── 真实调 LLM（OpenAI 兼容端点）+ usage 日志
```

SSE 进度通过 `ProgressContext`（ThreadLocal 出水口）+ `ProgressSink` 实现：Provider 内部 `publish(event)` → 后台线程写入 `SseEmitter` → 浏览器实时显示。

## 项目结构

```
src/main/java/io/github/nihaoljx/flowchart/
├── FlowchartApplication.java          # 启动入口
├── client/                            # LLM 网关层
│   ├── LlmProvider.java               # LLM 统一接口
│   ├── OpenAiCompatibleProvider.java  # OpenAI 兼容实现（含 usage 日志）
│   ├── RoutingLlmProvider.java        # 路由策略
│   ├── FallbackLlmProvider.java       # 故障转移
│   ├── CachingLlmProvider.java        # 结果缓存
│   ├── ProviderConfig.java            # 单个 Provider 配置
│   ├── ProviderCapability.java        # json_schema / json_object
│   ├── RoutingStrategy.java           # fixed / round_robin / weighted
│   ├── CacheEntry.java                # 缓存条目
│   └── stream/                        # SSE 进度（ProgressContext / ProgressEvent / ProgressSink / RoutingContext）
├── config/
│   ├── LlmProperties.java             # 绑定 llm.* 配置
│   ├── LlmProviderConfig.java         # 多 Provider 装配工厂
│   └── OpenApiConfig.java             # Swagger 文档元信息
├── controller/
│   └── DiagramController.java         # REST 接口（generate / stream / download / stats / health）
├── model/
│   ├── FlowchartData.java / MindmapData.java   # 数据模型
│   ├── Result.java / ValidationIssue.java      # 统一响应 + 校验错误
│   ├── UsageRecord.java               # 单次调用用量记录
│   ├── GenerateRequest.java / DownloadRequest.java  # 请求 DTO（record）
└── service/
    ├── PromptService.java             # Prompt 模板 + Schema 加载（多类型）
    ├── ParserService.java             # JSON 解析 + 校验
    ├── DiagramService.java            # PlantUML 转换 + SVG/PNG 渲染
    ├── UsageService.java              # 成本统计
    └── ValidationException.java

src/main/resources/
├── application.yml                    # 配置（含明文 Key，**已被 gitignore + dockerignore 排除，不入库不进镜像**）
├── application-example.yml           # 配置样例（占位 Key，可安全入库，复制为 application.yml 后填真实 Key）
├── static/index.html, app.js, style.css   # 前端页面
├── templates/                        # 各图表类型的 Prompt 模板
└── schemas/                          # 各图表类型的 JSON Schema（结构化输出校验）
```

## 快速开始

### 1. 准备配置（含 Key，不入库）

```bash
# 复制样例配置
cp src/main/resources/application-example.yml src/main/resources/application.yml
# 编辑 application.yml，把 sk-xxx 换成你自己的真实 Key
```

> ⚠️ `application.yml` 含明文 Key，已被 `.gitignore` 与 `.dockerignore` 排除——**既不会提交到 git，也不会烤进 Docker 镜像**。请勿手动把它加回跟踪。

### 2. 启动

**方式 A（开发）**：IDEA 直接运行 `FlowchartApplication`。

**方式 B（命令行）**：

```cmd
mvn package -DskipTests
java -jar target/flowchart-0.0.1-SNAPSHOT.jar
```

### 3. 打开

浏览器访问 **http://localhost:8080**

## API 接口

启动后访问 **http://localhost:8080/swagger-ui/index.html** 查看交互式文档并可在线调试。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/health` | 健康检查（容器探活） |
| POST | `/api/generate` | 生成图表（文字 → SVG + PlantUML / Mermaid） |
| GET  | `/api/generate/stream` | 生成图表（SSE 实时进度，参数 `text`/`type`/`model`/`format`） |
| POST | `/api/download` | 下载图表（PlantUML → SVG/PNG 文件） |
| GET  | `/api/stats` | 成本统计（调用次数、Token 总量、估算成本） |

**`/api/generate` 请求体**：

```json
{
  "text": "用户输入账号密码→系统验证→进入首页",
  "type": "flowchart",
  "format": "svg"
}
```

- `type`：`flowchart` / `mindmap` / `architecture`
- `format`：`svg` / `png` / `mermaid`（mermaid 返回 `mermaid` 字段源码，由前端渲染，可选下载 `.mmd`）

**响应**（节选）：

```json
{
  "code": 0,
  "data": {
    "svg": "<svg ...>",
    "plantUml": "@startuml\n...",
    "type": "flowchart"
  }
}
```

## 配置说明（`application.yml`）

```yaml
llm:
  routing-strategy: fixed        # fixed | round_robin | weighted
  cache-ttl-minutes: 60
  providers:
    - name: mimo
      base-url: https://api.xiaomimimo.com/v1/chat/completions
      api-key: sk-你的Key
      model: mimo-v2.5
      capability: json_object     # MiMo 只支持 json_object
    - name: kimi
      api-key: sk-你的Key
      model: kimi-k2.7-code-highspeed
      capability: json_schema     # Kimi 支持 json_schema + strict
  retry: { max-attempts: 3, initial-backoff-ms: 1000, max-backoff-ms: 8000 }
  pricing: { "kimi-k2.7-code-highspeed": 12.0, default: 10.0 }
```

- `capability`：`json_object`（模型只返回 JSON）或 `json_schema`（支持 strict 结构化输出），决定 `chatStructured` 的传参方式。
- 不写 `providers` 时，可用顶层 `llm.base-url/api-key/model` 单份配置（向后兼容）。

## Docker 部署

> ⚠️ **密钥安全**：`Dockerfile` 用 `COPY src ./src`，会拷本地文件。`.dockerignore` 已排除 `application.yml`，所以 Key **不会进镜像**。请勿把 `application.yml` 从 `.dockerignore` 移除，也不要改用 `COPY` 把配置打进镜像。生产建议用挂载卷或环境变量注入 Key。

```cmd
# 构建并启动（本地 application.yml 通过挂载注入，不烤进镜像）
docker compose up --build -d

# 查看日志
docker compose logs -f

# 停止
docker compose down
```

`docker-compose.yml` 通过挂载本地 `application.yml` 到容器内，运行时读取，避免密钥固化到镜像层。

## 测试

```cmd
mvn test
```

约 **65 个单元测试（7 个测试类）**，覆盖 Provider 网关（缓存 / Fallback / 兼容端点）、解析、构建、Prompt、成本统计等核心逻辑，均为纯逻辑测试，不依赖 Spring / 网络。

## 路线图 / 学习延伸

- 接 RAG：让 AI 基于私有知识库生成图表
- 接 Agent：多步推理自动产出复杂架构
- 限流（Rate Limiting）/ Token 预算 接入网关装饰链
