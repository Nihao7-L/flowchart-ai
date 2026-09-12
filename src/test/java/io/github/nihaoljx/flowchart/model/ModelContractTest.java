package io.github.nihaoljx.flowchart.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据模型契约测试（IR 与 API 外壳）
 *
 * 测试范围：Result 统一响应结构、请求 record 的字段语义、MindmapData 的递归兜底
 *
 * 覆盖的失败模式：前端依赖的响应字段改名/改语义；LLM 漏返回 children 时 NPE
 */
class ModelContractTest {

    // ==================== Result 统一响应 ====================

    @Test
    @DisplayName("Result.success(data)：code=200、message=ok、data 原样透传")
    void resultSuccessCarriesData() {
        Object payload = List.of("a", "b");
        Result<Object> r = Result.success(payload);

        assertEquals(200, r.getCode());
        assertEquals("ok", r.getMessage());
        assertSame(payload, r.getData());
    }

    @Test
    @DisplayName("Result.success()：无数据版 data 为 null")
    void resultSuccessWithoutData() {
        Result<Void> r = Result.success();

        assertEquals(200, r.getCode());
        assertEquals("ok", r.getMessage());
        assertNull(r.getData());
    }

    @Test
    @DisplayName("Result.error(code,msg)：data 恒为 null")
    void resultErrorHasNoData() {
        Result<Void> r = Result.error(400, "请输入流程描述");

        assertEquals(400, r.getCode());
        assertEquals("请输入流程描述", r.getMessage());
        assertNull(r.getData());
    }

    // ==================== 请求 record ====================

    @Test
    @DisplayName("GenerateRequest：三个字段按顺序可取，且允许为 null")
    void generateRequestAccessors() {
        GenerateRequest req = new GenerateRequest("甲到乙", "flowchart", "svg");
        assertEquals("甲到乙", req.text());
        assertEquals("flowchart", req.type());
        assertEquals("svg", req.format());

        GenerateRequest empty = new GenerateRequest(null, null, null);
        assertNull(empty.text());
        assertNull(empty.type());
        assertNull(empty.format());
    }

    @Test
    @DisplayName("GenerateRequest：record 的相等性按字段值比较")
    void generateRequestEquality() {
        assertEquals(new GenerateRequest("a", "flowchart", "svg"),
                new GenerateRequest("a", "flowchart", "svg"));
        assertNotEquals(new GenerateRequest("a", "flowchart", "svg"),
                new GenerateRequest("a", "mindmap", "svg"));
    }

    @Test
    @DisplayName("DownloadRequest：字段可取，format 允许为 null（由 controller 兜默认值）")
    void downloadRequestAccessors() {
        DownloadRequest req = new DownloadRequest("@startuml\n@enduml", "png");
        assertEquals("@startuml\n@enduml", req.plantUml());
        assertEquals("png", req.format());

        assertNull(new DownloadRequest("x", null).format());
    }

    // ==================== MindmapData 递归兜底 ====================

    @Test
    @DisplayName("MindmapData：新建对象 children 不为 null（避免遍历时 NPE）")
    void mindmapChildrenNeverNull() {
        MindmapData node = new MindmapData();
        assertNotNull(node.getChildren());
        assertTrue(node.getChildren().isEmpty());
    }

    @Test
    @DisplayName("MindmapData：setChildren(null) 兜底成空列表")
    void mindmapSetChildrenNullFallsBack() {
        MindmapData node = new MindmapData();
        node.setChildren(null);

        assertNotNull(node.getChildren(), "LLM 漏返回 children 时不应得到 null");
        assertTrue(node.getChildren().isEmpty());
    }

    @Test
    @DisplayName("MindmapData：支持多层嵌套（父子关系长在对象里）")
    void mindmapSupportsNesting() {
        MindmapData root = new MindmapData();
        root.setLabel("电商系统");

        MindmapData frontend = new MindmapData();
        frontend.setLabel("前端");

        MindmapData mall = new MindmapData();
        mall.setLabel("Web 商城");

        frontend.setChildren(List.of(mall));
        root.setChildren(List.of(frontend));

        assertEquals("电商系统", root.getLabel());
        assertEquals("前端", root.getChildren().get(0).getLabel());
        assertEquals("Web 商城",
                root.getChildren().get(0).getChildren().get(0).getLabel());
    }
}
