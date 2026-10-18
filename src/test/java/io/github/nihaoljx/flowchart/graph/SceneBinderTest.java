package io.github.nihaoljx.flowchart.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;

/**
 * 连线吸附单元测试（v2-33）
 *
 * <p>背景：Excalidraw 里**只有 arrow 支持端点绑定**，`line` 与
 * "只给 points 的箭头"都不会跟随图形移动（2026-09-16 浏览器实测）。
 * 用户看到的现象就是"拖动图形时有的连线不动/像断了"。
 *
 * <p>吸附的职责单一：把"两端都落在图形上"的两点箭头升级成
 * `start.id` / `end.id` 绑定式，让它能跟随。测的是"该动的动、
 * 不该动的一个字都不许改"。
 */
class SceneBinderTest {

    private final SceneBinder binder = new SceneBinder();

    private static final String R1 =
            "{\"id\":\"r1\",\"type\":\"rectangle\","
            + "\"x\":0,\"y\":0,\"width\":160,\"height\":70}";
    private static final String R2 =
            "{\"id\":\"r2\",\"type\":\"rectangle\","
            + "\"x\":400,\"y\":0,\"width\":160,\"height\":70}";

    @Test
    @DisplayName("两端都落在图形上的两点箭头 → 升级为绑定式并丢掉 points")
    void bindsArrowSpanningTwoShapes() {
        String json = "{\"elements\":[" + R1 + "," + R2 + ","
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"x\":160,\"y\":35,"
                + "\"points\":[[0,0],[240,0]]}]}";

        String out = binder.bind(json);

        assertTrue(out.contains("\"id\":\"r1\""),
                "绑定目标 id 应写在 start 上: " + out);
        assertTrue(out.contains("\"id\":\"r2\""),
                "绑定目标 id 应写在 end 上: " + out);
        // 升级后 points / x / y 必须丢掉：三者与绑定并存会让
        // "几何"有两个来源，渲染器按哪个算属于实现细节，不能赌
        assertFalse(out.contains("points"),
                "升级为绑定式后不应残留 points: " + out);
    }

    @Test
    @DisplayName("已经是绑定式的箭头 → 原样返回（返回同一字符串实例）")
    void leavesBoundArrowUntouched() {
        String json = "{\"elements\":[" + R1 + "," + R2 + ","
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"start\":{\"id\":\"r1\"},"
                + "\"end\":{\"id\":\"r2\"}}]}";

        // 没有改动时返回原字符串实例：避免无谓的重排与格式化，
        // 也让调用方能靠"写出的 JSON 与输入字节相同"判断是否被改过
        assertSame(json, binder.bind(json));
    }

    @Test
    @DisplayName("多折线箭头（points > 2）→ 不吸附")
    void keepsMultiPointArrow() {
        // 吸附必须丢掉折点，会把绕线路径拉直 —— 代价比"不跟随"更大
        String json = "{\"elements\":[" + R1 + "," + R2 + ","
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"x\":160,\"y\":35,"
                + "\"points\":[[0,0],[120,-80],[240,0]]}]}";

        assertSame(json, binder.bind(json));
    }

    @Test
    @DisplayName("只有一端落在图形上 → 不吸附")
    void keepsArrowWithDanglingEndpoint() {
        // 只命中一端时若改 x/y，相对坐标的 points 会整体错位；
        // 保留原样至少几何是对的（只是不跟随）
        String json = "{\"elements\":[" + R1 + "," + R2 + ","
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"x\":160,\"y\":35,"
                + "\"points\":[[0,0],[500,0]]}]}";

        assertSame(json, binder.bind(json));
    }

    @Test
    @DisplayName("两端落在同一个图形上 → 不吸附")
    void keepsSelfLoop() {
        String json = "{\"elements\":[" + R1 + ","
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"x\":20,\"y\":20,"
                + "\"points\":[[0,0],[120,40]]}]}";

        assertSame(json, binder.bind(json));
    }

    @Test
    @DisplayName("没有图形元素 → 不吸附")
    void keepsArrowWithoutShapes() {
        String json = "{\"elements\":["
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[100,0]]}]}";

        assertSame(json, binder.bind(json));
    }

    @Test
    @DisplayName("line 不被吸附：Excalidraw 根本不允许 line 绑定")
    void ignoresLineElements() {
        String json = "{\"elements\":[" + R1 + "," + R2 + ","
                + "{\"id\":\"l1\",\"type\":\"line\","
                + "\"x\":160,\"y\":35,"
                + "\"points\":[[0,0],[240,0]]}]}";

        assertSame(json, binder.bind(json));
    }

    @Test
    @DisplayName("坏 JSON → 原样返回，绝不因吸附失败中断生成")
    void toleratesBrokenJson() {
        String broken = "{ this is not json";
        assertSame(broken, binder.bind(broken));
    }

    @Test
    @DisplayName("缺 elements 字段 → 原样返回")
    void toleratesMissingElements() {
        String json = "{\"foo\":1}";
        assertSame(json, binder.bind(json));
    }

    @Test
    @DisplayName("端点贴在框外沿几像素内 → 仍算命中（容差 12px）")
    void snapsWithinTolerance() {
        // LLM 给的端点很少精确落在框边上，实测常压在外沿几像素
        String json = "{\"elements\":[" + R1 + "," + R2 + ","
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"x\":156,\"y\":35,"
                + "\"points\":[[0,0],[248,0]]}]}";

        String out = binder.bind(json);

        assertFalse(out.contains("points"),
                "容差内的端点应被吸附: " + out);
    }

    /**
     * 时序图生命线不是可连线的图形：吸附会把 SequenceLayout 写好的
     * 显式 x/y/points 抹掉（remove 三件套），消息箭头的行位就此丢失。
     *
     * @see io.github.nihaoljx.flowchart.graph.transform.SceneBinder#LIFELINE_ID_PREFIX
     */
    @Test
    @DisplayName("生命线不是连线端点：两点箭头保持显式 x/y/points 原样")
    void keepsArrowAnchoredOnLifelines() {
        // 仿 SequenceLayout 产物：参与者框 + 2px 生命线 + 水平消息箭头
        String life1 = "{\"id\":\"life_a\",\"type\":\"rectangle\","
                + "\"x\":200,\"y\":180,\"width\":2,\"height\":980,"
                + "\"backgroundColor\":\"transparent\","
                + "\"strokeStyle\":\"dashed\"}";
        String life2 = "{\"id\":\"life_b\",\"type\":\"rectangle\","
                + "\"x\":540,\"y\":180,\"width\":2,\"height\":980,"
                + "\"backgroundColor\":\"transparent\","
                + "\"strokeStyle\":\"dashed\"}";
        String json = "{\"elements\":[" + life1 + "," + life2 + ","
                + "{\"id\":\"msg_0\",\"type\":\"arrow\","
                + "\"x\":200,\"y\":300,"
                + "\"points\":[[0,0],[340,0]],"
                + "\"label\":{\"text\":\"输入密码\"}}]}";

        // 原样返回（引用相等）：一个字节都不该被改
        assertSame(json, binder.bind(json));
    }

    /**
     * 正常的参与者框照样是合法端点 —— 生命线豁免不许把整个吸附能力废掉。
     */
    @Test
    @DisplayName("生命线豁免不影响普通矩形的吸附")
    void stillBindsToOrdinaryShapesBesideLifelines() {
        String life1 = "{\"id\":\"life_a\",\"type\":\"rectangle\","
                + "\"x\":200,\"y\":180,\"width\":2,\"height\":980}";
        String json = "{\"elements\":[" + R1 + "," + R2 + "," + life1 + ","
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"x\":160,\"y\":35,"
                + "\"points\":[[0,0],[240,0]]}]}";

        String out = binder.bind(json);

        assertTrue(out.contains("\"id\":\"r1\""),
                "普通矩形仍应被吸附: " + out);
        assertTrue(out.contains("\"id\":\"r2\""),
                "普通矩形仍应被吸附: " + out);
        assertFalse(out.contains("points"),
                "升级为绑定式后不应残留 points: " + out);
        // 绑定目标必须是参与者框，不是生命线：
        // start/end 两处引用的 id 都不该是 life_a
        assertFalse(out.contains("\"start\":{\"id\":\"life_a\"}")
                        || out.contains("\"end\":{\"id\":\"life_a\"}"),
                "生命线不该被选为绑定端点: " + out);
    }
}
