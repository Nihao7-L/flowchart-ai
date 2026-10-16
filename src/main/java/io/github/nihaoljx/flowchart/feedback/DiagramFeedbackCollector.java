package io.github.nihaoljx.flowchart.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 「好的画法」收集器（D4 数据飞轮的地基，Route2 第一步）。
 *
 * <p>把每次生成的 (需求, 场景, 得分, 问题摘要) 追加成 JSONL 记录 ——
 * 这就是文章说的「拒绝采样自蒸馏」的数据底座：先有**带分数的真实样本**，
 * 才谈得上 best-of-N 选优与后续微调。别人能复制代码，复制不了
 * 这里积累的几千轮好画法。
 *
 * <p>写入策略：{@code dir} 为空 = 关闭（不产生任何 I/O，管线零影响）；
 * 配置了目录才落盘。单条追加失败绝不抛出打断生成 —— 记一笔
 * System.err 即可（收集器永远不能比主流程更重要）。
 */
public final class DiagramFeedbackCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ISSUES_IN_RECORD = 8;
    private static final int MAX_SCENE_CHARS = 200_000;

    private final Path dir;

    /** @param dir 反馈目录；null 或空白 = 关闭收集 */
    public DiagramFeedbackCollector(String dir) {
        if (dir == null || dir.isBlank()) {
            this.dir = null;
        } else {
            this.dir = Path.of(dir);
        }
    }

    public boolean enabled() {
        return dir != null;
    }

    /**
     * 追加一条反馈记录。
     *
     * @return 写入的文件路径；关闭或失败返回 null（失败绝不打断生成）
     */
    public Path record(String request, String sceneJson, double score,
                       List<String> issueSummaries) {
        if (!enabled() || sceneJson == null || sceneJson.isBlank()) {
            return null;
        }
        try {
            Files.createDirectories(dir);
            ObjectNode node = MAPPER.createObjectNode();
            node.put("ts", java.time.Instant.now().toString());
            node.put("id", UUID.randomUUID().toString().substring(0, 8));
            node.put("request", request == null ? "" : request);
            node.put("score", Math.round(score * 10000) / 10000.0);
            ArrayNode issues = node.putArray("issues");
            if (issueSummaries != null) {
                issueSummaries.stream()
                        .limit(MAX_ISSUES_IN_RECORD)
                        .forEach(issues::add);
            }
            node.put("scene", trimScene(sceneJson));
            Path file = dir.resolve("feedback-"
                    + java.time.LocalDate.now() + ".jsonl");
            Files.writeString(file, MAPPER.writeValueAsString(node)
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return file;
        } catch (IOException | RuntimeException e) {
            // 收集失败绝不打断生成
            System.err.println("[feedback] 收集失败（忽略）: " + e);
            return null;
        }
    }

    /**
     * best-of-N：从记录文件里读出所有样本，按分数降序取前 n 条场景。
     * 供蒸馏时挑「同一需求下画得最好的那份」。
     */
    public List<ScoredScene> bestOf(int n) throws IOException {
        List<ScoredScene> all = readAll();
        all.sort(Comparator.comparingDouble((ScoredScene s) -> s.score())
                .reversed());
        return all.size() <= n ? all : new ArrayList<>(all.subList(0, n));
    }

    /** 读取目录下全部反馈记录（文件缺失/损坏行跳过） */
    public List<ScoredScene> readAll() throws IOException {
        List<ScoredScene> result = new ArrayList<>();
        if (!enabled() || !Files.isDirectory(dir)) {
            return result;
        }
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString()
                    .endsWith(".jsonl")).toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        ObjectNode node = (ObjectNode) MAPPER.readTree(line);
                        result.add(new ScoredScene(
                                node.path("request").asText(""),
                                node.path("scene").asText(""),
                                node.path("score").asDouble(0)));
                    } catch (RuntimeException corrupted) {
                        // 损坏行跳过，不影响其余样本
                    }
                }
            }
        }
        return result;
    }

    private static String trimScene(String sceneJson) {
        return sceneJson.length() <= MAX_SCENE_CHARS
                ? sceneJson : sceneJson.substring(0, MAX_SCENE_CHARS);
    }

    /** 一条带分数的样本（蒸馏数据集的最小单元） */
    public record ScoredScene(String request, String scene, double score) {
    }
}
