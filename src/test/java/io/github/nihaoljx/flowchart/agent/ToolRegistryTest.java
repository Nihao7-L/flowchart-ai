package io.github.nihaoljx.flowchart.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.nihaoljx.flowchart.llm.AgentTool;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolCall;
import io.github.nihaoljx.flowchart.llm.ToolContext;

/**
 * 工具派发表单元测试（v2-55）
 *
 * <p>核心纪律：未知工具不抛、执行异常不穿 —— 循环的存活优先于
 * 单次工具的成败（失败 observation 回喂模型，让它自己纠偏）。
 */
class ToolRegistryTest {

    private static AgentTool named(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return Map.of("type", "object");
            }

            @Override
            public Observation execute(
                    com.fasterxml.jackson.databind.JsonNode args,
                    ToolContext ctx) {
                if ("boom".equals(name)) {
                    throw new IllegalStateException("炸了");
                }
                return Observation.ok("ok", null);
            }
        };
    }

    @Test
    @DisplayName("未知工具 → 失败 observation（列出可用工具），不抛异常")
    void unknownToolFailsGracefully() {
        ToolRegistry registry =
                new ToolRegistry(List.of(named("alpha")));
        ToolCall call = new ToolCall("c1", "nope", null);
        Observation obs = registry.dispatch(call, new ToolContext(null, null));
        assertFalse(obs.ok());
        assertTrue(obs.summary().contains("未知工具"));
        assertTrue(obs.summary().contains("alpha"));
    }

    @Test
    @DisplayName("工具执行异常 → 兜成失败 observation，不打穿循环")
    void toolExceptionIsContained() {
        ToolRegistry registry = new ToolRegistry(List.of(named("boom")));
        Observation obs = registry.dispatch(
                new ToolCall("c1", "boom", null),
                new ToolContext(null, null));
        assertFalse(obs.ok());
        assertTrue(obs.summary().contains("执行异常"));
    }

    @Test
    @DisplayName("重名注册 → 装配即失败（fail-fast）")
    void duplicateNameRejected() {
        assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(named("a"), named("a"))));
    }

    @Test
    @DisplayName("schemas() 产出 OpenAI function 口径的包装结构")
    void schemasUseFunctionWrapper() {
        ToolRegistry registry =
                new ToolRegistry(List.of(named("alpha")));
        assertEquals(1, registry.schemas().size());
        Map<String, Object> wrapper = registry.schemas().get(0);
        assertEquals("function", wrapper.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) wrapper.get("function");
        assertEquals("alpha", fn.get("name"));
        assertTrue(fn.containsKey("description"));
        assertTrue(fn.containsKey("parameters"));
    }
}
