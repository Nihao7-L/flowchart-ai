package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 场景种子与轻量 JSON 读写的独立工位
 *
 * 收拢四类"碰 JSON 但不做校验判断"的纯转换逻辑：
 * 手绘提升种子场景（buildSeedScene）、IR 版本号盖章
 * （stampSchemaVersion）、空场景判定（isEmptyScene）、
 * 元素 id 读取（idsOf / idsOfJson）与占位场景合成（syntheticScene）。
 *
 * 共同纪律：任何解析失败都**不抛** —— 或返回原样 / 空集 / null，
 * 让调用方走各自的正常分支。JSON 工具层一旦抛异常，
 * 上游的生成主流程就得为每个调用点补兜底，重复且易漏。
 */
public class SceneSeedBuilder {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    SceneSeedBuilder.class);

    /** IR 格式版本号：写入每个下发的场景，便于将来向前兼容（ADR-2） */
    private static final String IR_SCHEMA_VERSION = "2.0";

    /**
     * 兜底合成场景里那个占位框的 id
     *
     * 固定值而非随机：随机 id 会让"每次降级都产生一份新模型"，
     * 排查时没法把日志里的场景对上。用户可以自由删掉它。
     */
    private static final String FALLBACK_ELEMENT_ID =
            "chartflow-fallback-note";

    /** 会话模型的序列化/反序列化 */
    private final ObjectMapper objectMapper;

    public SceneSeedBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把"只有手绘、没有 AI 元素"的会话模型变成可编辑场景（Bug A 配套）
     *
     * 仅当模型 elements 为空且 _userElements 非空时返回种子场景
     * （手绘提升进 elements、旁路清空）；否则返回 null
     * （走正常的编辑 / 新建路径）。
     *
     * 只有契约类型会提升（rectangle / ellipse / diamond / arrow /
     * line / text / frame）；freedraw 笔画无法参与自动布局，跳过。
     */
    public String buildSeedScene(String modelJson) {
        try {
            JsonNode root = objectMapper.readTree(modelJson);
            JsonNode elements = root.get("elements");
            boolean elementsEmpty = elements == null
                    || !elements.isArray()
                    || elements.isEmpty();
            JsonNode userEls = root.get(
                    SessionStore.USER_ELEMENTS_KEY);
            if (!elementsEmpty
                    || userEls == null
                    || !userEls.isArray()
                    || userEls.isEmpty()) {
                return null;
            }

            List<Object> seed = new ArrayList<>();
            for (JsonNode ue : userEls) {
                String type = ue.path("type").asText(null);
                if (type == null) continue;
                if (!Set.of("rectangle", "ellipse", "diamond",
                        "arrow", "line", "text", "frame")
                        .contains(type)) {
                    continue;
                }
                ObjectNode el =
                        objectMapper.createObjectNode();
                el.put("id", ue.path("id").asText(
                        "ue" + seed.size()));
                el.put("type", type);
                putIfFinite(el, ue, "x");
                putIfFinite(el, ue, "y");
                if (!type.equals("arrow")
                        && !type.equals("line")
                        && !type.equals("text")) {
                    putIfFinite(el, ue, "width");
                    putIfFinite(el, ue, "height");
                }
                if (ue.has("text")
                        && !ue.get("text").asText().isEmpty()) {
                    if (type.equals("text")) {
                        el.put("text",
                                ue.get("text").asText());
                    } else {
                        el.putObject("label")
                                .put("text",
                                        ue.get("text").asText());
                    }
                }
                if (type.equals("arrow")
                        || type.equals("line")) {
                    String s = ue.path("startId").asText(null);
                    String e = ue.path("endId").asText(null);
                    if (s != null && e != null) {
                        el.putObject("start")
                                .put("id", s);
                        el.putObject("end")
                                .put("id", e);
                    } else if (ue.has("points")) {
                        el.set("points", ue.get("points"));
                    }
                }
                seed.add(el);
            }
            if (seed.isEmpty()) return null;

            ObjectNode out =
                    objectMapper.createObjectNode();
            out.set("elements",
                    objectMapper.valueToTree(seed));
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            LOG.warn("take-over seed scene build failed, "
                    + "fall back to normal edit: {}",
                    e.getMessage());
            return null;
        }
    }

    /** 只写有限数值字段（NaN / Infinity 一律不写） */
    private void putIfFinite(
            ObjectNode el, JsonNode src, String key) {
        JsonNode v = src.get(key);
        if (v != null && v.isNumber()) {
            el.put(key, v.doubleValue());
        }
    }

    /**
     * 给场景盖 IR 版本号（幂等：已有则不覆盖）。
     *
     * 后端是 IR 唯一真相源（ADR-2），每个下发的场景都带版本号，
     * 将来 schema 演进时旧实例可据此识别、决定能否直接兼容。
     * 解析失败一律原样返回 —— 版本号只是保险，绝不能因它阻断生成。
     */
    public String stampSchemaVersion(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isObject() && !root.has("schemaVersion")) {
                ((ObjectNode) root).put(
                        "schemaVersion", IR_SCHEMA_VERSION);
                return root.toString();
            }
            return json;
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 判一份场景是不是"空场景"（一个元素都没有）
     *
     * 用于兜住 GraphValidator 漏报空数组的情形（校验器一笔没挑、
     * 0 元素却被当"成功生成"落库下发）。
     *
     * 解析失败一律不算空 —— 坏 JSON 由 GraphValidator 去报更
     * 准确的 issue；这里闷声判成"空场景"反而会把坏 JSON 一路放行到落库。
     *
     * @param json 场景 JSON 文本；可为 null
     * @return 元素数组缺失 / 为空时返回 true
     */
    public boolean isEmptyScene(String json) {
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode elements = root.get("elements");
            return elements == null
                    || !elements.isArray()
                    || elements.isEmpty();
        } catch (Exception e) {
            LOG.warn("空场景判定跳过（解析失败）: {}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * 合成一个只含单个占位框的合法场景
     *
     * 刻意用**带 label 的矩形**而不是裸 text 元素：契约里
     * text 元素不允许带 width / height，而裸 text 走渲染层会被
     * 补成 0×0。带 label 的矩形两头都满足 —— 尺寸是我们给的有限值，
     * 框里有字，用户能一眼看清"当前没有图"，也能直接框选删掉它。
     *
     * @param text 占位框里的说明文字
     * @return 场景 JSON；objectMapper 异常时返回 null
     */
    public String syntheticScene(String text) {
        try {
            ObjectNode el = objectMapper.createObjectNode();
            el.put("id", FALLBACK_ELEMENT_ID);
            el.put("type", "rectangle");
            el.put("x", 120);
            el.put("y", 120);
            el.put("width", 360);
            el.put("height", 60);
            el.set("label",
                    objectMapper.createObjectNode()
                            .put("text", text));
            ObjectNode root = objectMapper.createObjectNode();
            root.set("elements",
                    objectMapper.createArrayNode()
                            .add(el));
            root.put("schemaVersion", IR_SCHEMA_VERSION);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            // 兜底的兜底：走到这里说明 objectMapper 都出问题了，
            // 交回 null 让调用方退回 error 语义
            LOG.warn("兜底场景合成失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 安全地读一份场景 JSON 里的 id 全集
     *
     * 解析失败一律返回空集 —— 空集等于"全量重排"，这比抛异常好：
     * 布局是锦上添花，不能因为读不到旧模型就阻断整次生成。
     *
     * @param json 场景 JSON 文本；可为 null
     * @return id 集合；任何异常都返回空集
     */
    public Set<String> idsOfJson(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return idsOf(objectMapper.readTree(json));
        } catch (JsonProcessingException e) {
            LOG.warn("读取既有 id 失败，布局按新建处理: {}",
                    e.getMessage());
            return Set.of();
        }
    }

    /** 取一份场景树里所有元素的 id */
    public Set<String> idsOf(JsonNode root) {
        Set<String> result = new HashSet<>();
        JsonNode elements = root.get("elements");
        if (elements == null
                || !elements.isArray()) {
            return result;
        }
        for (JsonNode elem : elements) {
            JsonNode id = elem.get("id");
            if (id != null && id.isTextual()) {
                result.add(id.asText());
            }
        }
        return result;
    }
}
