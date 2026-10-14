package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;

/**
 * 全功能用例桥接：把 {@code full_battery.py} 产生的「真实 LLM / 后端输出」
 * （{@code .workbuddy/ab/raw_full/*.json}）逐文件喂给后端真实
 * {@link GraphValidator}，输出「过 / 拒 + 命中哪条护栏」的报告。
 *
 * <p>与 {@link AdvValidatorCheck}、{@link EdgeValidatorCheck} 的区别：那两个
 * 分别钉在已经下线的数据集（raw_adv / raw_edge）上，本件是唯一还在用的入口。
 *
 * <p>**报告件，不做断言**：raw_full 目录缺失（新克隆 / 未跑电池）时静默跳过，
 * 保持 {@code mvn test} 绿。
 *
 * <p>运行： {@code mvn -o test -Dtest=CaseValidatorCheck}
 * <br>产物： {@code .workbuddy/ab/full_validator_report.json} + 控制台汇总表。
 *
 * <p>2026-09-26 新增两个可选参数，用来把"某一轮新数据"和"历史数据"分开扫：
 * <ul>
 *   <li>{@code -Dvalidator.rawDir=<sub>} —— 扫 {@code .workbuddy/ab/<sub>/}；</li>
 *   <li>{@code -Dvalidator.out=<file>} —— 报告写到哪个文件名。</li>
 * </ul>
 * 例：{@code mvn -o test -Dtest=CaseValidatorCheck
 * -Dvalidator.rawDir=raw_n10 -Dvalidator.out=n10_validator_report.json}
 * 两者都不给时行为与以前完全一致（扫 raw_full、写
 * full_validator_report.json）。
 */
public class CaseValidatorCheck {

    private static final String AB_DIR =
            "F:/ProgramData/IDEA/flowchart/.workbuddy/ab";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GraphValidator VALIDATOR = new GraphValidator();

    @Test
    public void validateFullBatteryOutput() throws Exception {
        String sub = System.getProperty("validator.rawDir", "raw_full");
        String outName = System.getProperty("validator.out",
                "full_validator_report.json");
        Path rawDir = Paths.get(AB_DIR, sub);
        if (!Files.isDirectory(rawDir)) {
            System.out.println(
                    "[CaseValidatorCheck] raw_full 目录不存在，跳过（先跑 full_battery.py）");
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(rawDir)
                .filter(p -> p.toString().endsWith(".json"))) {
            files = s.sorted().toList();
        }

        Map<String, Object> report = new LinkedHashMap<>();
        int pass = 0;
        int fail = 0;

        System.out.println("\n=== 全功能用例真实输出 · 后端 GraphValidator 判定 ===");
        System.out.printf("%-24s %-8s %-8s %s%n",
                "case", "elements", "verdict", "issue/拒因");
        for (Path f : files) {
            String cid = f.getFileName().toString().replace(".json", "");
            String json = Files.readString(f);
            List<ValidationIssue> issues = VALIDATOR.validate(json);
            int elements = countElements(json);
            boolean ok = issues.isEmpty();
            if (ok) {
                pass++;
            } else {
                fail++;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("elements", elements);
            entry.put("pass", ok);
            entry.put("issue_count", issues.size());
            List<Map<String, String>> details = new ArrayList<>();
            for (ValidationIssue it : issues) {
                Map<String, String> d = new LinkedHashMap<>();
                d.put("field", it.field());
                d.put("reason", it.reason());
                d.put("hint", it.hint());
                details.add(d);
            }
            entry.put("issues", details);
            report.put(cid, entry);

            StringBuilder reasons = new StringBuilder();
            for (ValidationIssue it : issues) {
                if (reasons.length() > 0) {
                    reasons.append(" | ");
                }
                reasons.append(it.reason());
            }
            System.out.printf("%-24s %-8d %-8s %s%n",
                    cid, elements, ok ? "PASS" : "FAIL",
                    reasons.length() == 0 ? "(零问题)" : reasons);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generated_at",
                java.time.LocalDateTime.now().toString());
        root.put("total", pass + fail);
        root.put("pass", pass);
        root.put("fail", fail);
        root.put("cases", report);
        Path out = Paths.get(AB_DIR, outName);
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(out.toFile(), root);

        System.out.printf("%n汇总  PASS %d / FAIL %d（共 %d）%n",
                pass, fail, pass + fail);
        System.out.println("报告 -> " + out);
    }

    /** 统计 elements 数组长度；解析失败返回 -1。 */
    private int countElements(String json) {
        try {
            var node = MAPPER.readTree(json);
            var els = node.get("elements");
            return els == null || !els.isArray() ? 0 : els.size();
        } catch (Exception e) {
            return -1;
        }
    }
}
