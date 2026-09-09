package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实工具：在沙箱里执行 Python 代码（算数/数据处理），返回 stdout。
 * 没配沙箱 / 启动失败时降级跳过（不报错，交给 ToolExecutor 的 Fix2 跳过）。
 *
 * 沙箱服务用开源 onyxdotapp/code-interpreter（Docker 起，本机端口 8000）：
 *   docker run --rm -d --user root -p 8000:8000 onyxdotapp/code-interpreter
 * 接口：POST {url}  body: {"code":"...","timeout_ms":5000,"last_line_interactive":true,"files":[]}
 * 返回：{"stdout":"...","stderr":"...","exit_code":0,"timed_out":false,"duration_ms":145,"files":[]}
 *
 * 沙箱生命周期由 SandboxManager 接管（按需启停 + 60s 闲置关 + JVM 退出联动）；
 * 也可走外部固定 sandboxUrl（llm.sandboxUrl），由 enabled 开关选择。
 */
@Component
public class CodeExecuteTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${llm.sandboxUrl:}") private String sandboxUrl;

    @Autowired private SandboxManager sandboxManager;

    @Override public String name() { return "code_execute"; }

    @Override public String description() {
        return "在沙箱中执行 Python 代码做计算或数据处理（统计、聚合、数值推导），返回 stdout 文本。仅用于计算图表所需的数据，禁止用它生成图片/图表/可视化。";
    }

    @Override public boolean available() {
        // Fix3 + 沙箱自管：自管模式（enabled）下沙箱可按需启，永远可用；外部模式仍按 url 是否配判断
        return sandboxManager.isEnabled() || (sandboxUrl != null && !sandboxUrl.isBlank());
    }

    @Override public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"description\":\"要执行的 Python 代码\"}},\"required\":[\"code\"]}";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String url;
        if (sandboxManager.isEnabled()) {
            // 自管模式：按需启动；启动失败 → 返回 null，交给调用方跳过（Fix2）
            if (!sandboxManager.ensureRunning()) return null;
            url = "http://localhost:" + sandboxManager.getPort() + "/v1/execute";
        } else {
            // 外部模式：直接走 sandboxUrl；未配 → 返回 null 跳过（Fix2）
            if (sandboxUrl.isBlank()) return null;
            url = sandboxUrl;
        }

        String code = String.valueOf(args.getOrDefault("code", ""));

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("timeout_ms", 5000);
        body.put("last_line_interactive", true);
        body.put("files", List.of());
        String payload = MAPPER.writeValueAsString(body);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (sandboxManager.isEnabled()) sandboxManager.touch(); // 用完刷新闲置计时

        if (resp.statusCode() / 100 != 2) {
            return "ERROR: 沙箱服务返回 HTTP " + resp.statusCode() + "：" + resp.body();
        }
        JsonNode root = MAPPER.readTree(resp.body());
        String stdout = root.path("stdout").asText("");
        String stderr = root.path("stderr").asText("");
        int exitCode = root.path("exit_code").asInt(0);
        boolean timedOut = root.path("timed_out").asBoolean(false);

        StringBuilder sb = new StringBuilder();
        sb.append("【代码执行结果】\n").append(stdout);
        if (timedOut) sb.append("\n[警告] 执行超时");
        if (exitCode != 0 && !stderr.isBlank()) sb.append("\n[stderr]\n").append(stderr);
        String out = sb.toString();
        return out.substring(0, Math.min(4000, out.length()));
    }
}
