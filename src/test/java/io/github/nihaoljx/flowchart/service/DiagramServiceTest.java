package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.FlowchartData;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * DiagramService 单元测试
 * 不调 AI，不调 PlantUML，只测 JSON → PlantUML 语法这一步
 */
public class DiagramServiceTest {

    private final DiagramService diagramService = new DiagramService();

    /**
     * 场景1：登录流程（有判断分支）
     * 开始 → 输入账号密码 → 验证通过? → 是: 进入首页 → 结束
     *                                → 否: 提示错误 → 回到输入
     */
    @Test
    public void testLoginFlow() {
        // ===== 构造测试数据（模拟 AI 返回的 JSON） =====
        FlowchartData data = new FlowchartData();
        data.setTitle("用户登录流程");

        // 构造节点
        FlowchartData.Node n1 = node("1", "start", "开始");
        FlowchartData.Node n2 = node("2", "process", "输入账号密码");
        FlowchartData.Node n3 = node("3", "decision", "验证通过?");
        FlowchartData.Node n4 = node("4", "process", "进入首页");
        FlowchartData.Node n5 = node("5", "process", "提示错误");
        FlowchartData.Node n6 = node("6", "end", "结束");
        data.setNodes(List.of(n1, n2, n3, n4, n5, n6));

        // 构造连线
        data.setEdges(List.of(
                edge("1", "2", null),
                edge("2", "3", null),
                edge("3", "4", "是"),
                edge("3", "5", "否"),
                edge("4", "6", null),
                edge("5", "2", null)    // 循环：提示错误 → 回到输入
        ));

        // ===== 执行转换 =====
        String plantUml = diagramService.buildPlantUml(data);

        // ===== 打印输出，肉眼检查 =====
        System.out.println("=== 登录流程 PlantUML 输出 ===");
        System.out.println(plantUml);
    }

    // ===== 辅助方法：快速构造 node =====
    private FlowchartData.Node node(String id, String type, String label) {
        FlowchartData.Node n = new FlowchartData.Node();
        n.setId(id);
        n.setType(type);
        n.setLabel(label);
        return n;
    }

    // ===== 辅助方法：快速构造 edge =====
    private FlowchartData.Edge edge(String from, String to, String label) {
        FlowchartData.Edge e = new FlowchartData.Edge();
        e.setFrom(from);
        e.setTo(to);
        e.setLabel(label);
        return e;
    }
}
