package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.AgentTool;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * update_elements：按 id 发增量操作改已有图（，承接 M6b  的 ops）
 *
 * 与 SubmitSceneTool（全量 result）互补：编辑已有图时模型只交
 * "改了哪几个"（add / update / remove），未交的元素原样保留 —— 从机制上
 * 消灭"整图重画"一类事故，而不是靠提示词劝。
 *
 * 基座选择：本次 run 已有校验通过的场景（validScene）用它；否则编辑
 * 基线（previousJson）；两者皆空（新建模式）→ fail 引导回 submit_spec。
 *
 * update 是整元素替换而非字段级合并：与全量协议的字段语义 100%
 * 一致，模型零新知识；合并语义（深度合并 / 数组叠加）出脏数据的面大，
 * 刻意不做。
 *
 * 应用完成后组装成整份场景交给 process —— 增量产物与
 * 全量产物走同一条校验管线（bind/dedup/sanitize/claimFramesOnly/parseGate/
 * validate + audit + 空场景 + 编辑保真），校验标准零放松（不回退红线）。
 */
public final class UpdateElementsTool implements AgentTool {

    public static final String NAME = "update_elements";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SceneGate gate;

    public UpdateElementsTool(SceneGate gate) {
        this.gate = gate;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "增量编辑当前已有的图：按 id 提交 add/update/remove 操作，"
                + "未提及的元素自动原样保留。编辑已有图（改颜色/加节点/删节点/调连线）时"
                + "必须优先用本工具，不要整图重画。删链路中间节点时，要同时删掉连到它的箭头"
                + "并 add 一条前驱→后继的桥接箭头，保持链路连通。update 必须给完整替换体"
                + "（整元素全字段，不是只给变化的字段）；只有新建图或用户明确要求"
                + "重画时才走 submit_spec（同样不交坐标）。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("type", "object");
        op.put("properties", Map.of(
                "action", Map.of("type", "string",
                        "enum", List.of("add", "update", "remove")),
                "element", Map.of("type", "object"),
                "id", Map.of("type", "string")));
        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("type", "array");
        ops.put("items", op);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("ops", ops);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("ops"));
        return schema;
    }

    @Override
    public Observation execute(JsonNode args, ToolContext ctx) {
        JsonNode ops = args == null ? null : args.get("ops");
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            return fail("缺少 ops 参数（应为非空数组）", null);
        }
        String baseJson = pickBase(ctx);
        if (baseJson == null) {
            return fail(
                    "当前没有可编辑的基线场景：这是新建请求，请改用 "
                            + "submit_spec 提交语义 spec（nodes/edges）",
                    null);
        }
        JsonNode base;
        try {
            base = MAPPER.readTree(baseJson);
        } catch (Exception e) {
            return fail("基线场景读不通: " + e.getMessage(), null);
        }
        Applied applied = applyOps(base, ops);
        if (applied.error() != null) {
            return fail(applied.error(), applied.issueHint());
        }
        String merged;
        try {
            // 必须包成 {"elements":[...]} 场景外壳——裸数组过不了 parseGate
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode arr = root.putArray("elements");
            applied.scene().forEach(arr::add);
            merged = MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return fail("应用 ops 后无法序列化: " + e.getMessage(), null);
        }
        // 与 submit_spec 同一条闸门：增量产物不降校验标准（不回退红线）
        SceneGate.Outcome out = gate.process(merged, ctx);
        if (!out.parseable()) {
            return Observation.fail(
                    "增量结果读不通，未受理", payload(out.issues()), out.issues());
        }
        ctx.processedScene(out.json());
        if (!out.issues().isEmpty()) {
            return Observation.fail(
                    "增量结果受理但校验未通过（" + out.issues().size()
                            + " 个问题）",
                    payload(out.issues()), out.issues());
        }
        ctx.validScene(out.json());
        return Observation.ok(
                "增量编辑完成：" + applied.summary() + "，校验通过"
                        + "（现场景 " + applied.scene().size() + " 个元素），"
                        + "生成完成",
                payload(List.of()));
    }

    /** 基座：本次 run 的最新校验通过场景 → 编辑基线 → 都没有则 null */
    private static String pickBase(ToolContext ctx) {
        if (ctx.validScene() != null) {
            return ctx.validScene();
        }
        return ctx.previousJson();
    }

    /** ops 应用结果：成功 = 改好的元素列表 + 摘要；失败 = 点名错误 */
    private record Applied(List<ObjectNode> scene, String summary,
                           String error, String issueHint) {
        static Applied ok(List<ObjectNode> scene, String summary) {
            return new Applied(scene, summary, null, null);
        }

        static Applied bad(String error, String issueHint) {
            return new Applied(null, null, error, issueHint);
        }
    }

    /**
     * 应用顺序 remove → update → add：先删后加，规避"同一批里先删 id
     * 又加回同 id"的中间态歧义。
     */
    private static Applied applyOps(JsonNode base, JsonNode ops) {
        // 保序 Map：LinkedHashMap 保 z-order 语义（契约里数组序即层叠序）
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (JsonNode el : base.path("elements")) {
            if (el.isObject() && el.hasNonNull("id")
                    && el.get("id").isTextual()) {
                byId.put(el.get("id").asText(),
                        (ObjectNode) el.deepCopy());
            }
        }
        int added = 0;
        int updated = 0;
        int removed = 0;
        // 预扫描 add 的 id：remove→add 同 id 属于"替换"，不算冲突，
        // 所以 add 冲突判定放在 remove 之后做（用两阶段）
        List<JsonNode> adds = new ArrayList<>();
        Set<String> addIds = new LinkedHashSet<>();
        for (JsonNode op : ops) {
            String action = op.path("action").asText("");
            switch (action) {
                case "add" -> {
                    JsonNode el = op.get("element");
                    if (el == null || !el.isObject()) {
                        return Applied.bad(
                                "add 操作缺少 element 对象", null);
                    }
                    String id = el.path("id").asText("");
                    if (id.isEmpty()) {
                        return Applied.bad(
                                "add 的 element 缺少 id（契约要求元素必有 id）",
                                "给新元素写一个不与现有元素重复的 id，"
                                        + "如 \"n_new_1\"");
                    }
                    adds.add(el);
                    if (!addIds.add(id)) {
                        return Applied.bad(
                                "同一批 ops 里有两个 add 用了同一个 id: " + id,
                                "每个 add 的 id 必须唯一");
                    }
                }
                case "update" -> {
                    JsonNode el = op.get("element");
                    if (el == null || !el.isObject()) {
                        return Applied.bad(
                                "update 操作缺少 element 对象", null);
                    }
                    String id = el.path("id").asText("");
                    if (id.isEmpty()) {
                        return Applied.bad(
                                "update 的 element 缺少 id", null);
                    }
                    if (!byId.containsKey(id)) {
                        return Applied.bad(
                                "update 的 id 不存在于当前场景: " + id
                                        + "（现有 id 示例: "
                                        + sampleIds(byId.keySet()) + "）",
                                "先 read_scene 确认元素 id 再改；新元素请用 add");
                    }
                    byId.put(id, ((ObjectNode) el).deepCopy());
                    updated++;
                }
                case "remove" -> {
                    String id = op.path("id").asText("");
                    if (id.isEmpty()) {
                        return Applied.bad(
                                "remove 操作缺少 id", null);
                    }
                    if (byId.remove(id) == null) {
                        return Applied.bad(
                                "remove 的 id 不存在于当前场景: " + id
                                        + "（现有 id 示例: "
                                        + sampleIds(byId.keySet()) + "）",
                                "先 read_scene 确认元素 id 再删");
                    }
                    removed++;
                }
                default -> {
                    return Applied.bad(
                            "未知 action: \"" + action
                                    + "\"（只支持 add/update/remove）",
                            null);
                }
            }
        }
        // add 的 id 冲突在 remove 之后判（remove→add 同 id = 替换，合法）
        for (JsonNode el : adds) {
            String id = el.get("id").asText();
            if (byId.containsKey(id)) {
                return Applied.bad(
                        "add 的 id 与现有元素冲突: " + id,
                        "换一个新 id，或改用 update");
            }
            ObjectNode copy = ((ObjectNode) el).deepCopy();
            byId.put(id, copy);
            added++;
        }
        List<ObjectNode> scene = new ArrayList<>(byId.values());
        String summary = "add " + added + " / update " + updated
                + " / remove " + removed;
        return Applied.ok(scene, summary);
    }

    private static String sampleIds(Set<String> ids) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String id : ids) {
            if (i++ >= 8) {
                sb.append(" ...");
                break;
            }
            if (i > 1) {
                sb.append(", ");
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static Observation fail(String reason, String issueHint) {
        ValidationIssue issue = issueHint == null
                ? new ValidationIssue("ops", reason, "修正 ops 后重试")
                : new ValidationIssue("ops", reason, issueHint);
        return Observation.fail(
                "update_elements 未受理: " + reason,
                payload(List.of(issue)), List.of(issue));
    }

    /** 结构化载荷：issue 三元组（与 submit_spec 同形态，有界可回喂） */
    private static JsonNode payload(List<ValidationIssue> issues) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("issueCount", issues.size());
        ArrayNode arr = node.putArray("issues");
        for (ValidationIssue issue : issues) {
            ObjectNode item = arr.addObject();
            item.put("field", issue.field());
            item.put("reason", issue.reason());
            item.put("hint", issue.hint());
        }
        return node;
    }
}
