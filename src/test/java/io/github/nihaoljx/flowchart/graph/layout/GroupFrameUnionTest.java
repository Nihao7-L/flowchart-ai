package io.github.nihaoljx.flowchart.graph.layout;

import io.github.nihaoljx.flowchart.agent.tools.SubmitSpecTool;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：{@code GenericLayoutFamily.addGroupFrames} 的容器 bbox 必须紧贴成员，
 * 不能因为一个元素的多层级 group 把共享祖先框的 bbox 撑到全图。
 *
 * <p>历史 bug：{@code union} 改写了入参数组；一个多层级路径元素（如
 * {@code ["a","x"]}）的 {@code box} 数组引用同时挂在最内层键 {@code a/x}
 * 和父键 {@code x} 上，后来的兄弟元素往 {@code x} 上 merge 时顺手把
 * {@code a/x} 的盒子也撑成全图 —— 内层框吞画布。修复后 union 返回新数组，
 * 跨键不再相互污染。
 */
class GroupFrameUnionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deepNestingKeepsInnerFrameTight() throws Exception {
        // A=[a,x] B=[b,x] C=[c,b,x] D=[d,c,b,x]：
        // - 同一祖先 x 下的 a、b 共享父键，a/x 的盒子必须不被 b 的兄弟节点撑大
        // - 4 层嵌套 c/b/x 与 d/c/b/x 必须让 d/c/b/x 紧贴 D、不吞 b 或 c
        String spec = """
            {
              "title": "深嵌套回归",
              "nodes": [
                {"id":"A","label":"A节点","group":["a","x"]},
                {"id":"B","label":"B节点","group":["b","x"]},
                {"id":"C","label":"C节点","group":["c","b","x"]},
                {"id":"D","label":"D节点","group":["d","c","b","x"]}
              ],
              "edges":[
                {"from":"A","to":"B"},
                {"from":"B","to":"C"},
                {"from":"C","to":"D"}
              ]
            }
            """;
        SceneGate gate = new SceneGate(
                new SceneBinder(),
                new SceneDeduper(),
                new SceneSanitizer(),
                new ScenePlanner(),
                new GraphValidator(),
                new GeometryAudit());
        SubmitSpecTool tool = new SubmitSpecTool(
                gate, LayoutFamilyFixtures.defaultRegistry());
        ToolContext ctx = new ToolContext("回归", null);
        Observation obs = tool.execute(
                MAPPER.readTree("{\"spec\":" + spec + "}"), ctx);
        assertTrue(obs.ok(), () -> "scene must validate: " + obs.summary());

        JsonNode root = MAPPER.readTree(ctx.processedScene());
        JsonNode aFrame = null, bFrame = null, cFrame = null, dFrame = null;
        for (JsonNode el : root.get("elements")) {
            if (!"frame".equals(el.path("type").asText())) {
                continue;
            }
            String n = el.path("name").asText();
            if ("a".equals(n)) aFrame = el;
            else if ("b".equals(n)) bFrame = el;
            else if ("c".equals(n)) cFrame = el;
            else if ("d".equals(n)) dFrame = el;
        }
        assertTrue(aFrame != null && bFrame != null
                && cFrame != null && dFrame != null,
                "all four sub-frames must be synthesized");

        // 1) 内层 a 必须远小于外层 b —— 历史上 a 被 b 撑成同 bbox 一起吞画布
        double aArea = boxArea(aFrame);
        double bArea = boxArea(bFrame);
        assertTrue(aArea < bArea * 0.5,
                () -> "a frame must be much smaller than b (a=" + aArea
                        + ", b=" + bArea + ")");

        // 2) 内层 d 必须远小于 c
        double cArea = boxArea(cFrame);
        double dArea = boxArea(dFrame);
        assertTrue(dArea < cArea * 0.5,
                () -> "d frame must be much smaller than c (c=" + cArea
                        + ", d=" + dArea + ")");

        // 3) 任意内层框的面积不能超过外层框 —— 历史上 a 和 b 完全相等
        assertTrue(aArea < bArea, "a must be smaller than b");
        assertTrue(dArea < cArea, "d must be smaller than c");
    }

    private static double boxArea(JsonNode frame) {
        return frame.path("width").asDouble() * frame.path("height").asDouble();
    }
}
