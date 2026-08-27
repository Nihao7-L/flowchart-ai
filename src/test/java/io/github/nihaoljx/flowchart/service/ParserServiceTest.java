package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.FlowchartData;
import io.github.nihaoljx.flowchart.model.MindmapData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.nihaoljx.flowchart.model.ValidationIssue;
import java.util.List;


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

    // ==================== Opt1: 健壮解析测试 ====================

    /**
     * 以下测试验证 extractJsonText() 的容错能力：
     * LLM 返回的 JSON 可能被 ```json 围栏包住、前后夹带解释文字、或者多出未知字段。
     * 这些场景都应该能正常解析，而不是报错。
     */

    @Test
    @DisplayName("健壮解析：```json 围栏包裹的流程图 JSON 能正常解析")
    void testParseFencedJson() throws Exception {
        // Given：LLM 用 ```json 围栏包住了 JSON
        String fenced = """
                ```json
                {
                  "title": "登录流程",
                  "nodes": [
                    {"id": "1", "type": "start", "label": "开始"},
                    {"id": "2", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null}
                  ]
                }
                ```
                """;

        // When
        FlowchartData data = parserService.parse(fenced);

        // Then
        assertEquals("登录流程", data.getTitle(), "围栏内的 JSON 应能正常解析");
        assertEquals(2, data.getNodes().size(), "应有 2 个节点");
    }

    @Test
    @DisplayName("健壮解析：JSON 前后夹带解释文字能正常解析")
    void testParseJsonWithSurroundingText() throws Exception {
        // Given：LLM 在 JSON 前后加了废话
        String withText = """
                好的，这是您要的流程图数据：

                {
                  "title": "审批流程",
                  "nodes": [
                    {"id": "1", "type": "start", "label": "开始"},
                    {"id": "2", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null}
                  ]
                }

                希望对您有帮助！如有需要可以继续调整。
                """;

        // When
        FlowchartData data = parserService.parse(withText);

        // Then
        assertEquals("审批流程", data.getTitle(), "夹带文字的 JSON 应能正常解析");
    }

    @Test
    @DisplayName("健壮解析：JSON 含未知字段不报错")
    void testParseJsonWithUnknownFields() throws Exception {
        // Given：LLM 多输出了 model 和 timestamp 字段
        String withUnknown = """
                {
                  "title": "带未知字段的流程",
                  "model": "kimi-k2",
                  "timestamp": "2026-08-18",
                  "nodes": [
                    {"id": "1", "type": "start", "label": "开始"},
                    {"id": "2", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null}
                  ]
                }
                """;

        // When
        FlowchartData data = parserService.parse(withUnknown);

        // Then：多余字段被忽略，不报错
        assertEquals("带未知字段的流程", data.getTitle());
        assertEquals(2, data.getNodes().size(), "未知字段不影响正常字段解析");
    }

    @Test
    @DisplayName("健壮解析：纯 ``` 围栏（无 json 标识）能正常解析")
    void testParsePlainFencedJson() throws Exception {
        // Given：LLM 用 ``` 而不是 ```json
        String plainFenced = """
                ```
                {
                  "title": "纯围栏",
                  "nodes": [
                    {"id": "1", "type": "start", "label": "开始"},
                    {"id": "2", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null}
                  ]
                }
                ```
                """;

        // When
        FlowchartData data = parserService.parse(plainFenced);

        // Then
        assertEquals("纯围栏", data.getTitle(), "``` 围栏（无 json 标识）应能正常解析");
    }

    @Test
    @DisplayName("健壮解析：围栏 + 前后文字混合场景")
    void testParseFencedJsonWithSurroundingText() throws Exception {
        // Given：最复杂的场景——前有文字、后有围栏、围栏后又有文字
        String mixed = """
                这是为您生成的流程图：

                ```json
                {
                  "title": "混合场景",
                  "nodes": [
                    {"id": "1", "type": "start", "label": "开始"},
                    {"id": "2", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null}
                  ]
                }
                ```

                以上就是结果，请查收。
                """;

        // When
        FlowchartData data = parserService.parse(mixed);

        // Then
        assertEquals("混合场景", data.getTitle(), "围栏+前后文字混合应能正常解析");
    }

    @Test
    @DisplayName("健壮解析：思维导图 ```json 围栏包裹能正常解析")
    void testParseMindmapFenced() throws Exception {
        // Given
        String fenced = """
                ```json
                {
                  "label": "系统架构",
                  "children": [
                    {"label": "前端", "children": []},
                    {"label": "后端", "children": []}
                  ]
                }
                ```
                """;

        // When
        MindmapData data = parserService.parseMindmap(fenced);

        // Then
        assertEquals("系统架构", data.getLabel(), "围栏内的思维导图 JSON 应能正常解析");
        assertEquals(2, data.getChildren().size());
    }

    @Test
    @DisplayName("健壮解析：架构图 ```json 围栏包裹能正常解析")
    void testParseArchitectureFenced() throws Exception {
        // Given
        String fenced = """
                ```json
                {
                  "title": "微服务",
                  "nodes": [
                    {"id": "1", "type": "process", "label": "网关"},
                    {"id": "2", "type": "process", "label": "服务"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": "调用"}
                  ]
                }
                ```
                """;

        // When
        FlowchartData data = parserService.parseArchitecture(fenced);

        // Then
        assertEquals("微服务", data.getTitle(), "围栏内的架构图 JSON 应能正常解析");
        assertEquals(2, data.getNodes().size());
    }

    @Test
    @DisplayName("健壮解析：解析失败时错误信息包含原始文本片段")
    void testParseErrorContainsOriginalText() {
        // Given：一段完全无法解析的文本
        String garbage = "LLM 罢工了，返回了一段废话";

        // When + Then
        Exception ex = assertThrows(Exception.class, () -> {
            parserService.parse(garbage);
        });

        // 错误信息里应该能看到原始文本
        assertTrue(ex.getMessage().contains("LLM 罢工了"),
                "错误信息应包含原始文本片段，实际：" + ex.getMessage());
    }

    // ==================== Opt4: 结构化校验测试 ====================

    @Test
    @DisplayName("结构化校验：缺 title 返回 {field, reason, hint} 结构")
    void testValidationIssueMissingTitle() {
        // Given：title 为空 + 缺 start（同时两个问题）
        String json = """
                {
                  "title": "",
                  "nodes": [
                    {"id": "1", "type": "process", "label": "步骤"},
                    {"id": "2", "type": "end", "label": "结束"}
                  ],
                  "edges": [
                    {"from": "1", "to": "2", "label": null}
                  ]
                }
                """;

        // When
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parserService.parse(json));

        // Then：issue 里能找到 title 问题，且带修复建议
        List<ValidationIssue> issues = ex.getIssues();
        ValidationIssue titleIssue = issues.stream()
                .filter(i -> "title".equals(i.field()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("issues 中应有 title 问题，实际：" + issues));

        assertEquals("缺少流程图标题", titleIssue.reason(), "reason 说明为什么错");
        assertTrue(titleIssue.hint().contains("title"), "hint 应包含修复建议，实际：" + titleIssue.hint());
    }

    @Test
    @DisplayName("结构化校验：多个问题一次性全部返回")
    void testValidationReturnsAllIssuesAtOnce() {
        // Given：缺 title + 缺 start + 缺 end（三个问题同时存在）
        String json = """
                {
                  "title": "",
                  "nodes": [
                    {"id": "1", "type": "process", "label": "孤零零的步骤"}
                  ],
                  "edges": []
                }
                """;

        // When
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parserService.parse(json));

        // Then：三个问题都在列表里（这是"收集全部"和"抛一个就停"的本质区别）
        List<String> fields = ex.getIssues().stream()
                .map(ValidationIssue::field)
                .toList();
        assertTrue(fields.contains("title"), "应包含 title 问题，实际：" + fields);
        assertTrue(fields.contains("nodes[type=start]"), "应包含 start 问题，实际：" + fields);
        assertTrue(fields.contains("nodes[type=end]"), "应包含 end 问题，实际：" + fields);
        assertEquals(3, ex.getIssues().size(), "三个问题应一次全部返回");
    }

    @Test
    @DisplayName("结构化校验：decision 少一条出边返回具体节点信息")
    void testValidationIssueDecisionEdge() {
        // Given：decision 只有一条出边
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

        // When
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parserService.parse(json));

        // Then：问题挂在 edges[from=2]，hint 给出"是/否两条"的修复建议
        ValidationIssue issue = ex.getIssues().stream()
                .filter(i -> i.field().equals("edges[from=2]"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应能找到 decision 出边问题，实际：" + ex.getIssues()));

        assertTrue(issue.reason().contains("2 条出边"), "reason 应说明需要 2 条出边，实际：" + issue.reason());
        assertTrue(issue.hint().contains("是"), "hint 应提示是/否分支，实际：" + issue.hint());
    }

    @Test
    @DisplayName("结构化校验：悬空边返回带节点 ID 的 field")
    void testValidationIssueDanglingEdge() {
        // Given：边指向不存在的节点 999
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

        // When
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parserService.parse(json));

        // Then：field 精确到 edges[to=999]，用户一眼看到哪条边错了
        List<ValidationIssue> issues = ex.getIssues();
        assertTrue(issues.stream().anyMatch(i -> i.field().equals("edges[to=999]")),
                "应能找到悬空边问题，实际：" + issues);
        assertTrue(issues.stream().allMatch(i -> i.hint() != null && !i.hint().isBlank()),
                "每条 issue 都必须有修复建议");
    }

    @Test
    @DisplayName("结构化校验：架构图组件为空返回结构化错误")
    void testValidationIssueArchitectureEmptyNodes() {
        // Given：架构图 nodes 为空
        String json = """
                {
                  "title": "空架构",
                  "nodes": [],
                  "edges": []
                }
                """;

        // When
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parserService.parseArchitecture(json));

        // Then
        List<ValidationIssue> issues = ex.getIssues();
        assertTrue(issues.stream().anyMatch(i -> i.field().equals("nodes")),
                "应报 nodes 为空，实际：" + issues);
        assertTrue(issues.get(0).hint().contains("节点"), "hint 应给出修复建议，实际：" + issues.get(0).hint());
    }

    // ==================== 任务37：Mermaid 提取测试 ====================

    @Test
    @DisplayName("任务37：extractMermaid 正常 JSON 能提取 mermaid 字段")
    void testExtractMermaidValid() throws Exception {
        String llmText = "{\"mermaid\":\"flowchart LR\\n A[前端]-->B[网关]\"}";
        String result = parserService.extractMermaid(llmText);
        assertTrue(result.contains("flowchart LR"), "应提取出 mermaid 代码");
        assertTrue(result.contains("A[前端]"), "应保留节点定义");
    }

    @Test
    @DisplayName("任务37：extractMermaid 字段为空或空白抛 ValidationException")
    void testExtractMermaidEmptyThrows() {
        String llmText = "{\"mermaid\":\"   \"}";
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parserService.extractMermaid(llmText));
        assertEquals("mermaid", ex.getIssues().get(0).field(), "问题应挂在 mermaid 字段");
    }

    @Test
    @DisplayName("任务37：extractMermaid 非法 JSON 抛 ValidationException")
    void testExtractMermaidInvalidJsonThrows() {
        String llmText = "这段返回里完全没有 JSON 结构";
        assertThrows(ValidationException.class,
                () -> parserService.extractMermaid(llmText));
    }
}
