# 任务39 实现代码：大厂式 Function Calling（让 LLM 长手脚）

> 定位：把"画啥图/RAG"的决策权，从前端写死升级成 **LLM 自己决定调哪个真实工具**（读文件 / 联网 / 检索知识库 / 跑代码）。  
> 这 4 个工具都是**真能干活**的（不是路由版那种"只选类型"），对应你之前说的：联网搜索、代码执行、文件系统、知识库检索。  
> 落盘方式：新建 5 个文件 + 改动 6 个文件。每个文件标注「新增 / 改动 + 在哪插」。



---

## 一、文件清单

| #  | 文件                                     | 动作 | 说明                                   |
| -- | -------------------------------------- | -- | ------------------------------------ |
| 1  | `client/Tool.java`                     | 新增 | 工具抽象接口（仿 MCP 的 tool 定义）              |
| 2  | `service/ReadFileTool.java`            | 新增 | 真实能力：读本地文件（零依赖，立刻能跑）                 |
| 3  | `service/WebSearchTool.java`           | 新增 | 真实能力：联网搜索（需 webSearchKey）            |
| 4  | `service/RetrieveDocumentTool.java`    | 新增 | 真实能力：检索知识库（包装现有 RagService）          |
| 5  | `service/CodeExecuteTool.java`         | 新增 | 真实能力：沙箱跑代码（需 sandboxUrl，骨架）          |
| 6  | `service/ToolRegistry.java`            | 新增 | 注册表：收集所有 Tool，拼成发给 LLM 的 tools JSON  |
| 7  | `service/ToolExecutor.java`            | 新增 | 核心：调 LLM 拿 tool_calls → 后端真执行 → 回灌素材 |
| 8  | `client/LlmProvider.java`              | 改动 | 接口加 `chatWithTools` default 方法       |
| 9  | `client/OpenAiCompatibleProvider.java` | 改动 | 实现 `chatWithTools`（带 tools 参数）       |
| 10 | `client/FallbackLlmProvider.java`      | 改动 | 转发 `chatWithTools`                   |
| 11 | `client/CachingLlmProvider.java`       | 改动 | 转发 `chatWithTools`（不缓存）              |
| 12 | `model/GenerateRequest.java`           | 改动 | 加 `useTool` 字段                       |
| 13 | `controller/DiagramController.java`    | 改动 | 加 useTool 分支 + 抽出 `doGenerate`       |

---

## 二、新增文件

### 1. `client/Tool.java`（完整文件）

```java
package io.github.nihaoljx.flowchart.client;

import java.util.Map;

/**
 * 任务39 能力版：工具抽象（仿 MCP 的 tool 定义）
 * 每个真实能力工具实现这个接口，注册进 ToolRegistry 即被 LLM 调用。
 * 注意：execute() 永远在【后端】跑，LLM 永远看不到也跑不了这段代码，
 *       它只通过 tools JSON 看到 name/description/parameters 这三样"能力菜单"。
 */
public interface Tool {
    String name();                    // 工具名，如 "read_file"
    String description();            // 给 LLM 看的自然语言说明（决定它何时调）
    String parametersJson();         // OpenAI function calling 的 parameters schema（JSON 字符串）
    String execute(Map<String, Object> args) throws Exception; // 后端真去干，返回结果文本
}
```

### 2. `service/ReadFileTool.java`（完整文件，零外部依赖）

```java
package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 真实工具：读本地文件内容，作为出图素材（LLM 长出"读文件"的手脚）。
 * 安全：限定在程序运行目录内，禁止越权读系统文件。
 */
@Component
public class ReadFileTool implements Tool {

    // 只允许读程序运行目录（项目根）下的文件，防越权
    private final String allowedDir = System.getProperty("user.dir");

    @Override public String name() { return "read_file"; }

    @Override public String description() {
        return "读取项目目录内的文本文件（.txt/.md/.json/.plantuml）内容，作为画图参考素材。当用户提到某个具体文件名时使用。";
    }

    @Override public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"相对项目根目录的文件路径，如 docs/login.md\"}},\"required\":[\"path\"]}";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String p = String.valueOf(args.getOrDefault("path", ""));
        if (p.isBlank()) return "ERROR: 缺少 path 参数";

        Path root = Path.of(allowedDir).normalize();
        Path path = root.resolve(p).normalize();
        if (!path.startsWith(root)) return "ERROR: 路径越权，拒绝读取";
        if (!Files.exists(path) || !Files.isRegularFile(path)) return "ERROR: 文件不存在";

        String content = Files.readString(path);
        if (content.length() > 8000) content = content.substring(0, 8000) + "\n...[已截断 8000 字]";
        return "【文件 " + p + " 内容】\n" + content;
    }
}
```

### 3. `service/WebSearchTool.java`（完整文件，需搜索 key）

```java
package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * 真实工具：联网搜索（需 llm.webSearchKey，如 SerpAPI）。
 * 不配 key 时 execute 直接返回提示，不阻断主流程（降级）。
 */
@Component
public class WebSearchTool implements Tool {

    @Value("${llm.webSearchKey:}") private String apiKey;

    @Override public String name() { return "web_search"; }

    @Override public String description() {
        return "当画图需要最新资料/外部知识（模型训练数据可能没覆盖）时联网搜索，返回网页摘要，避免瞎编。";
    }

    @Override public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"搜索关键词\"}},\"required\":[\"query\"]}";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        if (apiKey.isBlank()) return "ERROR: 未配置 llm.webSearchKey，跳过联网搜索";
        String q = String.valueOf(args.getOrDefault("query", ""));
        // 以 SerpAPI 为例，换成你有的搜索服务即可
        String url = "https://serpapi.com/search.json?q=" + URI.create(q) + "&api_key=" + apiKey;
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json").build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        return "【搜索 " + q + " 结果】\n" + body.substring(0, Math.min(4000, body.length()));
    }
}
```

### 4. `service/RetrieveDocumentTool.java`（完整文件，包装现有 RagService）

```java
package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 真实工具：检索知识库（包装现有 RagService.retrieve）。
 * 让 LLM 自己决定"要不要查知识库"，替代前端 useRag 勾选框的硬控制。
 */
@Component
public class RetrieveDocumentTool implements Tool {

    private final RagService ragService;

    public RetrieveDocumentTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override public String name() { return "retrieve_document"; }

    @Override public String description() {
        return "从已上传的知识库（用户上传的文档）中检索与问题相关的内容，作为画图参考。当用户提到参考文档/资料时使用。";
    }

    @Override public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"检索问题或关键词\"}},\"required\":[\"query\"]}";
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (!ragService.isEnabled()) return "ERROR: 知识库未配置（缺 embedding key），跳过";
        try {
            String q = String.valueOf(args.getOrDefault("query", ""));
            String ctx = ragService.retrieve(q);
            return ctx.isBlank() ? "知识库无相关内容" : "【知识库检索结果】\n" + ctx;
        } catch (Exception e) {
            return "ERROR: 检索失败 " + e.getMessage();
        }
    }
}
```

### 5. `service/CodeExecuteTool.java`（完整文件，沙箱骨架）

```java
package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * 真实工具：在沙箱里执行 Python 代码（算数/数据处理），返回 stdout。
 * 注意：绝不本地 Runtime.exec！必须调一个隔离的沙箱执行服务（sandboxUrl）。
 * 没配 sandboxUrl 时降级跳过。数据库查询/浏览器自动化按同样套路实现 Tool 接口即可。
 */
@Component
public class CodeExecuteTool implements Tool {

    @Value("${llm.sandboxUrl:}") private String sandboxUrl;

    @Override public String name() { return "code_execute"; }

    @Override public String description() {
        return "在沙箱中执行 Python 代码（算数/数据处理/画图数据准备），返回 stdout 结果。用于需要计算才能确定的图表数据。";
    }

    @Override public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"description\":\"要执行的 Python 代码\"}},\"required\":[\"code\"]}";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        if (sandboxUrl.isBlank()) return "ERROR: 未配置 llm.sandboxUrl（沙箱执行服务），跳过代码执行";
        String code = String.valueOf(args.getOrDefault("code", ""));
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(sandboxUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":" + escape(code) + "}"))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        return "【代码执行结果】\n" + body.substring(0, Math.min(4000, body.length()));
    }

    private String escape(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
```

### 6. `service/ToolRegistry.java`（完整文件，仿 MCP 注册表）

```java
package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具注册表：Spring 自动把所有 @Component 的 Tool 实现注入进来，
 * 拼成 OpenAI 要求的 tools 数组 JSON（发给 LLM 的"能力菜单"）。
 * 新增工具 = 写一个实现 Tool 的类 + 加 @Component，无需改这里。
 */
@Component
public class ToolRegistry {

    private final List<Tool> tools;

    public ToolRegistry(List<Tool> toolBeans) { // Spring 自动收集所有 Tool bean
        this.tools = toolBeans;
    }

    /** 拼成 OpenAI tools 数组 JSON 字符串 */
    public String toolsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tools.size(); i++) {
            Tool t = tools.get(i);
            sb.append("{\"type\":\"function\",\"function\":{")
               .append("\"name\":\"").append(t.name()).append("\",")
               .append("\"description\":\"").append(t.description()).append("\",")
               .append("\"parameters\":").append(t.parametersJson())
               .append("}}");
            if (i < tools.size() - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    /** 按名字取工具（执行时用） */
    public Tool get(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }
}
```

### 7. `service/ToolExecutor.java`（完整文件，多轮回灌核心）

```java
package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务39 核心：Tool Calling 执行器（大厂能力版）
 *
 * 流程：调 LLM 拿 tool_calls → 后端真执行工具 → 把结果作为"素材"返回。
 * 设计要点：
 *  - LLM 只当"调度员"决定调哪个工具 + 传什么参数，真正干活在后端 execute()。
 *  - 工具结果不直接让 LLM 生成最终图（出图必须走 chatStructured 保证 JSON 契约），
 *    而是作为 context 素材并入出图 prompt，与 RAG 注入同理。
 *  - 任何异常都降级为 null（无素材），让主流程退化为普通生成，不阻断出图。
 */
@Service
public class ToolExecutor {

    private final LlmProvider llmProvider;
    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolExecutor(LlmProvider llmProvider, ToolRegistry registry) {
        this.llmProvider = llmProvider;
        this.registry = registry;
    }

    private static final String SYSTEM_PROMPT =
        "你是 FlowAI 的助手。用户要画图时，若需要参考本地文件、联网资料或知识库，先调用对应工具获取素材，"
        + "再把素材综合进最终回答。只有在确需外部信息时才调用工具，不要编造工具未返回的内容。";

    /** 多轮回灌：返回补充后的上下文素材（可能 null = LLM 没调工具） */
    public String collectContext(String userText) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", userText));

        StringBuilder collected = new StringBuilder();
        try {
            // 第一轮：让 LLM 决定要不要调工具（返回 tool_calls 或纯 content）
            String raw = llmProvider.chatWithTools(messages, registry.toolsJson());
            List<ToolCall> calls = parseToolCalls(raw);

            if (calls.isEmpty()) {
                return null; // LLM 觉得不需要工具 → 无素材，走普通生成
            }

            // 后端真去执行每个工具，把结果拼成素材
            for (ToolCall c : calls) {
                Tool tool = registry.get(c.name);
                if (tool == null) continue;
                String result = tool.execute(c.args);   // ← 这里才真正"长手脚"
                collected.append("\n").append(result);
                // （完整大厂做法：把 assistant(tool_calls) + tool(result) 回灌 messages 再调一次 LLM；
                //  本项目出图强约束 JSON，故工具结果并入 context 走 chatStructured，等价且更稳）
            }
        } catch (Exception e) {
            System.err.println("Tool 路由/执行失败，降级无素材: " + e.getMessage());
            return null;
        }
        return collected.toString().isBlank() ? null : collected.toString();
    }

    /** 解析 provider 返回的 tool_calls：{"tool_calls":[{id,name,arguments}]} */
    @SuppressWarnings("unchecked")
    private List<ToolCall> parseToolCalls(String raw) {
        List<ToolCall> out = new ArrayList<>();
        try {
            Map<String, Object> root = mapper.readValue(raw, Map.class);
            Object callsObj = root.get("tool_calls");
            if (callsObj == null) return out; // 普通 content，无工具调用
            List<Map<String, Object>> calls = (List<Map<String, Object>>) callsObj;
            for (Map<String, Object> c : calls) {
                String id = String.valueOf(c.get("id"));
                String name = String.valueOf(c.get("name"));
                Map<String, Object> args = new LinkedHashMap<>();
                Object a = c.get("arguments");
                if (a instanceof String s && !s.isBlank()) {
                    args = (Map<String, Object>) mapper.readValue(s, Map.class);
                } else if (a instanceof Map m) {
                    args = (Map<String, Object>) m;
                }
                out.add(new ToolCall(id, name, args));
            }
        } catch (Exception ignored) {
            // raw 是纯文本 → 返回空，上层降级
        }
        return out;
    }

    private record ToolCall(String id, String name, Map<String, Object> args) {}
}
```

---

## 三、改动文件

### 8. `client/LlmProvider.java` — 加 default 方法

**顶部补 import：**

```java
import java.util.List;
import java.util.Map;
```

**在 `chatStructured` default 方法后面插入：**

```java
    /**
     * 任务39：Tool Calling 路由调用。
     * 带 tools 参数让 LLM 自己决定调用哪个工具，返回 tool_calls 的结构化 JSON，
     * 或普通 content 文本（取决于模型是否产生工具调用）。
     *
     * @param messages  完整对话消息列表（含 system/user，role 区分）
     * @param toolsJson OpenAI 格式的 tools 数组 JSON 字符串
     * @return 调用了工具 -> {"tool_calls":[{id,name,arguments}]}；否则 -> 纯文本 content
     */
    default String chatWithTools(List<Map<String, Object>> messages, String toolsJson) throws Exception {
        // 默认退化：不支持的 Provider 把工具提示当普通 prompt（上层会降级）
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            sb.append(m.get("content")).append("\n");
        }
        return chat(sb.toString());
    }
```

### 9. `client/OpenAiCompatibleProvider.java` — 实现 `chatWithTools`

（该类已 import `List`/`Map`/`LinkedHashMap`，无需补 import）

**在 `chatStructured` 方法后面插入：**

```java
    /**
     * 任务39：Tool Calling 调用。
     * 带 tools + tool_choice=auto，不带 response_format（两者互斥）。
     * 模型返回 tool_calls 时构造成 {"tool_calls":[...]} 返回；否则返回 content。
     */
    @Override
    public String chatWithTools(List<Map<String, Object>> messages, String toolsJson) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModel());
        payload.put("messages", messages);
        payload.put("tools", objectMapper.readValue(toolsJson, List.class));
        payload.put("tool_choice", "auto");

        String body = objectMapper.writeValueAsString(payload);
        String rawResponse = sendWithRetry(body);
        System.out.println("=== Tool Calling 原始响应: " + rawResponse);
        return extractToolOrContent(rawResponse);
    }

    @SuppressWarnings("unchecked")
    private String extractToolOrContent(String rawResponse) throws Exception {
        Map<String, Object> root = objectMapper.readValue(rawResponse, Map.class);

        Map<String, Object> usage = (Map<String, Object>) root.get("usage");
        if (usage != null) {
            int promptTokens = ((Number) usage.get("prompt_tokens")).intValue();
            int completionTokens = ((Number) usage.get("completion_tokens")).intValue();
            if (usageService != null) {
                usageService.record(config.getModel(), promptTokens, completionTokens);
            }
            Map<String, Object> tokenData = new LinkedHashMap<>();
            tokenData.put("promptTokens", promptTokens);
            tokenData.put("completionTokens", completionTokens);
            tokenData.put("totalTokens", promptTokens + completionTokens);
            tokenData.put("model", config.getModel());
            ProgressContext.publish(ProgressEvent.of("token_usage", "Token 用量", config.getName(), tokenData));
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new Exception("LLM 返回中没有 choices 字段，原始响应: " + rawResponse);
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new Exception("LLM 返回中没有 message 字段，原始响应: " + rawResponse);
        }
        Object toolCalls = message.get("tool_calls");
        if (toolCalls != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool_calls", toolCalls);
            return objectMapper.writeValueAsString(out);
        }
        Object content = message.get("content");
        return content == null ? "" : content.toString();
    }
```

### 10. `client/FallbackLlmProvider.java` — 转发（已 import List/Map）

**在 `chatStructured` 方法后面插入：**

```java
    @Override
    public String chatWithTools(List<Map<String, Object>> messages, String toolsJson) throws Exception {
        return withFallback(p -> p.chatWithTools(messages, toolsJson));
    }
```

### 11. `client/CachingLlmProvider.java` — 转发（不缓存）

**顶部补 import：**

```java
import java.util.List;
```

**在 `chatStructured` 方法后面插入：**

```java
    @Override
    public String chatWithTools(List<Map<String, Object>> messages, String toolsJson) throws Exception {
        // tool calling 不进缓存：结果依赖模型实时决策，messages 结构复杂不便做 key
        return delegate.chatWithTools(messages, toolsJson);
    }
```

### 12. `model/GenerateRequest.java` — 加 `useTool` 字段

**在 `useRag` 字段后面加一行（record 字段顺序变为 text/type/format/useRag/useTool）：**

```java
        @Schema(description = "是否启用 Tool Calling 路由（由 LLM 自己决定调工具：读文件/联网/查知识库/跑代码）", example = "false")
        Boolean useTool
```

### 13. `controller/DiagramController.java`

**13a. 顶部 import 加一行：**

```java
import io.github.nihaoljx.flowchart.service.ToolExecutor;
```

**13b. 注入字段（在 `@Autowired private RagService ragService;` 后面加）：**

```java
    @Autowired private ToolExecutor toolExecutor;
```

**13c. 替换 `/api/generate` 方法体为下面版本（抽出 `doGenerate` 复用）：**

```java
    @PostMapping("/api/generate")
    public Result<?> generate(@RequestBody GenerateRequest req) {
        try {
            String userText = req.text();
            String type = req.type() != null ? req.type() : "flowchart";
            String format = req.format() != null ? req.format() : "svg";

            if (!llmProvider.isConfigured()) {
                return Result.error(503, "服务未配置：请联系管理员设置 LLM_API_KEY");
            }
            if (userText == null || userText.isBlank()) {
                return Result.error(400, "请输入流程描述");
            }
            if (userText.length() > 2000) {
                return Result.error(400, "描述过长，请精简到 2000 字以内");
            }
            if (!"flowchart".equals(type) && !"mindmap".equals(type) && !"architecture".equals(type)) {
                return Result.error(400, "不支持的图表类型: " + type);
            }

            // ---- 任务39：Tool Calling 模式（大厂能力版，LLM 自己决定调工具）----
            if (Boolean.TRUE.equals(req.useTool())) {
                String extraContext = toolExecutor.collectContext(userText);
                Map<String, Object> result = doGenerate(userText, type, format,
                        (extraContext != null ? extraContext + "\n" : ""), null);
                return Result.success(result);
            }

            // ---- 任务38+B：RAG 检索增强（带元数据回传前端）----
            boolean useRag = req.useRag() != null && req.useRag();
            String context = "";
            RagService.RagResult ragResult = null;
            if (useRag) {
                if (!ragService.isEnabled()) {
                    return Result.error(503, "RAG 未配置：请在 application.yml 设置 llm.embedding.api-key（硅基流动免费 key）");
                }
                ragResult = ragService.retrieveWithMeta(userText);
                context = ragResult.context();
                if (ragResult.degraded()) {
                    System.out.println("⚠️ RAG 检索降级：embedding 调用失败，本次生成未使用知识库");
                }
            }

            Map<String, Object> result = doGenerate(userText, type, format, context, ragResult);
            return Result.success(result);

        } catch (ValidationException e) {
            return Result.error(400, e.getMessage(), e.getIssues());
        } catch (Exception e) {
            System.err.println("生成图表失败: " + e.getMessage());
            e.printStackTrace();
            return Result.error(500, "AI 生成失败：" + e.getMessage());
        }
    }
```

**13d. 新增 `doGenerate` 私有方法（放在 `buildMermaid` 方法前面）：**

```java
    /**
     * 任务39：抽出核心出图逻辑，useTool / useRag 共用
     * @param ragResult 检索命中（可为 null），用于回传前端引用面板
     */
    private Map<String, Object> doGenerate(String text, String type, String format,
                                           String context, RagService.RagResult ragResult) throws Exception {
        if ("mermaid".equals(format)) {
            return buildMermaid(text, type, context, ragResult);
        }
        String prompt = promptService.buildPrompt(text, type, context);
        String schema = promptService.loadSchema(type); // 任务33：加载该类型 JSON Schema
        String llmText = llmProvider.chatStructured(prompt, schema); // 任务33：结构化输出

        String plantUml;
        Map<String, Object> result = new HashMap<>();
        switch (type) {
            case "mindmap":
                MindmapData mindmap = parserService.parseMindmap(llmText);
                plantUml = diagramService.buildMindMap(mindmap);
                result.put("data", mindmap);
                break;
            case "architecture":
                FlowchartData arch = parserService.parseArchitecture(llmText);
                plantUml = diagramService.buildArchitecture(arch);
                result.put("data", arch);
                break;
            default:
                FlowchartData data = parserService.parse(llmText);
                plantUml = diagramService.buildPlantUml(data);
                result.put("data", data);
        }
        String svg = diagramService.renderToSvg(plantUml);
        result.put("svg", svg);
        result.put("plantUml", plantUml);
        result.put("type", type);
        putRagResult(result, ragResult);
        return result;
    }
```

---

## 四、application.yml 配置（可选，按需加）

```yaml
llm:
  webSearchKey:   # 填 SerpAPI 等搜索服务 key；不填则 web_search 工具自动降级跳过
  sandboxUrl:     # 填沙箱执行服务地址；不填则 code_execute 工具自动降级跳过
```

---

## 五、编译 & 验收

**编译（JDK17，离线）：**

```powershell
D:/maven-home/apache-maven-3.9.5-bin/apache-maven-3.9.5/bin/mvn.cmd -o compile
```

**验收（curl，这就是任务39 的验收点——LLM 自己决定调工具）：**

```bash
curl -X POST http://localhost:8080/api/generate \
  -H "Content-Type: application/json" \
  -d '{"text":"帮我画登录流程，参考 docs/login.md 这个文件","type":"flowchart","format":"svg","useTool":true}'
```

预期：LLM 调 `read_file` → 后端真读 `docs/login.md` → 内容作为素材并入 prompt → 出图。

---

## 六、⚠️ 前置风险（务必看）

1. **主模型必须支持 `tools` 参数**——之前确认 MiMo 不确定支持。验收前请把主模型设成 **kimi**（硅基流动标注支持 function calling），否则 LLM 不返回 `tool_calls`，`collectContext` 降级成"无素材出图"（仍能出图，但没体现"自动调工具"）。
2. **`web_search` / `code_execute` 需 key/沙箱**——不配就跳过（已做降级），`read_file` 零依赖立刻能演示"长手脚"。
3. **扩展新工具**（数据库查询 / 浏览器自动化）：写一个实现 `Tool` 接口的类 + 加 `@Component`，Spring 自动注册进 `ToolRegistry`，不用改任何其它代码。

---

## 七、代码级「注册 / 传输 / 返回 / 执行」对应表

| 阶段         | 代码位置                                                                             | 做了什么                                                     |
| ---------- | -------------------------------------------------------------------------------- | -------------------------------------------------------- |
| 注册（定义能力菜单） | `ToolRegistry.toolsJson()`                                                       | 把所有 `Tool` 的 name/description/parameters 拼成 `tools` JSON |
| 传输（何时发）    | `ToolExecutor.collectContext` → `llmProvider.chatWithTools(messages, toolsJson)` | 仅 `useTool=true` 时，请求体带 `tools` + `tool_choice:"auto"`   |
| 模型返回工具调用   | `OpenAiCompatibleProvider.extractToolOrContent`                                  | 有 `tool_calls` → 返回 `{"tool_calls":[...]}`；否则返回纯文本       |
| 后端执行（长手脚）  | `ToolExecutor.collectContext` 循环 `tool.execute(args)`                            | 真去读文件/联网/检索/跑代码                                          |
| 结果回灌       | 本项目把结果并入 `context` 走 `chatStructured` 出图（与 RAG 同理）                               | 强约束 JSON，比"二次调 LLM 自由生成"更稳                               |
