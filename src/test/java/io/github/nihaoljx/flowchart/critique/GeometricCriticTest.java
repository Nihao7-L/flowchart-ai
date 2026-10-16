package io.github.nihaoljx.flowchart.critique;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性几何批判器单测：覆盖重叠 / 出界 / 标签溢出 / 过小 / 连线交叉 /
 * 共享端点不算交叉 / 空输入安全。
 */
class GeometricCriticTest {

    private final GeometricCritic critic = new GeometricCritic();

    private Map<String, Object> box(String id, double x, double y,
                                   double w, double h, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", "rectangle");
        m.put("x", x);
        m.put("y", y);
        m.put("width", w);
        m.put("height", h);
        if (text != null) {
            m.put("text", text);
        }
        return m;
    }

    private Map<String, Object> arrow(String id, String s, String e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", "arrow");
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("elementId", s);
        Map<String, Object> eb = new LinkedHashMap<>();
        eb.put("elementId", e);
        m.put("startBinding", sb);
        m.put("endBinding", eb);
        return m;
    }

    private boolean has(List<CritiqueIssue> issues, CritiqueIssue.Category c) {
        return issues.stream().anyMatch(i -> i.category() == c);
    }

    @Test
    void overlapDetected() {
        var els = List.of(box("a", 0, 0, 100, 40, null),
                box("b", 50, 10, 100, 40, null));
        assertTrue(has(critic.critique(els, "x"), CritiqueIssue.Category.OVERLAP));
    }

    @Test
    void noFalseOverlapWhenSeparate() {
        var els = List.of(box("a", 0, 0, 100, 40, null),
                box("b", 300, 0, 100, 40, null));
        assertFalse(has(critic.critique(els, "x"), CritiqueIssue.Category.OVERLAP));
    }

    @Test
    void offCanvasDetected() {
        var els = List.of(box("a", -10, -5, 100, 40, null));
        assertTrue(has(critic.critique(els, "x"), CritiqueIssue.Category.OFF_CANVAS));
    }

    @Test
    void labelOverflowDetected() {
        var els = List.of(box("a", 0, 0, 40, 40, "这是一个非常非常长的标签文字"));
        assertTrue(has(critic.critique(els, "x"), CritiqueIssue.Category.LABEL_OVERFLOW));
    }

    @Test
    void tinyDetected() {
        var els = List.of(box("a", 0, 0, 20, 10, null));
        assertTrue(has(critic.critique(els, "x"), CritiqueIssue.Category.TINY));
    }

    @Test
    void edgeCrossingDetected() {
        var els = new ArrayList<Map<String, Object>>();
        els.add(box("a", 0, 0, 40, 40, null));
        els.add(box("b", 200, 0, 40, 40, null));
        els.add(box("c", 90, 100, 40, 40, null));
        els.add(box("d", 90, -100, 40, 40, null));
        els.add(arrow("e1", "a", "b"));
        els.add(arrow("e2", "c", "d"));
        assertTrue(has(critic.critique(els, "x"), CritiqueIssue.Category.EDGE_CROSSING));
    }

    @Test
    void crossingSkipsSharedEndpoint() {
        var els = new ArrayList<Map<String, Object>>();
        els.add(box("a", 0, 0, 40, 40, null));
        els.add(box("b", 200, 0, 40, 40, null));
        els.add(box("c", 90, 100, 40, 40, null));
        els.add(arrow("e1", "a", "b"));
        els.add(arrow("e2", "a", "c"));
        assertFalse(has(critic.critique(els, "x"), CritiqueIssue.Category.EDGE_CROSSING));
    }

    @Test
    void emptyInputSafe() {
        assertTrue(critic.critique(null, "x").isEmpty());
        assertTrue(critic.critique(List.of(), "x").isEmpty());
    }
}
