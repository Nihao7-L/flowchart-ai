package io.github.nihaoljx.flowchart.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAiToolClient.SseAccumulator 单元测试。
 *
 * 锁死流式累积的三条铁律（这块最易错：tool_calls 的 arguments 是分片下发的，
 * 拼错 = agent 收到畸形 JSON = 整轮作废）：
 * - content 分片顺序拼接、null/空跳过；
 * - tool_calls 按 index 归属，arguments 分片拼成完整 JSON、id/name 取首片；
 * - reasoning_content（思维链）被忽略，不进 content。
 */
class SseAccumulatorTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode contentDelta(String text) {
        ObjectNode d = M.createObjectNode();
        if (text != null) {
            d.put("content", text);
        }
        return d;
    }

    private static JsonNode toolDelta(
            int index, String id, String name, String argsFrag) {
        ObjectNode tc = M.createObjectNode();
        tc.put("index", index);
        if (id != null) {
            tc.put("id", id);
        }
        ObjectNode fn = tc.putObject("function");
        if (name != null) {
            fn.put("name", name);
        }
        if (argsFrag != null) {
            fn.put("arguments", argsFrag);
        }
        ObjectNode d = M.createObjectNode();
        d.putArray("tool_calls").add(tc);
        return d;
    }

    @Test
    @DisplayName("content 分片顺序拼接，null 跳过")
    void contentFragmentsConcatenate() {
        OpenAiToolClient.SseAccumulator acc =
                new OpenAiToolClient.SseAccumulator();
        acc.feed(contentDelta("你好"));
        acc.feed(contentDelta("，世界"));
        acc.feed(contentDelta(null));
        AgentTurn turn = acc.toTurn();
        assertEquals("你好，世界", turn.content());
        assertTrue(turn.toolCalls().isEmpty());
    }

    @Test
    @DisplayName("tool_calls 的 arguments 分片按 index 拼成完整 JSON")
    void toolCallArgumentsFragmentsConcatenate() {
        OpenAiToolClient.SseAccumulator acc =
                new OpenAiToolClient.SseAccumulator();
        acc.feed(toolDelta(0, "call_1", "submit_scene",
                "{\"elements\":["));
        acc.feed(toolDelta(0, null, null, "{\"id\":\"a\"}"));
        acc.feed(toolDelta(0, null, null, "]}"));
        AgentTurn turn = acc.toTurn();
        assertEquals(1, turn.toolCalls().size());
        ToolCall c = turn.toolCalls().get(0);
        assertEquals("call_1", c.id());
        assertEquals("submit_scene", c.name());
        JsonNode args = c.arguments();
        assertTrue(args.has("elements"),
                "arguments 应拼成含 elements 的完整 JSON，实际: " + args);
        assertEquals("a", args.get("elements").get(0).get("id").asText());
    }

    @Test
    @DisplayName("多个 tool_call 按 index 分别累积、保持顺序")
    void multipleToolCallsByIndex() {
        OpenAiToolClient.SseAccumulator acc =
                new OpenAiToolClient.SseAccumulator();
        acc.feed(toolDelta(0, "c0", "read_scene", "{}"));
        acc.feed(toolDelta(1, "c1", "submit_scene", "{\"a\":"));
        acc.feed(toolDelta(1, null, null, "1}"));
        AgentTurn turn = acc.toTurn();
        assertEquals(2, turn.toolCalls().size());
        assertEquals("read_scene", turn.toolCalls().get(0).name());
        assertEquals("submit_scene", turn.toolCalls().get(1).name());
        assertEquals(1, turn.toolCalls().get(1).arguments().get("a").asInt());
    }

    @Test
    @DisplayName("reasoning_content 被忽略，不进 content")
    void reasoningContentIgnored() {
        OpenAiToolClient.SseAccumulator acc =
                new OpenAiToolClient.SseAccumulator();
        ObjectNode d = M.createObjectNode();
        d.putNull("content");
        d.put("reasoning_content", "让我想想");
        acc.feed(d);
        acc.feed(contentDelta("答案"));
        AgentTurn turn = acc.toTurn();
        assertEquals("答案", turn.content());
        assertFalse(turn.content().contains("想想"));
    }

    @Test
    @DisplayName("回归：siliconflow 续片 name:\"\" 不得覆盖首片工具名")
    void emptyNameFragmentDoesNotOverwrite() {
        OpenAiToolClient.SseAccumulator acc =
                new OpenAiToolClient.SseAccumulator();
        acc.feed(toolDelta(0, "call_1", "submit_spec", ""));
        acc.feed(toolDelta(0, null, "", "{\"spec\":"));
        acc.feed(toolDelta(0, null, "", "{\"a\":1}}"));
        AgentTurn turn = acc.toTurn();
        assertEquals(1, turn.toolCalls().size());
        ToolCall c = turn.toolCalls().get(0);
        assertEquals("submit_spec", c.name());
        assertEquals("call_1", c.id());
        assertEquals(1, c.arguments().get("spec").get("a").asInt());
    }

    @Test
    @DisplayName("回归：续片 id:\"\" 不得覆盖首片 id")
    void emptyIdFragmentDoesNotOverwrite() {
        OpenAiToolClient.SseAccumulator acc =
                new OpenAiToolClient.SseAccumulator();
        acc.feed(toolDelta(0, "call_9", "submit_spec", "{}"));
        acc.feed(toolDelta(0, "", "submit_spec", "{}"));
        AgentTurn turn = acc.toTurn();
        assertEquals("call_9", turn.toolCalls().get(0).id());
    }
}
