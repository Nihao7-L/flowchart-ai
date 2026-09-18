package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Throwaway replay harness for VALIDATION failures (NOT a regression test,
 * hence no "Test" suffix).
 *
 * <p>Why it exists: when GenerationService retries, it only pushes the ISSUE
 * COUNT over SSE ("第 1 轮校验失败: 1 个问题") and logs the same count. The
 * field/reason/hint of every issue stays in memory. 2026-09-18 the prompt
 * battery burned two 400s budgets on re-tries and there was no way to see WHY.
 *
 * <p>This replays one dumped LLM payload through the production chain
 * (SceneBinder -> SceneDeduper -> ScenePlanner -> GraphValidator) and prints
 * every issue, so a retry becomes explainable.
 *
 * <p>Run:
 * {@code mvn -q -Dsurefire.useFile=false -Dtest=ReplayValidation
 *  -Dpayload=frontend/node_modules/.cache/fail-a.json test}
 *
 * <p>The payload must be the extracted scene object ({@code {"elements":[...]}}),
 * i.e. the fenced block inside the LLM response, not the raw envelope.
 */
class ReplayValidation {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void replay() throws Exception {
        String prop = System.getProperty(
                "payload", "frontend/node_modules/.cache/llm-case02.json");
        Path payload = Path.of(prop);
        Assumptions.assumeTrue(Files.exists(payload),
                "missing " + payload + " (dump it from the backend log first)");

        String json = Files.readString(payload);
        json = new SceneBinder().bind(json);
        json = new SceneDeduper().dedup(json, List.of());
        json = new ScenePlanner().arrange(
                json, new ElkLayoutEngine(), Set.of());

        List<ValidationIssue> issues =
                new GraphValidator().validate(json);

        int frames = 0;
        int shapes = 0;
        for (var e : MAPPER.readTree(json).get("elements")) {
            String type = e.path("type").asText();
            if ("frame".equals(type)) {
                frames++;
            } else if ("rectangle".equals(type) || "ellipse".equals(type)
                    || "diamond".equals(type)) {
                shapes++;
            }
        }
        System.out.println("\n=== replay validation ===");
        System.out.println("payload : " + payload);
        System.out.println("shapes  : " + shapes + " , frames: " + frames);
        System.out.println("issues  : " + issues.size());
        for (ValidationIssue i : issues) {
            System.out.println("  [" + i.field() + "] " + i.reason());
            if (i.hint() != null && !i.hint().isBlank()) {
                System.out.println("      hint: " + i.hint());
            }
        }
    }
}
