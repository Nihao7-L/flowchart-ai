package io.github.nihaoljx.flowchart.agent;

import io.github.nihaoljx.flowchart.agent.tools.AgentTools;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.SceneDrift;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.service.PromptService;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.nihaoljx.flowchart.llm.AgentLlmClient;
import io.github.nihaoljx.flowchart.llm.AgentResult;
import io.github.nihaoljx.flowchart.llm.OpenAiToolClient;

/**
 * v2-55 真实 LLM 冒烟（手动挡）：AgentLoop + 真实候选模型 + 真实场景
 *
 * <p>命名用 IT 后缀 —— surefire 默认 includes 不含 *IT，常规
 * {@code mvn -o test} 永远不会碰到它；只按需手动跑：
 * <pre>
 * mvn -o test -Dtest=AgentLoopRealLlmIT [-DagentSmoke.model=deepseek-v4.1-flash]
 * </pre>
 *
 * <p>它会真花钱调 LLM：跑 AgentLoop（4 工具、MAX_STEPS=8），把最终场景
 * JSON 写到 %TEMP%\chartflow_render\agent_smoke_scene.json 供渲染。
 * 密钥从 application.yml（gitignored）现读，绝不写进代码或输出。
 */
class AgentLoopRealLlmIT {

    private static final String YML =
            "src/main/resources/application.yml";

    private static final String USER_STORY =
            "画一张电商订单系统的处理流程图：用户浏览商品，加入购物车，提交订单，"
                    + "然后支付；支付成功走库存扣减、物流发货、确认收货；"
                    + "支付失败走取消订单并退款。图里要体现支付成功的分支判断。";

    @Test
    void runRealAgentLoop() throws Exception {
        String wantModel = System.getProperty(
                "agentSmoke.model", "glm-5.3-flash");
        Map<String, String> cand = pickCandidate(wantModel);
        System.out.println("[smoke] 候选=" + cand.get("name")
                + " model=" + cand.get("model"));

        PromptService promptService = new PromptService();
        ToolRegistry registry = AgentTools.defaultRegistry(
                new SceneBinder(), new SceneDeduper(), new SceneSanitizer(),
                new ScenePlanner(), new GraphValidator(),
                new GeometryAudit(), new SceneDrift(), promptService);
        AgentLlmClient client = new OpenAiToolClient(
                cand.get("base-url"), cand.get("api-key"),
                cand.get("model"), 65536);
        AgentLoop loop = new AgentLoop(client, registry, promptService);

        long deadline = System.currentTimeMillis() + 25 * 60 * 1000L;
        AgentResult result = loop.run(
                promptService.buildSystemBlock(), USER_STORY, null, deadline);

        System.out.println("[smoke] status=" + result.status()
                + " steps=" + result.steps()
                + " reason=" + result.reason());
        if (result.sceneJson() != null) {
            Path out = Path.of(System.getProperty("java.io.tmpdir"),
                    "chartflow_render", "agent_smoke_scene.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, result.sceneJson(),
                    StandardCharsets.UTF_8);
            System.out.println("[smoke] 场景已写出: " + out);
        }
        org.junit.jupiter.api.Assertions.assertEquals(
                AgentResult.Status.COMPLETED, result.status(),
                "agent loop 未通过校验: " + result.reason());
    }

    /**
     * 从 application.yml 现读指定 model 的候选（base-url/api-key/model）。
     * 只认未注释的条目；找不到就抛错提示候选名。
     */
    private static Map<String, String> pickCandidate(String model) {
        try {
            String yml = Files.readString(Path.of(YML));
            Pattern block = Pattern.compile(
                    "(?m)^\\s*- name:\\s*(\\S+)"
                            + "(?:(?!^\\s*- name:).)*?model:\\s*("
                            + Pattern.quote(model) + ")\\s*$"
                            + "(?:(?!^\\s*- name:).)*",
                    Pattern.DOTALL);
            Matcher m = block.matcher(yml);
            while (m.find()) {
                String chunk = m.group();
                String baseUrl = field(chunk, "base-url");
                String apiKey = field(chunk, "api-key");
                if (baseUrl != null && apiKey != null) {
                    return Map.of("name", m.group(1), "model", model,
                            "base-url", baseUrl, "api-key", apiKey);
                }
            }
            throw new IllegalStateException(
                    "application.yml 里找不到生效候选 model=" + model);
        } catch (IOException e) {
            throw new IllegalStateException("读 application.yml 失败", e);
        }
    }

    private static String field(String chunk, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + key + ":\\s*(\\S+)\\s*$")
                .matcher(chunk);
        return m.find() ? m.group(1) : null;
    }
}
