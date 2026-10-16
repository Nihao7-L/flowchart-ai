package io.github.nihaoljx.flowchart.graph.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 需求类别判定（D5 前置闸门）单测。
 *
 * <p>用 5 张参考图的真实需求原文，验证「图表类」与「绘画/构图类」区分正确，
 * 且判不出的需求不会误伤（UNKNOWN 不触发拦截）。
 */
class RequestIntentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("5 张参考图真实需求：4 图表 + 猫=绘画，分类与预期一致")
    void classifiesBenchPrompts() throws Exception {
        try (InputStream in = RequestIntentTest.class
                .getResourceAsStream("/bench/ref-prompts.json")) {
            assertNotNull(in, "缺失测试资源 /bench/ref-prompts.json");
            JsonNode root = mapper.readTree(in);
            for (JsonNode c : root.get("cases")) {
                String id = c.get("id").asText();
                String prompt = c.get("prompt").asText();
                String expected = c.get("expectedIntent").asText();
                assertEquals(RequestIntent.valueOf(expected),
                        RequestIntent.classify(prompt),
                        "需求 [" + id + "] 分类应为 " + expected);
            }
        }
    }

    @Test
    @DisplayName("判定顺序：'猫的生命周期'含 生命周期 ⇒ DIAGRAM，不与'画猫'混淆")
    void lifecycleCatIsDiagram() {
        assertEquals(RequestIntent.DIAGRAM,
                RequestIntent.classify("画一张猫的生命周期流程图"));
        assertEquals(RequestIntent.NON_DIAGRAM,
                RequestIntent.classify("画一只可爱的卡通猫"));
    }

    @Test
    @DisplayName("判不出 = UNKNOWN，且 UNKNOWN 不触发拦截（isNonDiagram=false）")
    void unknownDoesNotBlock() {
        assertEquals(RequestIntent.UNKNOWN,
                RequestIntent.classify("随便聊聊"));
        assertEquals(RequestIntent.UNKNOWN,
                RequestIntent.classify(null));
        assertFalse(RequestIntent.UNKNOWN.isNonDiagram());
    }
}
