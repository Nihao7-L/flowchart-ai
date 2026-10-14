package io.github.nihaoljx.flowchart.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;

/**
 * 几何体检器的行为契约（v10.0.0）。
 *
 * <p>两条纪律要钉死，它们是这个类存在的意义：
 * <ol>
 *   <li><b>只测量、不改坐标</b> —— 返回的是 issue，不是改过的场景；</li>
 *   <li><b>不误杀插画 / 伪 3D</b> —— 那种图里"面与面重叠"是造型手段。
 *       判据有两层：① 只有 {@code arrow} 算连线（{@code line} 是造型原语）；
 *       ② 障碍物只认"节点"图形，即<b>带 label</b> 或<b>被箭头绑定</b>的框 ——
 *       插画的猫耳猫眼既没名字也没连线接到它，不构成"不该被穿过"的语义。</li>
 * </ol>
 *
 * <p>阈值同样是契约的一部分：修正轮 = 再调一次 LLM（大图一轮约 240 秒），
 * 所以只有问题足够多才值得打扰模型，小瑕疵必须放过。2026-10-09 改成
 * "绝对数兜底 + 占比达标"双条件（连线 9 条时 4 处穿身是灾难，
 * 连线 20 条时 4 处无感，只按绝对数会小图漏报、大图误报）。
 */
class GeometryAuditTest {

    private final GeometryAudit audit = new GeometryAudit();

    @Test
    @DisplayName("纯插画 / 伪 3D（无 arrow）→ 一个问题都不报，重叠是造型手段")
    void pureIllustrationIsNotFlagged() {
        // 等轴测方块：三个面彼此重叠着拼在一起 —— 这正是"不该被判成缺陷"的形态
        String json = "{\"elements\":["
                + "{\"id\":\"top\",\"type\":\"line\",\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[100,0],[140,40],[40,40]],\"closed\":true},"
                + "{\"id\":\"left\",\"type\":\"line\",\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[40,40],[40,140],[0,100]],\"closed\":true},"
                + "{\"id\":\"front\",\"type\":\"rectangle\",\"x\":0,\"y\":40,"
                + "\"width\":100,\"height\":100},"
                + "{\"id\":\"face\",\"type\":\"rectangle\",\"x\":40,\"y\":40,"
                + "\"width\":100,\"height\":100,\"angle\":0}"
                + "]}";

        assertTrue(audit.audit(json).isEmpty(),
                "没有连线的画面不该做任何重叠判定（伪 3D 靠重叠拼面）");
    }

    @Test
    @DisplayName("规整的流程图（少量交叉）→ 不报，阈值以内不打扰 LLM")
    void cleanDiagramIsNotFlagged() {
        String json = "{\"elements\":["
                + "{\"id\":\"n1\",\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":50,\"label\":{\"text\":\"发起\"}},"
                + "{\"id\":\"n2\",\"type\":\"rectangle\",\"x\":300,\"y\":0,"
                + "\"width\":100,\"height\":50,\"label\":{\"text\":\"审核\"}},"
                + "{\"id\":\"a1\",\"type\":\"arrow\",\"start\":{\"id\":\"n1\"},"
                + "\"end\":{\"id\":\"n2\"},"
                + "\"label\":{\"text\":\"提交\"}}"
                + "]}";

        assertTrue(audit.audit(json).isEmpty(),
                "只有一条连线、彼此不缠，不该报");
    }

    @Test
    @DisplayName("连线两两缠绕 → 报一条汇总 issue（不逐处罗列，避免刷屏）")
    void tangledArrowsAreFlagged() {
        // 5 条自由箭头两两相交（共 10 对），全部穿过同一个中心附近
        String json = "{\"elements\":["
                + arrow("s1", 10, 0, "[[0,0],[80,100]]")
                + "," + arrow("s2", 10, 100, "[[0,0],[80,-100]]")
                + "," + arrow("s3", 0, 50, "[[0,0],[100,0]]")
                + "," + arrow("s4", 50, 0, "[[0,0],[0,100]]")
                + "," + arrow("s5", 0, 0, "[[0,0],[100,100]]")
                + "]}";

        List<ValidationIssue> issues = audit.audit(json);

        assertTrue(issues.size() >= 2,
                "点名化后应逐对报（而非一条汇总），实际: " + issues);
        boolean allCrossing = issues.stream()
                .allMatch(i -> "geometry.crossing".equals(i.field()));
        assertTrue(allCrossing,
                "交叉类 issue 的 field 应为 geometry.crossing，实际: "
                        + issues);
        assertTrue(issues.stream().anyMatch(i -> i.reason().contains("交叉")),
                "应说明是交叉问题，实际: " + issues);
        assertTrue(issues.stream().anyMatch(i -> i.hint().contains("折线")),
                "修复提示要给出可执行的做法（用折线绕开），实际: " + issues);
    }

    @Test
    @DisplayName("连线标签被图形压住 → 计入（用户反馈的「线上字显示不出」）")
    void pressedLabelsAreFlagged() {
        // 一个盖住中心的大矩形 + 4 条竖直穿过它的箭头，标签中点全落在矩形内
        String json = "{\"elements\":["
                + "{\"id\":\"box\",\"type\":\"rectangle\",\"x\":-100,\"y\":-100,"
                + "\"width\":200,\"height\":200,\"label\":{\"text\":\"中台\"}},"
                + labelledArrow("a1", -60) + "," + labelledArrow("a2", -20)
                + "," + labelledArrow("a3", 20) + "," + labelledArrow("a4", 60)
                + "]}";

        List<ValidationIssue> issues = audit.audit(json);

        // 这套场景里 4 条竖直箭头既"穿过中台框"（geometry.through）
        // 又"标签中点落在框内被压"（geometry.labelPressed），两类会同时报。
        // 本用例只钉死"标签被压"这条确实被点名，不要求整张只有这一类。
        long pressed = issues.stream()
                .filter(i -> "geometry.labelPressed".equals(i.field()))
                .count();
        assertTrue(pressed >= 2,
                "点名化后每条被压标签各报一条，实际: " + issues);
        assertTrue(issues.stream().anyMatch(
                        i -> i.field().equals("geometry.labelPressed")
                                && i.reason().contains("压住")),
                "应点名被压的标签，实际: " + issues);
    }

    @Test
    @DisplayName("脏输入不抛异常（体检是锦上添花，绝不能成为新的故障点）")
    void neverThrows() {
        assertTrue(audit.audit("").isEmpty());
        assertTrue(audit.audit("not json").isEmpty());
        assertTrue(audit.audit("{}").isEmpty());
        assertTrue(audit.audit("{\"elements\":null}").isEmpty());
        assertTrue(audit.audit("{\"elements\":[{\"id\":\"x\"}]}").isEmpty());
        assertTrue(audit.audit(null).isEmpty());
    }

    @Test
    @DisplayName("共享端点的两条线（同一图形的两条出边）不算缠绕")
    void sharedEndpointIsNotCrossing() {
        String json = "{\"elements\":["
                + "{\"id\":\"hub\",\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":50},"
                + "{\"id\":\"n2\",\"type\":\"rectangle\",\"x\":-300,\"y\":200,"
                + "\"width\":100,\"height\":50},"
                + "{\"id\":\"n3\",\"type\":\"rectangle\",\"x\":300,\"y\":200,"
                + "\"width\":100,\"height\":50},"
                + "{\"id\":\"a1\",\"type\":\"arrow\",\"start\":{\"id\":\"hub\"},"
                + "\"end\":{\"id\":\"n2\"}},"
                + "{\"id\":\"a2\",\"type\":\"arrow\",\"start\":{\"id\":\"hub\"},"
                + "\"end\":{\"id\":\"n3\"}}"
                + "]}";

        assertTrue(audit.audit(json).isEmpty(),
                "同一个中心发散出去的两条线是正常画法，不该算交叉");
    }

    @Test
    @DisplayName("箭头端点插进绑定框内部 → 报（实测 20 例里 18 处是它）")
    void insetEndpointIsFlagged() {
        // h_sequence 原型：垂直链，模型把端点写成"框中心"而不是"框边缘"。
        // user 框 y 从 50 到 100，它写的起点 y 就是 75（正中）。
        String json = "{\"elements\":["
                + "{\"id\":\"user\",\"type\":\"rectangle\",\"x\":100,\"y\":50,"
                + "\"width\":100,\"height\":50,\"label\":{\"text\":\"用户\"}},"
                + "{\"id\":\"front\",\"type\":\"rectangle\",\"x\":100,\"y\":150,"
                + "\"width\":100,\"height\":50,\"label\":{\"text\":\"前端\"}},"
                + "{\"id\":\"a1\",\"type\":\"arrow\",\"x\":150,\"y\":75,"
                + "\"points\":[[0,0],[0,50]],"
                + "\"start\":{\"id\":\"user\"},\"end\":{\"id\":\"front\"}}"
                + "]}";

        List<ValidationIssue> issues = audit.audit(json);

        assertEquals(1, issues.size(), "单条内插箭头只报一条，实际: " + issues);
        assertEquals("geometry.insetEndpoint", issues.get(0).field());
        assertTrue(issues.get(0).reason().contains("插在绑定图形"),
                "应点明是端点内插，实际: " + issues.get(0).reason());
        assertTrue(issues.get(0).hint().contains("start/end"),
                "提示要给出可执行改法（只写 start/end 交给渲染器），实际: "
                        + issues.get(0).hint());
    }

    @Test
    @DisplayName("自算路径（没给 points）不算端点内插 → 渲染器取的就是边缘交点")
    void snappedPathHasNoInsetEndpoint() {
        // 与上一条同样的两个框，但箭头不写 points —— 前端 edgePointTowards
        // 会取包围盒朝向对方的边缘交点，永远不会插进框里。
        String json = "{\"elements\":["
                + "{\"id\":\"user\",\"type\":\"rectangle\",\"x\":100,\"y\":50,"
                + "\"width\":100,\"height\":50,\"label\":{\"text\":\"用户\"}},"
                + "{\"id\":\"front\",\"type\":\"rectangle\",\"x\":100,\"y\":150,"
                + "\"width\":100,\"height\":50,\"label\":{\"text\":\"前端\"}},"
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"start\":{\"id\":\"user\"},\"end\":{\"id\":\"front\"}}"
                + "]}";

        assertTrue(audit.audit(json).isEmpty(),
                "不写坐标的绑定箭头由渲染器接管端点，不该报");
    }

    @Test
    @DisplayName("插画里的图形（无 label、无绑定）不算障碍物 → 不报穿身")
    void illustrationShapesAreNotObstacles() {
        // 猫的头 + 眼睛：都没 label、也没被箭头绑。"胡须"横穿它们是造型，
        // 不是缺陷。旧口径把每个图形都当墙，实测 c_cat 被误报「穿身 10 处」。
        String json = "{\"elements\":["
                + "{\"id\":\"head\",\"type\":\"ellipse\",\"x\":0,\"y\":0,"
                + "\"width\":200,\"height\":200},"
                + "{\"id\":\"eye\",\"type\":\"ellipse\",\"x\":60,\"y\":60,"
                + "\"width\":40,\"height\":40},"
                + arrow("w1", -80, 100, "[[0,0],[360,0]]")
                + "," + arrow("w2", -80, 110, "[[0,0],[360,10]]")
                + "," + arrow("w3", -80, 120, "[[0,0],[360,20]]")
                + "]}";

        assertTrue(audit.audit(json).isEmpty(),
                "插画的图形不是「节点」，不该按「连线穿过无关图形」报");
    }

    @Test
    @DisplayName("分区底板（吞下别的节点）不是障碍物 → 分区内的连线不算穿身")
    void containerPanelIsNotObstacle() {
        // 智慧园区大屏原型：一块浅色底板框住"分组头 + 三个子项"，
        // 三条箭头从子项向上连到分组头 —— 全程都在底板里走。
        // 底板面积大又是带 label 的矩形，不排除它就会报"穿身 3 处"。
        String json = "{\"elements\":["
                + "{\"id\":\"zone\",\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                + "\"width\":400,\"height\":400,\"label\":{\"text\":\"门禁\"}},"
                + "{\"id\":\"head\",\"type\":\"rectangle\",\"x\":50,\"y\":20,"
                + "\"width\":300,\"height\":60,\"label\":{\"text\":\"门禁管理\"}},"
                + "{\"id\":\"s1\",\"type\":\"rectangle\",\"x\":50,\"y\":120,"
                + "\"width\":80,\"height\":50,\"label\":{\"text\":\"人脸通行\"}},"
                + "{\"id\":\"s2\",\"type\":\"rectangle\",\"x\":160,\"y\":240,"
                + "\"width\":80,\"height\":50,\"label\":{\"text\":\"访客登记\"}},"
                + "{\"id\":\"s3\",\"type\":\"rectangle\",\"x\":270,\"y\":340,"
                + "\"width\":80,\"height\":50,\"label\":{\"text\":\"闸机状态\"}},"
                + "{\"id\":\"a1\",\"type\":\"arrow\",\"start\":{\"id\":\"s1\"},"
                + "\"end\":{\"id\":\"head\"}},"
                + "{\"id\":\"a2\",\"type\":\"arrow\",\"start\":{\"id\":\"s2\"},"
                + "\"end\":{\"id\":\"head\"}},"
                + "{\"id\":\"a3\",\"type\":\"arrow\",\"start\":{\"id\":\"s3\"},"
                + "\"end\":{\"id\":\"head\"}}"
                + "]}";

        assertTrue(audit.audit(json).isEmpty(),
                "连线在自家分区底板里走是分区本来的样子，底板不是墙");
    }

    private String arrow(String id, double x, double y, String points) {
        return "{\"id\":\"" + id + "\",\"type\":\"arrow\",\"x\":" + x
                + ",\"y\":" + y + ",\"points\":" + points + "}";
    }

    private String labelledArrow(String id, double x) {
        return "{\"id\":\"" + id + "\",\"type\":\"arrow\",\"x\":" + x
                + ",\"y\":-150,\"points\":[[0,0],[0,300]],"
                + "\"label\":{\"text\":\"调用\"}}";
    }
}
