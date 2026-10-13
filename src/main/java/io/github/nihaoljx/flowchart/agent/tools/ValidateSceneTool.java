package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.agent.AgentTool;
import io.github.nihaoljx.flowchart.agent.Observation;
import io.github.nihaoljx.flowchart.agent.ToolContext;
import io.github.nihaoljx.flowchart.graph.ValidationIssue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * validate_scene：对当前已提交场景重跑校验，返回点名化 issue（v2-55）
 *
 * <p>定位是"提交前自查"：模型改完想确认一下，不必真的走 submit
 * （submit 未过会记 processedScene，这里只读不写）。复用
 * {@link SceneGate#validateOnly}（结构校验 + GeometryAudit 点名化）。
 */
public final class ValidateSceneTool implements AgentTool {

    public static final String NAME = "validate_scene";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SceneGate gate;

    public ValidateSceneTool(SceneGate gate) {
        this.gate = gate;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "对当前已提交的场景重跑校验（结构 + 几何体检），"
                + "返回点名化的问题清单。提交前自查用；"
                + "没有已提交的场景时调用会失败。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    @Override
    public Observation execute(JsonNode args, ToolContext ctx) {
        String current = ctx.processedScene();
        if (current == null) {
            return Observation.fail(
                    "还没有已提交的场景：先 submit_scene，"
                            + "再 validate_scene", null, null);
        }
        List<ValidationIssue> issues = gate.validateOnly(current);
        ObjectNode data = MAPPER.createObjectNode();
        data.put("issueCount", issues.size());
        var arr = data.putArray("issues");
        for (ValidationIssue issue : issues) {
            ObjectNode item = arr.addObject();
            item.put("field", issue.field());
            item.put("reason", issue.reason());
            item.put("hint", issue.hint());
        }
        if (issues.isEmpty()) {
            return Observation.ok(
                    "校验通过：0 个问题（可以放心 submit_scene）", data);
        }
        return Observation.fail(
                "校验未通过（" + issues.size() + " 个问题）", data, issues);
    }
}
