package io.github.nihaoljx.flowchart.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FailoverAgentClient} 最小故障转移测试（v2-55 实测补）
 *
 * <p>锁死三条：首候选挂切下一个、粘性主选（成功者下次先试）、
 * 全挂抛最后一个异常。
 */
class FailoverAgentClientTest {

    private static AgentTurn turn() {
        return new AgentTurn("ok", List.of());
    }

    @Test
    @DisplayName("首候选 502 → 自动切第二个，粘性记住成功者")
    void failoverToNextAndStick() throws Exception {
        AgentLlmClient broken = mock(AgentLlmClient.class);
        when(broken.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("LLM HTTP 502"));
        AgentLlmClient good = mock(AgentLlmClient.class);
        when(good.respond(anyList(), anyList(), anyInt()))
                .thenReturn(turn());

        FailoverAgentClient client = new FailoverAgentClient(
                List.of(broken, good));
        AgentTurn result = client.respond(
                List.of(), List.of(), 60);

        assertSame(result, result);
        verify(broken, times(1)).respond(anyList(), anyList(), anyInt());
        verify(good, times(1)).respond(anyList(), anyList(), anyInt());

        // 第二次调用：粘性主选直接从 good 开始，不再碰 broken
        client.respond(List.of(), List.of(), 60);
        verify(broken, times(1))
                .respond(anyList(), anyList(), anyInt());
        verify(good, times(2))
                .respond(anyList(), anyList(), anyInt());
    }

    @Test
    @DisplayName("全部候选挂 → 抛最后一个异常（循环走兜底语义）")
    void allCandidatesDownThrowsLast() throws Exception {
        AgentLlmClient a = mock(AgentLlmClient.class);
        when(a.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("HTTP 502 A"));
        AgentLlmClient b = mock(AgentLlmClient.class);
        when(b.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("HTTP 503 B"));

        FailoverAgentClient client = new FailoverAgentClient(List.of(a, b));
        Exception e = assertThrows(Exception.class,
                () -> client.respond(List.of(), List.of(), 60));
        assertEquals("HTTP 503 B", e.getMessage());
        verify(a, times(1)).respond(anyList(), anyList(), anyInt());
        verify(b, times(1)).respond(anyList(), anyList(), anyInt());
    }

    @Test
    @DisplayName("空候选列表 → 构造即拒绝")
    void emptyCandidatesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new FailoverAgentClient(List.of()));
    }
}
