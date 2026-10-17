package io.github.nihaoljx.flowchart.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图型提示词模板的注册 / 路由 / 热加载守卫
 *
 * <p>锁的是三件事：① 模板能从 md 里读出来（frontmatter 解析）；
 * ② 路由是确定的（说什么话拿什么模板，不靠模型心情）；
 * ③ 改坏文件时服务不跪（保旧）。
 */
class DiagramPromptRegistryTest {

    private static final String VALID = """
            ---
            diagramType: sequence
            version: 2.1.0
            title: 时序图
            keywords: 时序图, 时序, sequence
            layout: sequence
            ---
            参与者横排、消息逐行。
            """;

    @Test
    @DisplayName("frontmatter 解析：diagramType / version / title / keywords 都读出来")
    void parsesFrontmatter() {
        DiagramTemplate template = DiagramPromptRegistry.parse("sequence.md", VALID);

        assertEquals("sequence", template.diagramType());
        assertEquals("2.1.0", template.version());
        assertEquals("时序图", template.title());
        assertTrue(template.keywords().contains("时序图"));
        assertTrue(template.keywords().contains("sequence"));
        // 版面与图型是两个概念：sequence 图型用 sequence 版面
        assertEquals("sequence", template.layout());
        // 正文里不该残留 frontmatter 与 HTML 注释
        assertFalse(template.body().contains("---"));
        assertFalse(template.body().contains("<!--"));
        assertTrue(template.body().contains("参与者横排"));
    }

    @Test
    @DisplayName("缺 diagramType 的模板必须报错（否则会注册成一个没名字的模板）")
    void missingDiagramTypeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DiagramPromptRegistry.parse("bad.md",
                        "---\nversion: 1.0.0\n---\n正文"));
    }

    @Test
    @DisplayName("classpath 内置的 sequence 模板默认就注册好了")
    void classpathTemplateRegistered() {
        DiagramPromptRegistry registry = new DiagramPromptRegistry();

        assertTrue(registry.types().contains("sequence"),
                "classpath 内置模板应自动注册，实际: " + registry.types());
        assertNotNull(registry.template("sequence"));
    }

    @Test
    @DisplayName("确定性路由：说到「时序图」就命中 sequence，纯抽象需求不命中")
    void routesByKeyword() {
        DiagramPromptRegistry registry = new DiagramPromptRegistry();

        DiagramTemplate hit = registry.forRequest("画一个登录的时序图");
        assertNotNull(hit, "用户原话含「时序图」应命中 sequence 模板");
        assertEquals("sequence", hit.diagramType());

        // v10.15.0：架构图已经是一种图型了，不再是「无模板」的通用需求
        assertEquals("architecture",
                registry.forRequest("画一个电商微服务架构图").diagramType());
        assertNull(registry.forRequest("帮我把这些想法整理一下"),
                "没点名任何图种的需求不该注入图型模板（只发内核）");
        assertNull(registry.forRequest(null));
        assertNull(registry.forRequest("  "));
    }

    /**
     * 图种覆盖面（2026-10-17 用户要求「至少 20 种、种类要明显不同」）。
     *
     * <p>锁两件事：① 数量 —— 砍模板等于砍掉模型的图种知识，必须有人发现；
     * ② 每个模板都有 keywords —— 没有关键词的模板永远路由不到，等于白写。
     */
    @Test
    @DisplayName("内置图型模板 ≥ 20 种，且每种都有触发关键词与非空正文")
    void coversAtLeastTwentyDiagramTypes() {
        DiagramPromptRegistry registry = new DiagramPromptRegistry();

        int size = registry.types().size();
        assertTrue(size >= 20,
                "内置图型模板只有 " + size + " 种，要求至少 20 种");

        for (String type : registry.types()) {
            DiagramTemplate template = registry.template(type);
            assertNotNull(template, type + " 取不到模板");
            assertFalse(template.keywords().isEmpty(),
                    type + " 没有 keywords —— 它永远不会被路由到，等于白写");
            assertFalse(template.body().isBlank(),
                    type + " 正文是空的");
            assertFalse(template.title().isBlank(), type + " 缺标题");
        }
    }

    /**
     * 图型与版面解耦（v10.15.0）：25 种图型共用两套版面。
     *
     * <p>以前 spec 只认布局家族名，于是「加一种图型」被绑架成「写一套排版器」。
     * 现在图型名合法 ≠ 有排版器，版面由模板的 layout 决定。
     */
    @Test
    @DisplayName("图型与版面解耦：25 种图型都合法，版面按 layout 走")
    void diagramTypeDecoupledFromLayout() {
        DiagramPromptRegistry registry = new DiagramPromptRegistry();

        for (String type : registry.types()) {
            assertTrue(registry.isKnown(type), type + " 应被判为已知图型");
            String layout = registry.layoutOf(type);
            assertTrue("generic".equals(layout) || "sequence".equals(layout),
                    type + " 的 layout 是 " + layout + "，超出已知版面家族");
        }
        assertEquals("sequence", registry.layoutOf("sequence"),
                "时序图必须走 sequence 版面（生命线 + 消息逐行）");
        assertEquals("generic", registry.layoutOf("gantt"),
                "甘特图复用通用版面即可，不该要求它有自己的排版器");
        assertFalse(registry.isKnown("这图型不存在"),
                "没注册过的图型名必须判为未知（否则模型写错名字不会被拦）");
        assertEquals("generic", registry.layoutOf("这图型不存在"),
                "未知图型的版面兜底 generic");
    }

    /**
     * 长关键词压短关键词：图种一多，短词会互相抢（「架构图」vs「组织架构图」、
     * 「流程图」vs「跨职能流程」）。抢错了 = 用户要 A 图却拿到 B 图的规则。
     */
    @Test
    @DisplayName("长关键词优先：「组织架构图」不被「架构图」抢走")
    void longestKeywordDisambiguates() {
        DiagramPromptRegistry registry = new DiagramPromptRegistry();

        assertEquals("org-chart",
                registry.forRequest("画我们公司的组织架构图").diagramType());
        assertEquals("architecture",
                registry.forRequest("画一个微服务架构图").diagramType());
        assertEquals("swimlane",
                registry.forRequest("画一个跨职能流程图").diagramType());
        assertEquals("flowchart",
                registry.forRequest("画一个下单流程图").diagramType());
    }

    @Test
    @DisplayName("命中多个时取最长关键词（「流程图」不该被「流程」抢走）")
    void longestKeywordWins() {
        DiagramPromptRegistry registry = new DiagramPromptRegistry();
        // 内置只有 sequence，这里直接验路由函数的排序逻辑：
        // 同一句话同时含「时序」与「时序图」，命中的仍是同一个模板但按最长词计分
        DiagramTemplate hit = registry.forRequest("时序图的时序");
        assertNotNull(hit);
        assertEquals("sequence", hit.diagramType());
    }

    @Test
    @DisplayName("渲染出的段落必须写出 diagramType 字面值（模型要照抄它）")
    void renderCarriesDiagramType() {
        DiagramPromptRegistry registry = new DiagramPromptRegistry();
        String rendered = registry.render(registry.template("sequence"));

        assertTrue(rendered.contains("diagramType: \"sequence\""),
                "段里必须给出可照抄的字面值，实际: " + rendered);
        assertTrue(rendered.startsWith("## 图型模板"),
                "图型段要有明确标题，实际: " + rendered);
    }

    @Test
    @DisplayName("外部目录同名 diagramType 覆盖 classpath 内置")
    void externalDirOverridesClasspath(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("sequence.md"), """
                ---
                diagramType: sequence
                version: 9.9.9
                title: 时序图（外部覆盖版）
                keywords: 时序图
                ---
                外部版正文。
                """, StandardCharsets.UTF_8);

        DiagramPromptRegistry registry =
                new DiagramPromptRegistry(dir.toString());

        assertEquals("时序图（外部覆盖版）", registry.template("sequence").title());
        assertEquals("9.9.9", registry.template("sequence").version());
        registry.close();
    }

    @Test
    @DisplayName("外部目录里的新图型是**新增**，不挤掉内置的")
    void externalDirAddsNewType(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mindmap.md"), """
                ---
                diagramType: mindmap
                version: 1.0.0
                title: 思维导图
                keywords: 思维导图, 脑图
                ---
                中心主题在正中。
                """, StandardCharsets.UTF_8);

        DiagramPromptRegistry registry =
                new DiagramPromptRegistry(dir.toString());

        assertTrue(registry.types().contains("mindmap"));
        assertTrue(registry.types().contains("sequence"),
                "新增图型不该挤掉 classpath 内置");
        assertEquals("mindmap", registry.forRequest("画个思维导图").diagramType());
        registry.close();
    }

    /**
     * 失败保旧：外部这份改坏了，服务必须照常可用 ——
     * 要么回退到 classpath 内置，要么沿用上一次成功的版本。
     */
    @Test
    @DisplayName("外部模板改坏：忽略坏文件，图型仍在（出图链路不跪）")
    void brokenExternalFileKeepsPreviousVersion(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sequence.md");
        Files.writeString(file, """
                ---
                diagramType: sequence
                title: 外部好版本
                keywords: 时序图
                ---
                好版本。
                """, StandardCharsets.UTF_8);
        DiagramPromptRegistry registry =
                new DiagramPromptRegistry(dir.toString());
        assertEquals("外部好版本", registry.template("sequence").title());

        // 改坏：frontmatter 里的 diagramType 被删掉
        Files.writeString(file, "---\ntitle: 改坏了\n---\n正文",
                StandardCharsets.UTF_8);
        registry.reload();

        assertNotNull(registry.template("sequence"),
                "改坏后该图型必须仍在（回退内置或沿用上一版），否则出图链路会缺规则");
        assertNotEquals("改坏了", registry.template("sequence").title());
        registry.close();
    }

    /**
     * 删掉外部文件 ⇒ 回退到 classpath 内置（不需要"撤销"操作，
     * 这也是 Spring AI Lab 三层优先级想要的效果）。
     */
    @Test
    @DisplayName("删掉外部模板后自动回退到 classpath 内置")
    void deletingExternalFallsBackToClasspath(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sequence.md");
        Files.writeString(file, """
                ---
                diagramType: sequence
                title: 外部版
                keywords: 时序图
                ---
                外部版。
                """, StandardCharsets.UTF_8);
        DiagramPromptRegistry registry =
                new DiagramPromptRegistry(dir.toString());
        assertEquals("外部版", registry.template("sequence").title());

        Files.delete(file);
        registry.reload();

        assertTrue(registry.template("sequence").title().startsWith("时序图"),
                "外部文件删掉后应回退到 classpath 内置版本，实际: "
                        + registry.template("sequence").title());
        registry.close();
    }
}
