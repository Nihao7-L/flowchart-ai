package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.agent.AgentLoopService;
import io.github.nihaoljx.flowchart.model.ChatEvent;
import io.github.nihaoljx.flowchart.session.SessionStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 需求类别闸门（2026-10-16 ref_cat 事故）端到端冒烟：
 * 绘画/构图类需求走 generate → 前置拦截 → 下发诚实说明占位，
 * **绝不调用模型**、绝不留零件关系图。
 */
class GenerationServiceGateTest {

    private AgentLoopService agent;
    private SessionStore sessionStore;
    private GenerationService service;

    @BeforeEach
    void setUp() {
        agent = mock(AgentLoopService.class);
        sessionStore = mock(SessionStore.class);
        service = new GenerationService(agent, sessionStore, new PromptService());
    }

    private List<ChatEvent> events(SseEmitter emitter) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("【回归·猫事故】画一只卡通猫 → 不下发零件图，下发【AI 备注】说明且零模型调用")
    void catRequestShortCircuitsToLimitationPlaceholder() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null,
                "画一只可爱的卡通猫，用最简单的几何形状拼："
                + "一个椭圆当身体、一个圆当脑袋、两个三角形当耳朵……",
                emitter);

        // 1) 闸门前置拦截：根本没碰模型（省掉一次 25~30s 调用）
        verify(agent, never()).run(any(), any(), anyList(), any(), anyLong(), any(), any());

        // 2) 下发的是诚实说明占位，不是事故现场那种零件关系图
        List<ChatEvent> all = events(emitter);
        ChatEvent result = all.stream()
                .filter(e -> "result".equals(e.type()))
                .reduce((a, b) -> b).orElseThrow();
        String scene = result.data();
        assertTrue(scene.contains("【AI 备注】"),
                "必须下发如实说明能力边界的占位: " + scene);
        assertFalse(scene.contains("Meow~ ♥"),
                "绝不能下发事故现场那种零件关系图");

        // 3) 说明占位只有 2 个元素（说明框 + 建议行），不是 15 个椭圆
        int elems = assertDoesNotThrow(() -> {
            var root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(scene);
            return root.get("elements").size();
        });
        assertEquals(2, elems, "说明占位应只有说明框 + 建议行两元素");
    }
}
