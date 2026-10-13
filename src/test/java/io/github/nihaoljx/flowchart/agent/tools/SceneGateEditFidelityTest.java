package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.agent.ToolContext;
import io.github.nihaoljx.flowchart.graph.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.GraphValidator;
import io.github.nihaoljx.flowchart.graph.SceneBinder;
import io.github.nihaoljx.flowchart.graph.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.SceneDrift;
import io.github.nihaoljx.flowchart.graph.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.SceneSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SceneGate 编辑保真检查（v2-55 步骤 3 补）——
 * 锁死三条：全换 id 拦下、正常小改放行、只拦首次提交。
 */
class SceneGateEditFidelityTest {

    private final SceneGate gate = new SceneGate(
            new SceneBinder(), new SceneDeduper(),
            new SceneSanitizer(), new ScenePlanner(),
            new GraphValidator(), new GeometryAudit(),
            new SceneDrift());

    private static final String BASELINE = """
            {"elements":[
              {"id":"n1","type":"rectangle","x":0,"y":0,
               "width":120,"height":60,"label":{"text":"A"}},
              {"id":"n2","type":"rectangle","x":300,"y":0,
               "width":120,"height":60,"label":{"text":"B"}},
              {"id":"n3","type":"rectangle","x":600,"y":0,
               "width":120,"height":60,"label":{"text":"C"}}
            ]}""";

    private static String redrawn() {
        return """
                {"elements":[
                  {"id":"x1","type":"rectangle","x":0,"y":0,
                   "width":120,"height":60,"label":{"text":"甲"}},
                  {"id":"x2","type":"rectangle","x":300,"y":0,
                   "width":120,"height":60,"label":{"text":"乙"}},
                  {"id":"x3","type":"rectangle","x":600,"y":0,
                   "width":120,"height":60,"label":{"text":"丙"}}
                ]}""";
    }

    private static String smallChange() {
        // 只改 n2 的文字：id / 几何全保留（把 JSON 拼成一行以模拟
        // "照抄原元素、只动 label" 的合法修改）
        return """
                {"elements":[
                  {"id":"n1","type":"rectangle","x":0,"y":0,
                   "width":120,"height":60,"label":{"text":"A"}},
                  {"id":"n2","type":"rectangle","x":300,"y":0,
                   "width":120,"height":60,"label":{"text":"B2"}},
                  {"id":"n3","type":"rectangle","x":600,"y":0,
                   "width":120,"height":60,"label":{"text":"C"}}
                ]}""";
    }

    @Test
    @DisplayName("编辑模式整图重画（id 全换）→ 拦下，报 rewrite issue")
    void fullRewriteBlocked() {
        ToolContext ctx = new ToolContext(
                "把B改成B2", List.of(), BASELINE);
        SceneGate.Outcome out = gate.process(redrawn(), ctx);
        assertFalse(out.issues().isEmpty(),
                "id 全换必须被改图保真检查拦下");
        assertTrue(out.issues().stream().anyMatch(
                        i -> i.field().contains("id")),
                "issue 应点名 elements[].id");
        assertFalse(out.issues().get(0).reason().isEmpty());
    }

    @Test
    @DisplayName("编辑模式正常小改（id 全保留）→ 放行零 issue")
    void smallChangePasses() {
        ToolContext ctx = new ToolContext(
                "把B改成B2", List.of(), BASELINE);
        SceneGate.Outcome out = gate.process(smallChange(), ctx);
        assertTrue(out.issues().isEmpty(),
                "正常小改不该被拦: " + out.issues());
    }

    @Test
    @DisplayName("保真检查只做一次：首次拦下后，第二次重画放行（legacy 第 2 轮语义）")
    void onlyFirstSubmissionChecked() {
        ToolContext ctx = new ToolContext(
                "重画", List.of(), BASELINE);
        gate.process(redrawn(), ctx);
        assertTrue(ctx.rewriteChecked());
        SceneGate.Outcome second =
                gate.process(redrawn(), ctx);
        assertTrue(second.issues().isEmpty(),
                "第二次提交不再拦（模型坚持重画 = 用户可能就要重画）");
    }

    @Test
    @DisplayName("新建模式（无基线）→ 不做保真检查")
    void newModeSkipsCheck() {
        ToolContext ctx = new ToolContext("画张图", List.of());
        SceneGate.Outcome out = gate.process(redrawn(), ctx);
        assertTrue(out.issues().isEmpty());
        assertFalse(ctx.rewriteChecked(),
                "无基线时不应标记检查已做");
    }
}
