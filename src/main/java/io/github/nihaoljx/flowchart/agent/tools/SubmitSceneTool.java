package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.AgentTool;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * submit_scene：提交整份场景 JSON，走全管线 + 校验（首批工具）
 *
 * 结构化输出免费拿：场景 JSON 作为工具参数提交，厂商 function calling
 * 的参数体本来就是强 JSON，parseGate 的"读得通"在参数层天然成立；
 * 截断/畸形参数交由既有冷却与重试机制处置（step 3 接线时生效）。
 *
 * 写上下文的两个槽位（见 ToolContext）：
 * parseGate 过 → processedScene（超限时它是 partial 的来源）；
 * 零 issue → validScene（只有它能作为成品）。
 */
public final class SubmitSceneTool implements AgentTool {

    public static final String NAME = "submit_scene";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SceneGate gate;

    public SubmitSceneTool(SceneGate gate) {
        this.gate = gate;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "提交整份白板场景 JSON（{\"elements\":[...]}）。"
                + "后端会跑校验：通过则生成结束；未通过则返回点名化的"
                + "问题清单，请只修正被点名的问题后再提交。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("type", "object");
        scene.put("properties",
                Map.of("elements", Map.of("type", "array")));
        scene.put("required", List.of("elements"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scene", scene);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("scene"));
        return schema;
    }

    @Override
    public Observation execute(JsonNode args, ToolContext ctx) {
        JsonNode scene = args == null ? null : args.get("scene");
        if (scene == null || !scene.isObject()) {
            ValidationIssue issue = new ValidationIssue(
                    "scene", "缺少 scene 参数或它不是 JSON 对象",
                    "把整份场景 JSON 放进 scene 参数重新提交");
            return Observation.fail(
                    "submit_scene 缺少 scene 参数", null, List.of(issue));
        }
        String raw;
        try {
            raw = MAPPER.writeValueAsString(scene);
        } catch (Exception e) {
            return Observation.fail(
                    "scene 参数无法序列化: " + e.getMessage(), null, null);
        }
        SceneGate.Outcome out = gate.process(raw, ctx);
        if (!out.parseable()) {
            return Observation.fail(
                    "场景读不通，未受理", payload(out.issues()), out.issues());
        }
        ctx.processedScene(out.json());
        if (!out.issues().isEmpty()) {
            return Observation.fail(
                    "场景受理但校验未通过（" + out.issues().size() + " 个问题）",
                    payload(out.issues()), out.issues());
        }
        ctx.validScene(out.json());
        int count = elementCount(out.json());
        return Observation.ok(
                "场景受理并通过校验（" + count + " 个元素），生成完成",
                payload(List.of()));
    }

    /** 结构化载荷：issue 三元组 + 元素计数（有界，直接可回喂） */
    private static JsonNode payload(List<ValidationIssue> issues) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("issueCount", issues.size());
        var arr = node.putArray("issues");
        for (ValidationIssue issue : issues) {
            ObjectNode item = arr.addObject();
            item.put("field", issue.field());
            item.put("reason", issue.reason());
            item.put("hint", issue.hint());
        }
        return node;
    }

    private static int elementCount(String json) {
        try {
            return MAPPER.readTree(json).path("elements").size();
        } catch (Exception e) {
            return -1;
        }
    }
}
