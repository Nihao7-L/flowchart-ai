# FlowAI - AI 流程图生成器

用自然语言描述业务流程，AI 自动生成流程图。

## 技术栈

- **后端**: Spring Boot 3.2 + Java 17
- **AI**: Gemini 2.0 Flash
- **渲染**: PlantUML → SVG
- **前端**: 原生 HTML/CSS/JS（零框架）

## 快速开始

1. 配置 API Key：设置环境变量 `LLM_API_KEY=你的Gemini_API_Key`
2. 如果使用代理，IDEA 启动参数加：`-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`
3. 运行 `FlowchartApplication`
4. 浏览器打开 http://localhost:8080

## 项目结构

src/main/java/io/github/nihaoljx/flowchart/
├── FlowchartApplication.java # 启动入口
├── client/
│ └── AiApiClient.java # Gemini API 调用
├── controller/
│ └── DiagramController.java # REST 接口
├── model/
│ ├── FlowchartData.java # 流程图数据模型
│ └── Result.java # 统一响应格式
└── service/
├── PromptService.java # Prompt 模板
├── ParserService.java # JSON 解析 + 校验
└── DiagramService.java # PlantUML 转换 + SVG 渲染


## 核心流程

用户输入文字 → PromptService 拼 prompt
→ AiApiClient 调 Gemini API
→ ParserService 解析 JSON 并校验
→ DiagramService JSON→PlantUML→SVG
→ 前端展示 + 下载
