package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.FlowchartData;
import io.github.nihaoljx.flowchart.model.MindmapData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiagramService 单元测试
 *
 * 测试范围：JSON 数据 → PlantUML 语法（不调 AI，不调 PlantUML 渲染）
 *
 * 测试方法命名规则：test + 场景 + 期望结果
 * 每个方法都遵循三段式：Given（准备数据）→ When（执行被测方法）→ Then（断言结果）
 */
class DiagramServiceTest {

    /** 被测对象：直接 new，不用 Spring 注入 */
    private DiagramService diagramService;

    /** 辅助方法：快速构造 node */
    private FlowchartData.Node node(String id, String type, String label) {
        FlowchartData.Node n = new FlowchartData.Node();
        n.setId(id);
        n.setType(type);
        n.setLabel(label);
        return n;
    }

    /** 辅助方法：快速构造 edge */
    private FlowchartData.Edge edge(String from, String to, String label) {
        FlowchartData.Edge e = new FlowchartData.Edge();
        e.setFrom(from);
        e.setTo(to);
        e.setLabel(label);
        return e;
    }

    /** 辅助方法：快速构造思维导图节点 */
    private MindmapData mindNode(String label, MindmapData... children) {
        MindmapData node = new MindmapData();
        node.setLabel(label);
        for (MindmapData child : children) {
            node.getChildren().add(child);
        }
        return node;
    }

    /**
     * @BeforeEach：每个 @Test 方法执行前自动调用一次
     *
     * 作用：保证每个测试方法拿到全新的 DiagramService 对象，互不干扰。
     * 如果上一个测试改了对象状态，不会影响下一个测试——这叫"测试隔离"。
     */
    @BeforeEach
    void setUp() {
        diagramService = new DiagramService();
    }

    // ==================== 流程图测试 ====================

    @Test
    @DisplayName("流程图：登录流程应包含 start 和 stop")
    void testFlowchartContainsStartAndStop() {
        // Given：构造登录流程数据（开始 → 输入 → 验证 → 是:进入首页 → 结束 / 否:提示错误 → 回到输入）
        FlowchartData data = new FlowchartData();
        data.setTitle("用户登录流程");
        data.setNodes(List.of(
                node("1", "start", "开始"),
                node("2", "process", "输入账号密码"),
                node("3", "decision", "验证通过?"),
                node("4", "process", "进入首页"),
                node("5", "process", "提示错误"),
                node("6", "end", "结束")
        ));
        data.setEdges(List.of(
                edge("1", "2", null),
                edge("2", "3", null),
                edge("3", "4", "是"),
                edge("3", "5", "否"),
                edge("4", "6", null),
                edge("5", "2", null)   // 循环：提示错误 → 回到输入
        ));

        // When：执行转换
        String plantUml = diagramService.buildPlantUml(data);

        // Then：断言关键字段存在
        assertTrue(plantUml.startsWith("@startuml"), "应以 @startuml 开头");
        assertTrue(plantUml.contains("start"), "应包含 start");
        assertTrue(plantUml.contains("stop"), "应包含 stop");
        assertTrue(plantUml.contains("endif"), "有判断分支应有 endif");
        assertTrue(plantUml.contains("进入首页"), "应包含节点文字");
        assertTrue(plantUml.endsWith("@enduml\n"), "应以 @enduml 结尾");
    }

    @Test
    @DisplayName("流程图：验证通过? 已带问号时不重复加")
    void testDecisionLabelAlreadyHasQuestionMark() {
        // Given：decision 的 label 已经以 ? 结尾
        FlowchartData data = new FlowchartData();
        data.setTitle("简单判断");
        data.setNodes(List.of(
                node("1", "start", "开始"),
                node("2", "decision", "通过?"),  // 已带 ?
                node("3", "process", "成功"),
                node("4", "end", "结束")
        ));
        data.setEdges(List.of(
                edge("1", "2", null),
                edge("2", "3", "是"),
                edge("2", "4", "否"),    // 否分支直接到 end
                edge("3", "4", null)
        ));

        // When
        String plantUml = diagramService.buildPlantUml(data);

        // Then：不应该出现 "通过??"（不会重复加问号）
        assertFalse(plantUml.contains("通过??"), "label 已带问号时不应重复加");
        assertTrue(plantUml.contains("通过?"), "应保留原有问号");
    }

    @Test
    @DisplayName("流程图：循环回到已访问节点应输出 note")
    void testCycleDetectionOutputsNote() {
        // Given：构造一个循环——B 回到 A
        FlowchartData data = new FlowchartData();
        data.setTitle("循环测试");
        data.setNodes(List.of(
                node("1", "start", "开始"),
                node("2", "process", "步骤A"),
                node("3", "process", "步骤B"),
                node("4", "end", "结束")
        ));
        // A → B → A（循环）
        data.setEdges(List.of(
                edge("1", "2", null),
                edge("2", "3", null),
                edge("3", "2", null),   // B 回到 A
                edge("3", "4", null)
        ));

        // When
        String plantUml = diagramService.buildPlantUml(data);

        // Then：应检测到循环并输出 note
        assertTrue(plantUml.contains("note right"), "循环应输出 note right");
        assertTrue(plantUml.contains("循环回到"), "note 应包含循环回到提示");
    }

    // ==================== 思维导图测试 ====================

    @Test
    @DisplayName("思维导图：根节点用 *，子节点用 **，孙节点用 ***")
    void testMindMapStarLevels() {
        // Given：构造三层树
        // 电商系统
        //   ├── 前端
        //   │   └── Web 商城
        //   └── 后端
        //       └── 订单服务
        MindmapData root = mindNode("电商系统",
                mindNode("前端",
                        mindNode("Web 商城")),
                mindNode("后端",
                        mindNode("订单服务"))
        );

        // When
        String plantUml = diagramService.buildMindMap(root);

        // Then
        assertTrue(plantUml.startsWith("@startmindmap"), "应以 @startmindmap 开头");
        assertTrue(plantUml.contains("* 电商系统"), "根节点用单个 *");
        assertTrue(plantUml.contains("** 前端"), "一层子节点用 **");
        assertTrue(plantUml.contains("*** Web 商城"), "二层子节点用 ***");
        assertTrue(plantUml.contains("@endmindmap"), "应以 @endmindmap 结尾");
    }

    @Test
    @DisplayName("思维导图：叶子节点（children 为空）只输出自己，不报错")
    void testMindMapLeafNode() {
        // Given：只有一个根节点，无子节点
        MindmapData root = new MindmapData();
        root.setLabel("单独节点");

        // When
        String plantUml = diagramService.buildMindMap(root);

        // Then
        assertTrue(plantUml.contains("* 单独节点"), "应输出根节点");
        assertFalse(plantUml.contains("**"), "没有子节点不应出现 **");
    }

    // ==================== 架构图测试 ====================

    @Test
    @DisplayName("架构图：组件用方括号，依赖用 -->")
    void testArchitectureBracketsAndArrows() {
        // Given：Web前端 → 订单服务 → MySQL
        FlowchartData data = new FlowchartData();
        data.setTitle("电商架构");
        data.setNodes(List.of(
                node("1", "process", "Web前端"),
                node("2", "process", "订单服务"),
                node("3", "process", "MySQL")
        ));
        data.setEdges(List.of(
                edge("1", "2", "HTTP"),
                edge("2", "3", "SQL")
        ));

        // When
        String plantUml = diagramService.buildArchitecture(data);

        // Then
        assertTrue(plantUml.startsWith("@startuml"), "应以 @startuml 开头");
        assertTrue(plantUml.contains("[Web前端] --> [订单服务]"), "组件用方括号，依赖用 -->");
        assertTrue(plantUml.contains(" : HTTP"), "边标签用冒号连接");
        assertTrue(plantUml.contains(" : SQL"), "第二条边标签");
        assertTrue(plantUml.endsWith("@enduml\n"), "应以 @enduml 结尾");
    }

    @Test
    @DisplayName("架构图：边引用不存在的节点时应跳过（防御逻辑）")
    void testArchitectureSkipInvalidEdge() {
        // Given：边引用了一个不存在的节点 ID
        FlowchartData data = new FlowchartData();
        data.setTitle("含无效边");
        data.setNodes(List.of(
                node("1", "process", "组件A"),
                node("2", "process", "组件B")
        ));
        data.setEdges(List.of(
                edge("1", "2", "调用"),
                edge("1", "999", "无效边")  // 999 不存在于节点列表
        ));

        // When
        String plantUml = diagramService.buildArchitecture(data);

        // Then：有效边正常输出，无效边被跳过
        assertTrue(plantUml.contains("[组件A] --> [组件B]"), "有效边应输出");
        assertFalse(plantUml.contains("999"), "无效边引用的 ID 不应出现");
        assertFalse(plantUml.contains("null"), "不应出现 null 文字");
    }
}

