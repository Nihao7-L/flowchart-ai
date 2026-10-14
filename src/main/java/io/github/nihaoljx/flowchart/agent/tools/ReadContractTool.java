package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.AgentTool;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.service.PromptService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * read_contract：按元素类型按需读契约片段（，s07 思路的工具化）
 *
 * 为什么不全量塞 system：契约段约 3.3KB 且常驻，而模型真正纠结的
 * 往往只有一两个类型（arrow 的 points/start/end 二选一、frame 的
 * 绑定禁令）。按需加载 = 注意力预算纪律（最小高信号 token 集合）。
 *
 * 映射关系：模型说 rectangle / ellipse / diamond 时归到 shape 分支
 * （schema 的 definitions 键是 shape，一个分支覆盖三种形状）。
 */
public final class ReadContractTool implements AgentTool {

    public static final String NAME = "read_contract";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PromptService promptService;

    public ReadContractTool(PromptService promptService) {
        this.promptService = promptService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "读取某个元素类型的契约片段（字段、必填键、类型约束）。"
                + "section 取值: shape(矩形/椭圆/菱形)/arrow/line/text/"
                + "frame/freedraw/embeddable。拿不准字段口径时先查，"
                + "不要猜。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "string");
        section.put("enum", java.util.List.of(
                "shape", "arrow", "line", "text",
                "frame", "freedraw", "embeddable"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("section", section);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", java.util.List.of("section"));
        return schema;
    }

    @Override
    public Observation execute(JsonNode args, ToolContext ctx) {
        String section = args == null ? null
                : args.path("section").asText(null);
        if (section == null) {
            return Observation.fail(
                    "缺少 section 参数（元素类型名）", null, null);
        }
        String branch = normalize(section);
        try {
            String text = promptService.contractSection(branch);
            return Observation.ok("契约片段 · " + branch, payload(text));
        } catch (IllegalArgumentException e) {
            return Observation.fail(e.getMessage(), null, null);
        } catch (Exception e) {
            return Observation.fail(
                    "契约片段读取失败: " + e.getMessage(), null, null);
        }
    }

    /** 模型常用形状名 → schema 分支名（shape 一个分支覆盖三种形状） */
    private static String normalize(String section) {
        return switch (section) {
            case "rectangle", "ellipse", "diamond" -> "shape";
            default -> section;
        };
    }

    private static JsonNode payload(String text) {
        var node = MAPPER.createObjectNode();
        node.put("text", text);
        return node;
    }
}
