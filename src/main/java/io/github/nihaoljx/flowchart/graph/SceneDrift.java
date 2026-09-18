package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编辑模式「几何保真」校验（v2-37，2026-09-17）
 *
 * <p><b>为什么需要它：</b>{@code PromptService} 已经向 LLM 承诺
 * 「没有要求改动的元素必须原样保留 id / type / 几何（x / y / width / height）」，
 * 但此前**没有任何一层在守这条承诺** —— {@link GraphValidator} 只看单个元素的
 * 字段合法性（不看上一版），{@code GenerationService.detectRewrite} 只看
 * 「整张图的 id 是不是全被换掉」。于是**几何漂移无人管**：
 * LLM 完全可以在保留 id 的前提下把所有坐标重排一遍，用户的图被悄悄摆成
 * 另一个样子，而前端整体替换镜像后就照着重排后的坐标画。
 *
 * <p>这条缺口在 S1 只是"观感问题"，到 v2-36（后端布局）会变成**正确性问题**：
 * 编辑模式一旦走进"全量重排"，用户亲手拖出来的排布会在他说"改个颜色"的
 * 时候被整体抹掉。所以本校验必须先于布局落地。
 *
 * <p><b>判据刻意保守（宁可漏报，不误报）：</b>
 * 用户合法地移动 1~2 个元素（"把 A 挪到右边"）不该被拦 —— 拦下来就是白跑
 * 一轮修正（一轮 30~100 秒）。因此要求**同时**满足"漂移元素数 ≥ 3"与
 * "漂移占比 ≥ 50%"，才判为"整图重排"。
 *
 * <p><b>整体平移不算漂移：</b>"把整张图往下挪一点"是合法请求，它的特征是
 * 所有元素位移向量**一致**（纯平移）。整图重排的特征则是相对排布被改变。
 * 因此位移一致（且尺寸未变）时放行 —— 这一条把主要误报来源直接从判据里
 * 排掉，不必去猜用户那句话的意图。
 *
 * <p>不参与判断的元素：**两端都绑定的箭头**（几何由两端图形推导，前端
 * {@code withArrowGeometry} 现算，LLM 给的 x/y 本来就是无效值）、
 * {@code freedraw}（用户手绘的领域，AI 场景里不该有）。
 */
@Component
public class SceneDrift {

    private static final Logger LOG =
            LoggerFactory.getLogger(SceneDrift.class);

    /** 判定"位置/尺寸变了"的容差（px）：抄写取整不该算漂移 */
    static final double GEOMETRY_TOL = 2.0;

    /** 两版都存在的可比元素少于这个数就不判（样本太小，误报代价 > 收益） */
    static final int MIN_KEPT = 3;

    /** 漂移元素数少于此值 = 定向修改（改一个节点、挪一个框） */
    static final int MIN_DRIFTED = 3;

    /** 漂移占比低于此值同样视为定向修改 */
    static final double DRIFT_RATIO = 0.5;

    /** 有几何、需要比尺寸的类型 */
    private static final List<String> SIZED_TYPES =
            List.of("rectangle", "ellipse", "diamond", "frame");

    /** 有 points 路径、需要比路径的类型 */
    private static final List<String> POINTED_TYPES =
            List.of("arrow", "line", "freedraw");

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    /**
     * 比对「上一版场景」与「本轮 LLM 输出」的几何，识别整图重排。
     *
     * @param previousJson 修改前的场景（会话里的模型 JSON）
     * @param json         本轮 LLM 输出的场景
     * @return 需要 LLM 修正的 issue；判定通过（或数据不足以判断）返回 null
     */
    public ValidationIssue check(
            String previousJson, String json) {
        if (previousJson == null || previousJson.isBlank()
                || json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, JsonNode> before =
                    byId(objectMapper.readTree(previousJson));
            Map<String, JsonNode> after =
                    byId(objectMapper.readTree(json));
            if (before.isEmpty() || after.isEmpty()) {
                return null;
            }

            int kept = 0;
            int drifted = 0;
            boolean anySizeChanged = false;
            List<double[]> moves = new ArrayList<>();

            for (Map.Entry<String, JsonNode> entry
                    : before.entrySet()) {
                JsonNode oldEl = entry.getValue();
                JsonNode newEl = after.get(entry.getKey());
                // 被删掉的元素不参与：删元素是用户可能明确要求的动作
                if (newEl == null) {
                    continue;
                }
                // 几何由别处推导 / 属于用户手绘领域的，不参与
                if (isDerivedGeometry(oldEl)
                        || isDerivedGeometry(newEl)) {
                    continue;
                }
                kept++;

                double dx = number(newEl, "x")
                        - number(oldEl, "x");
                double dy = number(newEl, "y")
                        - number(oldEl, "y");
                boolean moved =
                        Math.abs(dx) > GEOMETRY_TOL
                        || Math.abs(dy) > GEOMETRY_TOL;
                boolean resized =
                        sizeDiffers(oldEl, newEl);
                if (!moved && !resized) {
                    continue;
                }
                drifted++;
                anySizeChanged = anySizeChanged || resized;
                moves.add(new double[]{dx, dy});
            }

            if (kept < MIN_KEPT
                    || drifted < MIN_DRIFTED
                    || drifted < DRIFT_RATIO * kept) {
                return null;
            }
            // 纯平移（位移向量一致且没改尺寸）= 合法请求，放行
            if (!anySizeChanged
                    && isUniformTranslation(moves)) {
                return null;
            }

            return new ValidationIssue(
                    "elements[].x",
                    "原场景 " + kept + " 个元素里有 "
                            + drifted + " 个（"
                            + Math.round(100.0 * drifted / kept)
                            + "%）的坐标/尺寸被改动，"
                            + "看起来是把整张图重新排了一遍",
                    "保留未涉及元素的几何原样照抄"
                            + "（x / y / width / height 一个字都不改），"
                            + "只给新增元素安排位置；"
                            + "只有用户明确要求重新排布时"
                            + "才可以整体改动");
        } catch (Exception e) {
            // 坏 JSON 由 GraphValidator 给出更准确的 field 级 issue，
            // 这里不重复报警，更不能因自己解析失败而阻断生成
            LOG.warn("几何保真检查跳过（解析失败）: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * 几何由别处推导的元素 —— 目前只有「两端都绑定」的箭头。
     *
     * <p>它的 x / y / points 由两端图形的几何算出（前端
     * {@code withArrowGeometry} 每次转换现算），LLM 写什么都不作数，
     * 拿它比"漂移"只会产生假阳性。
     */
    private boolean isDerivedGeometry(JsonNode el) {
        if (!"arrow".equals(text(el, "type"))) {
            return false;
        }
        boolean start = el.path("start").path("id").isTextual();
        boolean end = el.path("end").path("id").isTextual();
        return start && end;
    }

    private boolean sizeDiffers(JsonNode oldEl, JsonNode newEl) {
        String type = text(newEl, "type");
        if (SIZED_TYPES.contains(type)) {
            return Math.abs(number(newEl, "width")
                    - number(oldEl, "width")) > GEOMETRY_TOL
                    || Math.abs(number(newEl, "height")
                    - number(oldEl, "height")) > GEOMETRY_TOL;
        }
        if (POINTED_TYPES.contains(type)) {
            return pointsDiffer(
                    oldEl.get("points"), newEl.get("points"));
        }
        return false;
    }

    /** 两条 points 路径是否不同（长度不同或任一点偏差超容差都算不同） */
    private boolean pointsDiffer(JsonNode a, JsonNode b) {
        if (a == null || !a.isArray()
                || b == null || !b.isArray()) {
            return false;
        }
        if (a.size() != b.size()) {
            return true;
        }
        for (int i = 0; i < a.size(); i++) {
            for (int axis = 0; axis < 2; axis++) {
                double x = a.get(i).path(axis).asDouble();
                double y = b.get(i).path(axis).asDouble();
                if (Math.abs(x - y) > GEOMETRY_TOL) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 所有位移是否一致（纯平移）。
     *
     * <p>用中位数而非平均值：LLM 顺手改一个元素不影响中位数，
     * 却会把平均值带偏。
     */
    private boolean isUniformTranslation(List<double[]> moves) {
        if (moves.isEmpty()) {
            return true;
        }
        double[] xs = new double[moves.size()];
        double[] ys = new double[moves.size()];
        for (int i = 0; i < moves.size(); i++) {
            xs[i] = moves.get(i)[0];
            ys[i] = moves.get(i)[1];
        }
        double mx = median(xs);
        double my = median(ys);
        for (double[] move : moves) {
            if (Math.abs(move[0] - mx) > GEOMETRY_TOL
                    || Math.abs(move[1] - my) > GEOMETRY_TOL) {
                return false;
            }
        }
        return true;
    }

    private double median(double[] values) {
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) / 2;
    }

    /** 收「id → 元素」索引；顺带把"没有 id / 不是对象"的元素滤掉 */
    private Map<String, JsonNode> byId(JsonNode root) {
        Map<String, JsonNode> map = new HashMap<>();
        JsonNode elements = root.get("elements");
        if (elements == null || !elements.isArray()) {
            return map;
        }
        for (JsonNode el : elements) {
            if (!el.isObject()) {
                continue;
            }
            JsonNode id = el.get("id");
            if (id != null && id.isTextual()) {
                map.put(id.asText(), el);
            }
        }
        return map;
    }

    private String text(JsonNode el, String field) {
        JsonNode value = el.get(field);
        return value != null && value.isTextual()
                ? value.asText() : "";
    }

    /** 缺字段按 0 计；非数字同样按 0（这类问题由 GraphValidator 报） */
    private double number(JsonNode el, String field) {
        JsonNode value = el.get(field);
        return value != null && value.isNumber()
                ? value.asDouble() : 0.0;
    }
}
