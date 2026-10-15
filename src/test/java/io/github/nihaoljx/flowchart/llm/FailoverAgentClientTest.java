package io.github.nihaoljx.flowchart.llm;

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
 * <p>锁死口径（2026-10-14 增补 502 退避重试后共六条）：瞬时错误同候选
 * 退避重试、非瞬时错误立即切下一家、首候选挂切下一个、粘性主选
 * （成功者下次先试）、全挂抛最后一个异常、空候选列表构造即拒。
 */
class FailoverAgentClientTest {

    private static AgentTurn turn() {
        return new AgentTurn("ok", List.of());
    }

    @Test
    @DisplayName("首候选 502 → 重试仍挂切第二个，粘性记住成功者")
    void failoverToNextAndStick() throws Exception {
        AgentLlmClient broken = mock(AgentLlmClient.class);
        when(broken.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("LLM HTTP 502"));
        AgentLlmClient good = mock(AgentLlmClient.class);
        when(good.respond(anyList(), anyList(), anyInt()))
                .thenReturn(turn());

        // attempts=1：本条只锁「切候选」语义，重试语义另有专门用例
        FailoverAgentClient client = new FailoverAgentClient(
                List.of(broken, good), 1, 0);
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

        FailoverAgentClient client = new FailoverAgentClient(
                List.of(a, b), 1, 0);
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

    @Test
    @DisplayName("瞬时 502 → 同候选退避重试，第 2 次命中不切候选")
    void transientRetriedOnSameCandidate() throws Exception {
        AgentLlmClient flaky = mock(AgentLlmClient.class);
        when(flaky.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException(
                        "LLM HTTP 502: 网关过载"))
                .thenReturn(turn());
        AgentLlmClient other = mock(AgentLlmClient.class);
        when(other.respond(anyList(), anyList(), anyInt()))
                .thenReturn(turn());

        FailoverAgentClient client = new FailoverAgentClient(
                List.of(flaky, other), 3, 1);
        AgentTurn result = client.respond(List.of(), List.of(), 60);

        assertSame("ok", result.content());
        verify(flaky, times(2))
                .respond(anyList(), anyList(), anyInt());
        verify(other, times(0))
                .respond(anyList(), anyList(), anyInt());
    }

    @Test
    @DisplayName("非瞬时错误（400）→ 不重试，立即切下一个候选")
    void nonTransientMovesOnImmediately() throws Exception {
        AgentLlmClient bad = mock(AgentLlmClient.class);
        when(bad.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException(
                        "LLM HTTP 400: bad request"));
        AgentLlmClient good = mock(AgentLlmClient.class);
        when(good.respond(anyList(), anyList(), anyInt()))
                .thenReturn(turn());

        FailoverAgentClient client = new FailoverAgentClient(
                List.of(bad, good), 3, 1);
        client.respond(List.of(), List.of(), 60);

        verify(bad, times(1))
                .respond(anyList(), anyList(), anyInt());
        verify(good, times(1))
                .respond(anyList(), anyList(), anyInt());
    }

    @Test
    @DisplayName("瞬时错误重试耗尽 → 按序切候选，全挂抛最后一次异常")
    void transientExhaustedFallsThrough() throws Exception {
        AgentLlmClient a = mock(AgentLlmClient.class);
        when(a.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("LLM HTTP 502 A"));
        AgentLlmClient b = mock(AgentLlmClient.class);
        when(b.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("LLM HTTP 503 B"));

        FailoverAgentClient client = new FailoverAgentClient(
                List.of(a, b), 2, 1);
        Exception e = assertThrows(Exception.class,
                () -> client.respond(List.of(), List.of(), 60));
        assertEquals("LLM HTTP 503 B", e.getMessage());
        verify(a, times(2)).respond(anyList(), anyList(), anyInt());
        verify(b, times(2)).respond(anyList(), anyList(), anyInt());
    }

    @Test
    @DisplayName("主力让位后：每 5 次成功后回头探主力，主力复活即夺回主选")
    void periodicProbeReclaimsPrimary() throws Exception {
        // 主力（下标 0）首调超时让位，之后恢复；备用（下标 1）一直可用
        AgentLlmClient primary = mock(AgentLlmClient.class);
        when(primary.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException(
                        "LLM HTTP 408: 请求超时"))   // 第一次：超时让位
                .thenReturn(turn());                  // 探针轮起：已恢复
        AgentLlmClient backup = mock(AgentLlmClient.class);
        when(backup.respond(anyList(), anyList(), anyInt()))
                .thenReturn(turn());

        FailoverAgentClient client = new FailoverAgentClient(
                List.of(primary, backup), 1, 0);

        // 第 1 次：主力超时 → 切备用（粘性记住备用）
        client.respond(List.of(), List.of(), 60);
        verify(primary, times(1)).respond(anyList(), anyList(), anyInt());
        verify(backup, times(1)).respond(anyList(), anyList(), anyInt());

        // 第 2~3 次：粘性主选从备用开始，主力不被触碰
        for (int n = 0; n < 2; n++) {
            client.respond(List.of(), List.of(), 60);
        }
        verify(primary, times(1))
                .respond(anyList(), anyList(), anyInt());
        verify(backup, times(3))
                .respond(anyList(), anyList(), anyInt());

        // 第 4 次：已达 3 次成功 → 强制回头探主力；主力已恢复 → 夺回主选
        client.respond(List.of(), List.of(), 60);
        verify(primary, times(2))
                .respond(anyList(), anyList(), anyInt());
        verify(backup, times(3))
                .respond(anyList(), anyList(), anyInt());

        // 第 5 次：主选已回主力，继续从主力开始
        client.respond(List.of(), List.of(), 60);
        verify(primary, times(3))
                .respond(anyList(), anyList(), anyInt());
    }
}
