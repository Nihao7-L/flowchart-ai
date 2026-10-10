package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 契约注入段的**漂移守卫**（v2-43）
 *
 * <p>背景：system 段里的契约原来是"把 {@code scene.schema.json} 整份贴进去"。
 * 实测那样要吃掉 3000+ token（占 system 块六成），括号、重复的
 * {@code "type":"number"}、id 正则全是废token。v2-43 改成**按 schema 现算一张
 * 紧凑字段表**：省 token，且契约加字段时提示词自动跟着长，不会 drift。
 *
 * <p>但"自动派生"反过来带来一个新风险：渲染逻辑一旦写错（漏了某个类型、
 * 某个 enum 值没被带上），模型侧会静默少看一条约束 —— 这比多花 token 严重得多。
 * 所以这里把"契约里有的，提示词里必须出现"固化成断言。
 *
 * <p>顺带把生成的 system 块落到 {@code target/prompt-preview.txt}，
 * 供离线做 token 核算（改完提示词想知道省了多少，去看那个文件）。
 */
class PromptServiceContractTest {

    private final PromptService promptService = new PromptService();

    /** 不写死 tests 工作目录：优先 classpath（target/classes），退回源码目录。 */
    private JsonNode schema() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        java.net.URL url = getClass().getClassLoader()
                .getResource("schemas/scene.schema.json");
        if (url != null) {
            return mapper.readTree(url);
        }
        return mapper.readTree(
                Paths.get("src", "main", "resources", "schemas", "scene.schema.json").toFile());
    }

    /** Jackson 的 fieldNames() 返回 Iterator，这里转成 List 才能 for-each。 */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("契约段必须覆盖 scene.schema.json 里的每一个字段名与枚举值")
    void contractBlockCoversSchema() throws IOException {
        String block = promptService.buildContractBlock();
        JsonNode definitions = schema().get("definitions");

        List<String> missing = new ArrayList<>();
        for (String branch : PromptService.CONTRACT_BRANCHES) {
            JsonNode props = definitions.get(branch).get("properties");
            Iterator<String> names = props.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                // type 就是行首那一行自带的类型名，渲染时刻意跳过
                if (!"type".equals(name) && !block.contains(name)) {
                    missing.add(branch + "." + name);
                }
            }
            collectEnums(props, block, missing, branch + ".");
        }

        assertTrue(missing.isEmpty(),
                "契约段漏了这些 schema 内容（提示词会少一条约束）: " + missing);
    }

    private void collectEnums(JsonNode node, String block, List<String> missing, String where) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode enumNode = node.get("enum");
        if (enumNode != null) {
            for (JsonNode value : enumNode) {
                String text = value.asText();
                if (!text.isEmpty() && !block.contains(text)) {
                    missing.add(where + "enum:" + text);
                }
            }
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            collectEnums(node.get(name), block, missing, where + name + ".");
        }
    }

    @Test
    @DisplayName("system 块必须提到 scene.schema.json，且仍是 KV-cache 稳定前缀")
    void systemBlockStable() throws IOException {
        String first = promptService.buildSystemBlock();
        String second = promptService.buildSystemBlock();

        assertTrue(first.contains("scene.schema.json"),
                "system 段必须标注契约来源（下游断言依赖这个字符串）");
        assertEquals(first, second, "system 段必须可重复构造，否则 KV-cache 必失效");
    }

    @Test
    @DisplayName("把 system 块落盘到 target/prompt-preview.txt 供 token 核算")
    void dumpPreview() throws IOException {
        Path out = Paths.get("target", "prompt-preview.txt");
        Files.createDirectories(out.getParent());
        Files.write(out,
                promptService.buildSystemBlock().getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.exists(out), "预览文件应写出");
    }

    @Test
    @DisplayName("人类注释（L0 治理头）不许进 system 块，但必须留在源文件里")
    void humanCommentStrippedButKeptInFile() throws IOException {
        String block = promptService.buildSystemBlock();

        // 2026-10-07 用户拍板 strip：治理头是给人/CI 的变更史，明写
        // 「不属模型指令」，却一直原样发给模型（实测 839B ≈ md 的 14%，
        // 约 245 token / 轮）。它还会随每次改动膨胀，且违反本类
        // 「元数据不进正文，否则 KV-cache 前缀抖动」那条纪律。
        assertFalse(block.contains("<!--"),
                "md 的 HTML 注释不该付费发给模型");
        assertFalse(block.contains("L0 治理头"),
                "L0 治理头必须被 strip 掉");
        assertFalse(block.contains("不属模型指令"),
                "治理头正文必须被 strip 掉");
        assertTrue(block.startsWith("# System Prompt"),
                "strip 后应以正文标题开头，实际开头: "
                        + block.substring(0, Math.min(40, block.length())));

        // 源文件里必须还在：变更史是必要的，只是不该发给模型
        java.net.URL url = getClass().getClassLoader()
                .getResource("prompts/whiteboard/system-prompt.md");
        String md = url != null
                ? new String(url.openStream().readAllBytes(),
                        StandardCharsets.UTF_8)
                : Files.readString(Paths.get("src", "main", "resources",
                        "prompts", "whiteboard", "system-prompt.md"),
                        StandardCharsets.UTF_8);
        assertTrue(md.contains("L0 治理头"),
                "源文件里的 L0 治理头不能被误删（人和 CI 要读它）");
    }

    @Test
    @DisplayName("数字枚举必须与字符串枚举视觉可分")
    void numericEnumMarked() throws IOException {
        String block = promptService.buildContractBlock();

        // fontFamily 是**数字**枚举，但原来渲染成 fontFamily[1|2|3] ——
        // 与 textAlign[left|center|right] 完全同形。模型照抄就写 "1"（字符串），
        // 校验器 val.isInt() 判否 → 实测"写"类场景（写诗 / 算术 / emoji /
        // 纯符号）首轮 4/4 全挂在"非法 fontFamily"，白烧一轮。
        assertTrue(block.contains("fontFamily 1|2|3(数字)"),
                "数字枚举必须标注成数字，否则模型会照字符串枚举的样子写成 \"1\"");
        assertTrue(block.contains("textAlign[left|center|right]"),
                "字符串枚举的 [] 写法不能被连带改坏");
    }

    @Test
    @DisplayName("字段级提示必须渲染出来，且每个键都是 schema 里真实存在的字段")
    void fieldHintsRenderedAndTraceable() throws IOException {
        String block = promptService.buildContractBlock();

        // 2026-10-07 iso-3d 实测两条：模型写 angle:45（契约是弧度 → 转成 58.4°，
        // 图形全糊），以及只给 fillStyle 不给 backgroundColor（等于没填色，
        // 整图纯黑线）。契约段只渲染「名字 + 类型」，光看名字猜不到这两点。
        assertTrue(block.contains("angle(弧度不是角度)"),
                "angle 的单位必须跟在字段旁边，否则模型按角度写数字");
        assertTrue(block.contains("backgroundColor(要填充就得给，不写等于没填)"),
                "fillStyle 是枚举、会让人以为给了就算填了，这条必须挨着字段写");

        // 漂移守卫：字段改名 / 删除后，提示会静默挂在空气上（渲染时永远命中不了）
        JsonNode definitions = schema().get("definitions");
        java.util.Set<String> realFields = new java.util.HashSet<>();
        java.util.Iterator<String> branches = definitions.fieldNames();
        while (branches.hasNext()) {
            JsonNode props = definitions.get(branches.next()).get("properties");
            if (props == null) {
                continue;
            }
            props.fieldNames().forEachRemaining(realFields::add);
        }
        java.util.Set<String> orphans = new java.util.TreeSet<>(PromptService.FIELD_HINTS.keySet());
        orphans.removeAll(realFields);
        assertTrue(orphans.isEmpty(),
                "FIELD_HINTS 里的字段在 schema 里不存在（字段改名了？），"
                        + "这些提示永远不会被渲染出来: " + orphans);
    }

    @Test
    @DisplayName("契约段不许把校验器禁止的键列为可用键（line 的 start/end）")
    void contractAdvertisesNoIllegalField() throws IOException {
        String block = promptService.buildContractBlock();

        // GroupValidator.validateLine 对 line 上任何 start/end 一律打回，但
        // schema 原本把这两个键声明给了 line → 契约段照抄出来，同一行末尾
        // 却写着"禁止 start/end 绑定"，**一行之内自相矛盾**。实测模型照清单
        // 用了 start/end，首轮报"line 不能用 start/end 绑定"。
        // 这与 group 那次是同一类：schema 撒谎，整段契约跟着撒谎。
        String lineRow = null;
        for (String row : block.split("\n")) {
            if (row.startsWith("- line：")) {
                lineRow = row;
                break;
            }
        }
        assertNotNull(lineRow, "契约段里必须有 line 那一行");
        assertFalse(lineRow.contains("start{\"id\"}"),
                "line 的可用键里不许再出现 start，实际: " + lineRow);
        assertFalse(lineRow.contains("end{\"id\"}"),
                "line 的可用键里不许再出现 end，实际: " + lineRow);
        // 另一半口径：GraphValidator 的 checkOptionalPoints（对 line 是**必填**）
        // 要求 points 至少 2 个点，而 schema 的 line.required 原来只有 id/type/x/y，
        // 契约段于是渲染成「〔必填：id,type,x,y〕」—— 模型据此不写 points，
        // 实测首轮报 `line 必须有 points 路径 x4`。同一份 schema 里
        // freedraw 的 required 就带着 points，line 属于漏写。
        int requiredAt = lineRow.indexOf("〔必填：");
        assertTrue(requiredAt > 0, "line 那行必须有〔必填：…〕后缀");
        String requiredKeys = lineRow.substring(requiredAt);
        assertTrue(requiredKeys.contains("points"),
                "line 的必填键里必须有 points（校验器硬要求），"
                        + "否则模型不写 points、整轮被打回。实际后缀: " + requiredKeys);
    }

    @Test
    @DisplayName("points 的写法必须自解释「首点是 [0,0]」（相对坐标）")
    void pointsConventionSelfExplanatory() throws IOException {
        String block = promptService.buildContractBlock();

        // 2026-10-07 实测：契约原来把 points 渲染成 `[[dx,dy]...]`，看不出
        // **首点必须是 [0,0]**。模型据此把绝对坐标写进 points，同时把 x/y
        // 也设成第一个点 → 落点 = (x+dx, y+dy) ≈ 坐标翻倍：画猫那组
        // 9 条 line 全被画到视野外，而校验器只查"每个点是不是两个数字"、
        // 不查首点，静默通过。把首点写出来，这个约定就不用靠描述去解释了
        // （schema 的 point.description 里其实写着"相对坐标"，但模型填字段
        // 时看的是字段表）。
        assertTrue(block.contains("[[0,0],[dx,dy]...]"),
                "points 的写法必须显式带上首点 [0,0]，实际契约段里没有这一形态");
        assertFalse(block.contains("[[dx,dy]...]"),
                "旧的 [[dx,dy]...] 写法看不出首点约定，不许回退");
    }

    // ---------------------------------------------------------------
    // v7.0.0：静态层由「核心 + 大图扩展」两层合并为单一 md
    // ---------------------------------------------------------------
    @Test
    @DisplayName("单一 system 块体积受控：不超过 10000 字节（约 2900 token）")
    void singleBlockBudget() throws IOException {
        int bytes = promptService.buildSystemBlock()
                .getBytes(StandardCharsets.UTF_8).length;

        // 判据（实测约 3.4 字节/token）：v5.8.0 回退后 system 块 = md 正文 + 契约段，
        // 远小于 10000 字节（约 2000 token）。本闸门是防膨胀护栏，
        // 与 v7.0.0 合并时定的「3000 token 以内」硬线一脉相承；超了就是回归。
        assertTrue(bytes < 10000,
                "system 块超过 10000 字节（约 2900 token），超出约定的 3000 token 上限；"
                        + "当前 " + bytes + " 字节");
    }

    @Test
    @DisplayName("v5.8.0 核心条文在位：两形态契约 / freedraw 默认不开 / 增量全量场景 / relation")
    void v580EssentialRulesKept() throws IOException {
        String block = promptService.buildSystemBlock();

        // 这四条是 v5.8.0 时代的「硬护栏」，回退到该版本后必须原样在 md 里；
        // 一旦被合并或精简误删，出图质量会无声下降（没有断言兜底就发现不了）。
        assertTrue(block.contains("SYSTEM_ERROR"), "静态层缺少两形态契约（正常 elements / 系统错误）");
        assertTrue(block.contains("freedraw"), "静态层缺少 freedraw 默认不开的约束");
        assertTrue(block.contains("全量 elements"), "静态层缺少增量输出全量场景的约束");
        assertTrue(block.contains("relation"), "静态层缺少 relation 关系建模指引");
    }

    // ---------------------------------------------------------------
    // v2-43 收尾：promptVersion 必须与 md 治理头 + CHANGELOG 三者一致
    // ---------------------------------------------------------------

    /** 读 classpath 文本资源，取不到则退回源码目录。 */
    private static String readText(String classpathPath, String... sourcePath) throws IOException {
        java.net.URL url = PromptServiceContractTest.class.getClassLoader()
                .getResource(classpathPath);
        if (url != null) {
            return new String(url.openStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        Path path = Paths.get(sourcePath[0], sourcePath[1], sourcePath[2]);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("promptVersion 必须真的从 md 治理头读出来，且形如 vX.Y.Z")
    void promptVersionComesFromGovernanceHeader() throws IOException {
        String version = promptService.promptVersion();

        assertNotEquals("unknown", version,
                "从 system-prompt.md 治理头里没读到版本号 —— 检查首行是否还留着"
                        + "「<!-- L0 治理头：… 版本 vX.Y.Z … -->」");
        assertTrue(version.matches("v\\d+(\\.\\d+)*"),
                "版本号格式应为 vX.Y.Z，实际读到：" + version);

        // 关键：必须等于治理头里写的那个值（不是某个"看起来对"的常量）。
        String md = readText("prompts/whiteboard/system-prompt.md",
                "src", "main", "resources");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("版本\\s*(v\\d+(?:\\.\\d+)*)")
                .matcher(md);
        assertTrue(m.find(), "system-prompt.md 里找不到「版本 vX.Y.Z」字样");
        assertEquals(m.group(1), version,
                "promptVersion() 的返回值与 md 治理头不一致 —— 派生逻辑坏了");
    }

    @Test
    @DisplayName("CHANGELOG 必须记到 md 当前版本那一版（不许漏记）")
    void changelogCoversCurrentPromptVersion() throws IOException {
        String version = promptService.promptVersion();
        if ("unknown".equals(version)) {
            return; // 上一条测试已经报出治理头缺失，这里不重复失败
        }
        // 去掉前缀 v，比对 CHANGELOG 里的条目标题（如「## 10.2.0 (2026-10-10)」）
        String bare = version.startsWith("v") ? version.substring(1) : version;

        String changelog = readText("prompts/whiteboard/CHANGELOG.md",
                "src", "main", "resources");

        assertTrue(changelog.contains("## " + bare + " "),
                "CHANGELOG.md 里没有「## " + bare + "」这条记录，但 md 治理头已是 "
                        + version + " —— 改 md 必须同时补 CHANGELOG"
                        + "（历史上就漏记过 v9.x / v10.x 一整段，"
                        + "导致 PROMPT_VERSION 一直停在 v8.0.1 而无人发现）");
    }

    @Test
    @DisplayName("CHANGELOG 最新条目必须就是 md 当前版本（不许落后）")
    void changelogLatestEntryIsCurrentVersion() throws IOException {
        String version = promptService.promptVersion();
        if ("unknown".equals(version)) {
            return;
        }
        String bare = version.startsWith("v") ? version.substring(1) : version;

        String changelog = readText("prompts/whiteboard/CHANGELOG.md",
                "src", "main", "resources");

        // 取版本号最大的那条条目标题（CHANGELOG 倒序书写，第一条即最新）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^##\\s+(\\d+\\.\\d+\\.\\d+)\\s")
                .matcher(changelog);
        assertTrue(m.find(), "CHANGELOG.md 里解析不出任何「## X.Y.Z」条目标题");
        assertEquals(bare, m.group(1),
                "CHANGELOG 最新条目是 " + m.group(1) + "，但 md 治理头已是 " + version
                        + " —— 请在 CHANGELOG 顶部补上这一版");
    }
}
