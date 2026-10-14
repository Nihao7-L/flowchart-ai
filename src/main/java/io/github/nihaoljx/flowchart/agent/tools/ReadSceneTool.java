package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.AgentTool;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * read_scene：读当前场景的摘要与指定元素全量
 *
 * 这是"修正轮点名元素摘录"的正规化形态：不传 focusIds 时只给
 * 统计 + 概览（有界，防撑爆窗口）；传 focusIds 时给指定元素的
 * 完整 JSON（上限 20 个），模型修哪几个就看哪几个。
 */
public final class ReadSceneTool implements AgentTool {

    public static final String NAME = "read_scene";

    /** 概览条目上限（有界输出纪律：read 类工具不允许无上限回喂） */
    private static final int OUTLINE_CAP = 30;

    /** focusIds 上限 */
    private static final int FOCUS_CAP = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "读取当前场景：元素统计与概览（不传 focusIds），"
                + "或指定 id 的元素完整 JSON（传 focusIds，最多 20 个）。"
                + "修正前先看一眼现状，避免整图重画。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> focusIds = new LinkedHashMap<>();
        focusIds.put("type", "array");
        focusIds.put("items", Map.of("type", "string"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("focusIds", focusIds);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        return schema;
    }

    @Override
    public Observation execute(JsonNode args, ToolContext ctx) {
        String current = ctx.processedScene();
        if (current == null) {
            return Observation.fail(
                    "还没有场景可读：先 submit_scene（"
                            + "第一次生成时直接画即可）", null, null);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(current);
        } catch (Exception e) {
            return Observation.fail(
                    "当前场景读不通: " + e.getMessage(), null, null);
        }
        JsonNode elements = root.path("elements");
        ObjectNode data = MAPPER.createObjectNode();
        data.put("totalElements", elements.size());
        ObjectNode byType = data.putObject("byType");
        for (JsonNode el : elements) {
            String type = el.path("type").asText("unknown");
            byType.put(type, byType.path(type).asInt(0) + 1);
        }
        var outline = data.putArray("outline");
        int cap = Math.min(elements.size(), OUTLINE_CAP);
        for (int i = 0; i < cap; i++) {
            JsonNode el = elements.get(i);
            ObjectNode item = outline.addObject();
            item.put("id", el.path("id").asText(""));
            item.put("type", el.path("type").asText(""));
            item.put("text", el.path("label").path("text").asText(""));
        }
        if (elements.size() > cap) {
            data.put("outlineTruncated", true);
        }
        JsonNode focusArg = args == null ? null : args.get("focusIds");
        if (focusArg != null && focusArg.isArray() && !focusArg.isEmpty()) {
            var focus = data.putArray("focus");
            var missing = data.putArray("missingIds");
            int asked = Math.min(focusArg.size(), FOCUS_CAP);
            for (int i = 0; i < asked; i++) {
                String id = focusArg.get(i).asText("");
                JsonNode hit = findById(elements, id);
                if (hit == null) {
                    missing.add(id);
                } else {
                    focus.add(hit.deepCopy());
                }
            }
        }
        return Observation.ok("当前场景共 " + elements.size() + " 个元素", data);
    }

    private static JsonNode findById(JsonNode elements, String id) {
        for (JsonNode el : elements) {
            if (id.equals(el.path("id").asText(""))) {
                return el;
            }
        }
        return null;
    }
}
