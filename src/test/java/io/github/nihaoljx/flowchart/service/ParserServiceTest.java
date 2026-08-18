package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.FlowchartData;
import io.github.nihaoljx.flowchart.model.MindmapData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParserService 单元测试
 *
 * 测试范围：LLM 返回的 JSON 文本 → Java 对象 + 数据校验
 *
 * 核心验证点：
 * 1. 正常 JSON 能解析成功
 * 2. 非法 JSON 抛异常
 * 3. 校验规则能拦住不合规数据（缺 start、decision 少边等）
 */
class ParserServiceTest {

    private ParserService parserService;

    @BeforeEach
    void setUp() {
        parserService = new ParserService();
    }

    // ==================== 流程图解析测试 ====================

    @Test
    @DisplayName("流程图：合法 JSON 能正常解析")
    void testParseValidFlowchart() throws Exception {
        // Given：一段合法的流程图 JSON
        String json = """
                {
                  "title": "登录流程",
                  "nodes": [
                    {"id": "1", "type": "start", "label": "开始"},
                    {"id": "2", "type": "process", "label": "输入密码"},
                    {"id": "3", "type": "decision", "label": "验证通过?"},
                    {"id": "4", "type": "process", "label": "进入首页"},
                    {"id": "5", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null},
                    {"from": "2", "to": "3", "label": null},
                    {"from": "3", "to": "4", "label": "是"},
                    {"from": "3", "to": "5", "label": "否"},
                    {"from": "4", "to": "5", "label": null}
                  ]
                }
                """;

        // When
        FlowchartData data = parserService.parse(json);

        // Then
        assertNotNull(data, "解析结果不应为 null");
        assertEquals("登录流程", data.getTitle(), "标题应匹配");
        assertEquals(5, data.getNodes().size(), "应有 5 个节点");
        assertEquals(5, data.getEdges().size(), "应有 5 条边");
    }

    @Test
    @DisplayName("流程图：非法 JSON 应抛异常")
    void testParseInvalidJson() {
        // Given：一段根本不是 JSON 的文本
        String badJson = "这不是JSON，LLM 可能返回了乱码";

        // When + Then：parse 应该抛 Exception
        assertThrows(Exception.class, () -> {
            parserService.parse(badJson);
        }, "非法 JSON 应抛异常");
    }

    @Test
    @DisplayName("流程图：缺少 title 应校验失败")
    void testParseMissingTitle() {
        // Given：title 为空
        String json = """
                {
                  "title": "",
                  "nodes": [
                    {"id": "1", "type": "start", "label": "开始"},
                    {"id": "2", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null}
                  ]
                }
                """;

        // When + Then
        assertThrows(Exception.class, () -> {
            parserService.parse(json);
        }, "缺少 title 应校验失败");
    }

    @Test
    @DisplayName("流程图：没有 start 节点应校验失败")
    void testParseNoStartNode() {
        // Given：只有 process 和 end，没有 start
        String json = """
                {
                  "title": "无起点",
                  "nodes": [
                    {"id": "1", "type": "process", "label": "步骤"},
                    {"id": "2", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null}
                  ]
                }
                """;

        // When + Then
        assertThrows(Exception.class, () -> {
            parserService.parse(json);
        }, "没有 start 节点应校验失败");
    }

    @Test
    @DisplayName("流程图：decision 节点只有一条边应校验失败")
    void testParseDecisionWithOneEdge() {
        // Given：decision 节点只有一条出边（应有两条：是/否）
        String json = """
                {
                  "title": "判断不完整",
                  "nodes": [
                    {"id": "1", "type": "start", "label": "开始"},
                    {"id": "2", "type": "decision", "label": "通过?"},
                    {"id": "3", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null},
                    {"from": "2", "to": "3", "label": "是"}
                  ]
                }
                """;

        // When + Then
        assertThrows(Exception.class, () -> {
            parserService.parse(json);
        }, "decision 只有一条边应校验失败");
    }

    @Test
    @DisplayName("流程图：边引用了不存在的节点应校验失败")
    void testParseEdgeReferencesMissingNode() {
        // Given：边的 to 指向 ID "999"，但节点列表里没有 999
        String json = """
                {
                  "title": "悬空边",
                  "nodes": [
                    {"id": "1", "type": "start", "label": "开始"},
                    {"id": "2", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "999", "label": null}
                  ]
                }
                """;

        // When + Then
        assertThrows(Exception.class, () -> {
            parserService.parse(json);
        }, "边引用不存在的节点应校验失败");
    }

    // ==================== 思维导图解析测试 ====================

    @Test
    @DisplayName("思维导图：合法嵌套 JSON 能正常解析")
    void testParseValidMindmap() throws Exception {
        // Given
        String json = """
                {
                  "label": "电商系统",
                  "children": [
                    {
                      "label": "前端",
                      "children": [
                        {"label": "Web 商城", "children": []}
                      ]
                    },
                    {
                      "label": "后端",
                      "children": []
                    }
                  ]
                }
                """;

        // When
        MindmapData data = parserService.parseMindmap(json);

        // Then
        assertNotNull(data, "解析结果不应为 null");
        assertEquals("电商系统", data.getLabel(), "根节点 label 应匹配");
        assertEquals(2, data.getChildren().size(), "应有 2 个子节点");
        assertEquals("前端", data.getChildren().get(0).getLabel(), "第一个子节点是前端");
        assertEquals(1, data.getChildren().get(0).getChildren().size(), "前端下有 1 个孙节点");
    }

    @Test
    @DisplayName("思维导图：缺少根节点 label 应校验失败")
    void testParseMindmapMissingLabel() {
        // Given：根节点 label 为空
        String json = """
                {
                  "label": "",
                  "children": []
                }
                """;

        // When + Then
        assertThrows(Exception.class, () -> {
            parserService.parseMindmap(json);
        }, "缺少根节点 label 应校验失败");
    }

    @Test
    @DisplayName("思维导图：LLM 漏返回 children 字段时不应 NPE")
    void testParseMindmapMissingChildren() throws Exception {
        // Given：JSON 里没有 children 字段
        String json = """
                {
                  "label": "只有根节点"
                }
                """;

        // When
        MindmapData data = parserService.parseMindmap(json);

        // Then：children 不应该是 null（因为 MindmapData 初始化了 new ArrayList<>()）
        assertNotNull(data.getChildren(), "children 不应为 null（有默认初始化）");
        assertTrue(data.getChildren().isEmpty(), "没有 children 字段时应是空列表");
    }

    // ==================== 架构图解析测试 ====================

    @Test
    @DisplayName("架构图：合法 JSON 能正常解析")
    void testParseValidArchitecture() throws Exception {
        // Given
        String json = """
                {
                  "title": "微服务架构",
                  "nodes": [
                    {"id": "1", "type": "process", "label": "API 网关"},
                    {"id": "2", "type": "process", "label": "订单服务"},
                    {"id": "3", "type": "process", "label": "MySQL"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": "HTTP"},
                    {"from": "2", "to": "3", "label": "SQL"}
                  ]
                }
                """;

        // When
        FlowchartData data = parserService.parseArchitecture(json);

        // Then
        assertNotNull(data, "解析结果不应为 null");
        assertEquals(3, data.getNodes().size(), "应有 3 个组件");
        assertEquals(2, data.getEdges().size(), "应有 2 条依赖");
    }

    @Test
    @DisplayName("架构图：节点列表为空应校验失败")
    void testParseArchitectureEmptyNodes() {
        // Given
        String json = """
                {
                  "title": "空架构",
                  "nodes": [],
                  "edges": []
                }
                """;

        // When + Then
        assertThrows(Exception.class, () -> {
            parserService.parseArchitecture(json);
        }, "组件列表为空应校验失败");
    }

    @Test
    @DisplayName("架构图：边引用不存在的组件应校验失败")
    void testParseArchitectureEdgeMissingNode() {
        // Given：边的 from 指向 "999"，节点列表里没有
        String json = """
                {
                  "title": "无效引用",
                  "nodes": [
                    {"id": "1", "type": "process", "label": "组件A"},
                    {"id": "2", "type": "process", "label": "组件B"}
                  ],
                  "edges": [
                    {"from": "1", "to": "999", "label": "调用"}
                  ]
                }
                """;

        // When + Then
        assertThrows(Exception.class, () -> {
            parserService.parseArchitecture(json);
        }, "边引用不存在的组件应校验失败");
    }
}

