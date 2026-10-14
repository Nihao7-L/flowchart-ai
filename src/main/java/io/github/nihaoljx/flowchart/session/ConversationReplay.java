package io.github.nihaoljx.flowchart.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nihaoljx.flowchart.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 每轮回放落盘
 *
 * 解决的问题：改完提示词后，没人知道"当时到底发出去的是什么"，
 * 于是新本效果差在哪只能靠猜。把每轮真实发出的消息存成 json，
 * 回归时就能逐版本对照。
 *
 * 落盘目录默认 `target/context-replay/`：target 天然被
 * .gitignore 排除，`mvn clean` 会自动清掉 —— 属于运行产物，不入库。
 *
 * 落盘内容刻意只存"结构与体量"（role / 长度 / token 估算），不存正文。
 * 原因：契约 + 规则 + 用户需求整份落盘会让目录迅速膨胀（单轮数十 KB），
 * 而回放要回答的问题是"这轮发了哪些块、各多大、用的哪版提示词"，
 * 正文可由同版本提示词 + 同一入参复现。将来若需正文级对照，再开一个开关。
 */
@Component
public class ConversationReplay {

    private static final Logger LOG =
            LoggerFactory.getLogger(ConversationReplay.class);

    /** 实测系数：该模型约 1 token / 汉字、非汉字约 0.42 token / 字符 */
    private static final double NON_CJK_TOKEN_RATIO = 0.42;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 关掉即完全不落盘（排障时可临时关） */
    @Value("${context.replay.enabled:true}")
    private boolean enabled;

    /** 落盘目录 */
    @Value("${context.replay.dir:target/context-replay}")
    private String replayDir;

    /**
     * 把本轮消息落盘。
     *
 * 刻意**不抛异常**：回放只是观测手段，绝不能因为它写不进去
     * 就阻断一次正常的生成（"辅助功能不许打穿主流程"）。
     *
     * @param input    本轮组装入参（提供 sessionId / round / promptVersion）
     * @param messages 实际发出的消息
     */
    public void dump(AssembleInput input, List<Message> messages) {
        if (!enabled) {
            return;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("sessionId", safeId(input.sessionId()));
            root.put("round", input.round());
            root.put("editMode", input.editMode());
            root.put("promptVersion", input.promptVersion());
            root.put("ts", LocalDateTime.now().toString());

            ArrayNode array = root.putArray("messages");
            int totalTokens = 0;
            for (Message message : messages) {
                int tokens = estimateTokens(message.content());
                totalTokens += tokens;
                ObjectNode item = array.addObject();
                item.put("role", message.role());
                item.put("length", message.content().length());
                item.put("tokensEst", tokens);
                item.put("zone", "system".equals(message.role())
                        ? ContextBudget.ZONE_HIGH
                        : ContextBudget.ZONE_USER);
            }
            root.put("totalTokensEst", totalTokens);

            Path dir = Path.of(replayDir);
            Files.createDirectories(dir);
            String name = "context-replay-" + safeId(input.sessionId())
                    + "-r" + input.round() + ".json";
            Files.writeString(
                    dir.resolve(name),
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("上下文回放落盘失败（已忽略，不影响生成）: {}",
                    e.getMessage());
        }
    }

    /**
     * 估算 token 数。
     *
 * 用**实测系数**而不是"字符数 / 4"那种粗估：项目实测该模型约
     * **1 token / 汉字**、非汉字约 **0.42 token / 字符**，所以分开算再加权。
     *
 * 只用于观测与告警，不参与任何决策 —— 精确计数留给 M13 埋点。
     *
     * @param text 待估算文本
     * @return 估算 token 数（向上取整）
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return (int) Math.ceil(cjk + other * NON_CJK_TOKEN_RATIO);
    }

    /**
     * sessionId 可能为空或含路径非法字符，归一成文件名安全的形式。
     *
     * @param sessionId 会话 ID；可为 null
     * @return 可直接拼进文件名的安全 ID
     */
    private String safeId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "anonymous";
        }
        return sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
