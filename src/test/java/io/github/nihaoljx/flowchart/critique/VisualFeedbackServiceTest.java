package io.github.nihaoljx.flowchart.critique;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排器单测：开关关闭返回空、开启几何批判返回重叠问题、坏 JSON / null 安全。
 */
class VisualFeedbackServiceTest {

    private static final String SCENE = "["
            + "{\"id\":\"a\",\"type\":\"rectangle\",\"x\":0,\"y\":0,"
            + "\"width\":100,\"height\":40},"
            + "{\"id\":\"b\",\"type\":\"rectangle\",\"x\":50,\"y\":10,"
            + "\"width\":100,\"height\":40}]";

    @Test
    void disabledReturnsEmpty() {
        var svc = new VisualFeedbackService(
                new VisualFeedbackConfig(false, true, false),
                new GeometricCritic(), new NoopVisionCritic());
        var r = svc.critique(SCENE, "x");
        assertFalse(r.needsFix());
        assertEquals("", r.guidance());
    }

    @Test
    void enabledGeometricReturnsOverlap() {
        var svc = new VisualFeedbackService(
                new VisualFeedbackConfig(true, true, false),
                new GeometricCritic(), new NoopVisionCritic());
        var r = svc.critique(SCENE, "x");
        assertTrue(r.needsFix());
        assertTrue(r.guidance().contains("视觉自检"));
        assertTrue(r.guidance().contains("重叠"));
    }

    @Test
    void badJsonSafe() {
        var svc = new VisualFeedbackService(
                new VisualFeedbackConfig(true, true, false),
                new GeometricCritic(), new NoopVisionCritic());
        assertFalse(svc.critique("not json", "x").needsFix());
    }

    @Test
    void nullSceneSafe() {
        var svc = new VisualFeedbackService(
                new VisualFeedbackConfig(true, true, false),
                new GeometricCritic(), new NoopVisionCritic());
        assertFalse(svc.critique(null, "x").needsFix());
    }
}
