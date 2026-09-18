package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Throwaway replay harness (NOT a regression test, hence no "Test" suffix).
 *
 * <p>Feeds one real LLM payload (dumped from the backend log) through the exact
 * generation chain: SceneBinder -> SceneDeduper -> ScenePlanner(ElkLayoutEngine),
 * then prints frame geometry so we can compare the server's numbers with what
 * the browser ended up showing in the prompt battery.
 *
 * <p>Run: {@code mvn -q -Dsurefire.useFile=false -Dtest=ReplayCase02 test}
 */
class ReplayCase02 {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path PAYLOAD = Path.of(
            "frontend", "node_modules", ".cache", "llm-case02.json");

    /** Post-layout scene, fed to the browser probe to compare rendered geometry */
    private static final Path LAID = Path.of(
            "frontend", "node_modules", ".cache", "case02-laid.json");

    @Test
    void replay() throws Exception {
        // 依赖"电池跑过之后留下的 LLM 原始输出"：干净检出（含 CI）里没有这个
        // 文件，所以这里显式跳过，而不是报错 —— 它是排查工具，不是门禁。
        Assumptions.assumeTrue(Files.exists(PAYLOAD),
                "缺少 " + PAYLOAD + "（先跑一次真实 LLM 电池，或从后端日志导出）");
        String json = Files.readString(PAYLOAD);
        json = new SceneBinder().bind(json);
        json = new SceneDeduper().dedup(json, List.of());
        json = new ScenePlanner().arrange(
                json, new ElkLayoutEngine(), Set.of());
        Files.writeString(LAID, json);

        JsonNode elements = MAPPER.readTree(json).get("elements");
        System.out.println("\n--- frames after layout ---");
        for (JsonNode e : elements) {
            if (!"frame".equals(e.path("type").asText())) {
                continue;
            }
            double x = e.path("x").asDouble();
            double y = e.path("y").asDouble();
            System.out.printf(
                    "%-18s name=%-8s x=%-8.1f y=%-8.1f w=%-8.1f h=%-8.1f"
                            + "  x%%20=%.1f y%%20=%.1f  children=%d%n",
                    e.path("id").asText(), e.path("name").asText(),
                    x, y, e.path("width").asDouble(),
                    e.path("height").asDouble(),
                    x % 20, y % 20, e.path("children").size());
        }

        List<double[]> boxes = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (JsonNode e : elements) {
            String type = e.path("type").asText();
            if (!"rectangle".equals(type) && !"ellipse".equals(type)
                    && !"diamond".equals(type)) {
                continue;
            }
            boxes.add(new double[] {
                e.path("x").asDouble(), e.path("y").asDouble(),
                e.path("width").asDouble(), e.path("height").asDouble(),
            });
            ids.add(type + " " + e.path("width").asText()
                    + "x" + e.path("height").asText());
            sizes.merge(type + " " + e.path("width").asText()
                    + "x" + e.path("height").asText(), 1, Integer::sum);
        }
        int overlaps = 0;
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                if (overlapArea(boxes.get(i), boxes.get(j)) > 1) {
                    overlaps++;
                    if (overlaps <= 5) {
                        System.out.println("  OVERLAP " + ids.get(i)
                                + "  <->  " + ids.get(j));
                    }
                }
            }
        }
        System.out.println("\nshapes = " + boxes.size()
                + ", overlapping pairs = " + overlaps);
        System.out.println("size distribution:");
        sizes.forEach((key, count) -> System.out.println(
                "   " + key + "  x" + count));
    }

    private static double overlapArea(double[] a, double[] b) {
        double w = Math.min(a[0] + a[2], b[0] + b[2])
                - Math.max(a[0], b[0]);
        double h = Math.min(a[1] + a[3], b[1] + b[3])
                - Math.max(a[1], b[1]);
        return w <= 0 || h <= 0 ? 0 : w * h;
    }
}
