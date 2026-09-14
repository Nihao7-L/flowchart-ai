package io.github.nihaoljx.flowchart.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据模型契约测试（IR 与 API 外壳）
 *
 * 测试范围：Result 统一响应外壳、MindmapData 的递归兜底
 *
 * 注：请求 record（GenerateRequest / DownloadRequest）已随 M1 前置代码清算删除，
 * 对应的字段契约测试一并移除；M1 引入 /api/chat 的新请求体后在此补回。
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
