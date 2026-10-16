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
 * <p>锁死口径（2026-10-16 取消「粘性主选」后）：瞬时错误同候选退避重试、
 * 非瞬时错误立即切下一家、首候选挂切下一个、**每次调用都从主力（下标 0）
 * 重新试起**（不沿用上一次成功的候选）、全挂抛最后一个异常、空候选列表
 * 构造即拒；另有挂死冷却与冷却倍率边界两条。
 */
class FailoverAgentClientTest {

    private static AgentTurn turn() {
        return new AgentTurn("ok", List.of());
    }

    @Test
    @DisplayName("首候选 502 → 切第二个；下次调用仍从候选 1 重新试起（无粘性）")
    void failoverToNextWithoutStick() throws Exception {
        AgentLlmClient broken = mock(AgentLlmClient.class);
        when(broken.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("LLM HTTP 502"));
        AgentLlmClient good = mock(AgentLlmClient.class);
        when(good.respond(anyList(), anyList(), anyInt()))
                .thenReturn(turn());

        // attempts=1：本条只锁「切候选」语义，重试语义另有专门用例
        FailoverAgentClient client = new FailoverAgentClient(
                List.of(broken, good), 1, 0);
        client.respond(List.of(), List.of(), 60);

        verify(broken, times(1)).respond(anyList(), anyList(), anyInt());
        verify(good, times(1)).respond(anyList(), anyList(), anyInt());

        // 第二次调用：无粘性 —— 仍从候选 1 开始试（每张图都从主力试起）
        client.respond(List.of(), List.of(), 60);
        verify(broken, times(2))
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
    @DisplayName("主力超时让位后，下次请求仍先试主力（无粘性，主力恢复即直接用）")
    void primaryRetriedOnNextCall() throws Exception {
        // 主力（下标 0）第 1 次超时，之后恢复；备用（下标 1）一直可用
        AgentLlmClient primary = mock(AgentLlmClient.class);
        when(primary.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException(
                        "LLM HTTP 408: 请求超时"))   // 第 1 次：超时让位
                .thenReturn(turn());                  // 之后：已恢复
        AgentLlmClient backup = mock(AgentLlmClient.class);
        when(backup.respond(anyList(), anyList(), anyInt()))
                .thenReturn(turn());

        FailoverAgentClient client = new FailoverAgentClient(
                List.of(primary, backup), 1, 0);

        // 第 1 次：主力超时 → 备用接住
        client.respond(List.of(), List.of(), 60);
        verify(primary, times(1)).respond(anyList(), anyList(), anyInt());
        verify(backup, times(1)).respond(anyList(), anyList(), anyInt());

        // 第 2 次：无粘性 —— 仍从主力开始；主力已恢复 → 直接用主力，
        // 备用本轮不被触碰（旧行为是粘在备用上，要等 3 次成功才回头探）
        client.respond(List.of(), List.of(), 60);
        verify(primary, times(2)).respond(anyList(), anyList(), anyInt());
        verify(backup, times(1)).respond(anyList(), anyList(), anyInt());
    }

    @Test
    @DisplayName("候选挂死（超时）→ 记短冷却，冷却期内后续请求直接跳过它")
    void hangCandidateCooledDown() throws Exception {
        // 挂死消息与线上一致：HttpTimeoutException "request timed out"
        AgentLlmClient hanging = mock(AgentLlmClient.class);
        when(hanging.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException(
                        "request timed out"));
        AgentLlmClient good = mock(AgentLlmClient.class);
        when(good.respond(anyList(), anyList(), anyInt()))
                .thenReturn(turn());

        FailoverAgentClient client = new FailoverAgentClient(
                List.of(hanging, good), 1, 0);

        // 第 1 次：候选 1 挂死 → 切候选 2
        client.respond(List.of(), List.of(), 60);
        verify(hanging, times(1))
                .respond(anyList(), anyList(), anyInt());
        verify(good, times(1))
                .respond(anyList(), anyList(), anyInt());

        // 第 2 次：候选 1 在冷却期内 → 跳过它（修复前：每个新请求都要
        // 再付一遍挂满窗口的代价）
        client.respond(List.of(), List.of(), 60);
        verify(hanging, times(1))
                .respond(anyList(), anyList(), anyInt());
        verify(good, times(2))
                .respond(anyList(), anyList(), anyInt());
    }

    @Test
    @DisplayName("挂死冷却倍率：1 次 ×1、2 次 ×5、3 次 ×15 且封顶 30 分钟")
    void strikeMultiplierBoundaries() {
        assertEquals(1, FailoverAgentClient.strikeMultiplier(1));
        assertEquals(5, FailoverAgentClient.strikeMultiplier(2));
        assertEquals(15, FailoverAgentClient.strikeMultiplier(3));
        assertEquals(15, FailoverAgentClient.strikeMultiplier(99));
        // 封顶 = 基础冷却 ×15（3 次及以上都封顶）
        assertEquals(FailoverAgentClient.HANG_COOLDOWN_MS * 15,
                FailoverAgentClient.HANG_COOLDOWN_CAP_MS);
    }

    @Test
    @DisplayName("全员挂死全在冷却 → 无视冷却照常全试（避免无可用的死路）")
    void allCoolingFallsBackToFullSweep() throws Exception {
        AgentLlmClient a = mock(AgentLlmClient.class);
        when(a.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("request timed out"));
        AgentLlmClient b = mock(AgentLlmClient.class);
        when(b.respond(anyList(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("request timed out"));

        FailoverAgentClient client = new FailoverAgentClient(
                List.of(a, b), 1, 0);

        // 第 1 次：全挂，两个都进冷却
        assertThrows(Exception.class,
                () -> client.respond(List.of(), List.of(), 60));
        verify(a, times(1)).respond(anyList(), anyList(), anyInt());
        verify(b, times(1)).respond(anyList(), anyList(), anyInt());

        // 第 2 次：全员冷却 → 无视冷却再试一遍（否则没有候选可用）
        assertThrows(Exception.class,
                () -> client.respond(List.of(), List.of(), 60));
        verify(a, times(2)).respond(anyList(), anyList(), anyInt());
        verify(b, times(2)).respond(anyList(), anyList(), anyInt());
    }
}
