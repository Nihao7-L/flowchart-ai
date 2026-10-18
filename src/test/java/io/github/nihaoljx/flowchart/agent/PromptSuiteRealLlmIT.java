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
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.nihaoljx.flowchart.llm.AgentLlmClient;
import io.github.nihaoljx.flowchart.llm.AgentResult;
import io.github.nihaoljx.flowchart.llm.OpenAiToolClient;
import io.github.nihaoljx.flowchart.graph.layout.LayoutFamilyFixtures;

/**
 * 测试集真实 LLM 跑批（手动挡 IT）：对每个代表提示词各跑一次 AgentLoop，
 * 把最终场景 JSON 写到 %TEMP%/chartflow_render/suite_<name>.json。
 * 不校验成功与否，只负责产出场景供离线渲染检视。
 *
 * 跑法（候选从 application.yml 现读，用 -DagentSmoke.model=名字 选；默认 deepseek-ai/DeepSeek-V3）：
 *   mvn -o test -Dtest=PromptSuiteRealLlmIT -DagentSmoke.model=deepseek-ai/DeepSeek-V3
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptSuiteRealLlmIT {

    private static final String YML = "src/main/resources/application.yml";

    private static final Map<String, String> PROMPTS = new LinkedHashMap<>();

    static {
        PROMPTS.put("architecture", """
            设计一个电商平台的微服务架构图，要求多层嵌套，包含以下内容：
            外层架构（4个主要模块）：用户中心（蓝色）、商品中心（绿色）、交易中心（橙色）、物流中心（紫色）。
            横向服务（贯穿所有模块）：API 网关、消息队列、缓存层、配置中心、服务注册、监控告警、日志中心。
            请用不同颜色区分模块，连线表示调用关系，嵌套用包含关系展示。
            """);
        PROMPTS.put("flow", """
            设计一个电商订单处理流程图，包含嵌套子流程：主流程 用户下单→库存检查→支付处理→订单确认→物流处理→订单完成。
            支付处理含多分支（支付宝/微信/银行卡）；物流处理含循环（已揽收→运输中→派送中→已签收）。
            判断节点用菱形，循环用回路箭头，异常用红色标注，不同子流程用不同背景色。
            """);
        PROMPTS.put("statemachine", """
            设计一个复杂的订单状态机图，包含嵌套状态和并行状态：
            主状态机：待处理→处理中→已完成/已取消。处理中含嵌套子状态（待支付/支付中/待发货/已发货），
            并行状态（通知状态：待通知/已通知；审核状态：待审核/已审核/审核失败）。
            绿色=正常，黄色=中间，红色=异常/取消，蓝色=完成，紫色=并行，灰色=历史。
            """);
        PROMPTS.put("sequence", """
            设计一个复杂的用户登录时序图，包含嵌套循环和并行交互。
            参与者（8个）：用户、前端、API网关、认证服务、用户服务、缓存服务、数据库、日志服务。
            主流程：输入密码→转发→验证密码→生成Token→记录日志。嵌套循环：最多3次登录尝试。
            并行：手机验证码与邮箱验证码同时发送。生命线用垂直虚线，激活条用矩形，循环用loop框，并行用par框。
            """);
        PROMPTS.put("dataflow", """
            设计一个大数据处理系统的数据流图，包含多层嵌套和反馈循环：
            6层架构：数据采集层、数据传输层、数据存储层、数据处理层、数据服务层、数据应用层。
            每层含多个子系统（如采集层含 Flume/Filebeat/Logstash；存储层含 HDFS/S3/MinIO/Hive/ClickHouse 等）。
            反馈循环：处理层→采集层、应用层→处理层、服务层→存储层。粗箭头=主数据流，虚线=反馈流。
            """);
    }

    @Test
    void runAllPrompts() throws Exception {
        String wantModel = System.getProperty("agentSmoke.model", "deepseek-ai/DeepSeek-V3");
        Map<String, String> cand = pickCandidate(wantModel);
        System.out.println("[suite] 候选=" + cand.get("name") + " model=" + cand.get("model"));

        PromptService promptService = new PromptService();
        AgentLlmClient client = new OpenAiToolClient(
                cand.get("base-url"), cand.get("api-key"),
                cand.get("model"), 65536, true, 120);

        for (Map.Entry<String, String> e : PROMPTS.entrySet()) {
            String name = e.getKey();
            String prompt = e.getValue();
            System.out.println("[suite] === 开始生成 " + name + " ===");
            AgentLoop loop = new AgentLoop(client,
                    AgentTools.defaultRegistry(
                            new SceneBinder(), new SceneDeduper(), new SceneSanitizer(),
                            new ScenePlanner(), new GraphValidator(),
                            new GeometryAudit(), new SceneDrift(), promptService,
                            LayoutFamilyFixtures.defaultRegistry(),
                            LayoutFamilyFixtures.defaultPromptRegistry()),
                    promptService);
            long deadline = System.currentTimeMillis() + 30 * 60 * 1000L;
            AgentResult result = loop.run(
                    promptService.buildSystemBlock(), prompt, null, deadline);
            String scene = result.sceneJson();
            Path out = Path.of(System.getProperty("java.io.tmpdir"),
                    "chartflow_render", "suite_" + name + ".json");
            Files.createDirectories(out.getParent());
            if (scene != null) {
                Files.writeString(out, scene, StandardCharsets.UTF_8);
            } else {
                Files.writeString(out, "{}", StandardCharsets.UTF_8);
            }
            System.out.println("[suite] " + name + " -> status=" + result.status()
                    + " steps=" + result.steps() + " sceneBytes="
                    + (scene == null ? 0 : scene.length()) + " file=" + out);
        }
    }

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
        } catch (IOException ex) {
            throw new IllegalStateException("读 application.yml 失败", ex);
        }
    }

    private static String field(String chunk, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + key + ":\\s*(\\S+)\\s*$")
                .matcher(chunk);
        return m.find() ? m.group(1) : null;
    }
}
