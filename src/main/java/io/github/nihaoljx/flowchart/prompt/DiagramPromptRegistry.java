package io.github.nihaoljx.flowchart.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 图型提示词模板注册表：目录扫描 + 确定性路由 + 热加载
 *
 * <p><b>它解决什么</b>：不同图型需要不同提示词。以前所有图的规则都堆在内核 md
 * 里，字节闸门只剩 13B，加一种图的规则就得从通用规则里挤。现在一种图 = 一个 md
 * 文件，丢进目录就注册，加图型零改码。
 *
 * <p><b>三层来源，后者覆盖前者</b>（抄 Spring AI Lab 的优先级思路）：
 * <ol>
 *   <li>classpath 内置 {@code prompts/whiteboard/diagrams/*.md} —— 打进 jar，保底可用；
 *   <li>外部目录 {@code chartflow.prompts.external-dir} —— 同名 {@code diagramType} 覆盖内置；
 *   <li>运行时改动 —— 监听外部目录，改完即时生效。
 * </ol>
 * 外部文件删掉 ⇒ 重新扫描时自动回退到 classpath 内置版本（不需要"撤销"操作）。
 *
 * <p><b>路由是确定性的，不是让模型判断</b>：按 {@code keywords} 在用户原话里做
 * 包含匹配，命中多个取**最长关键词**那个（"流程图"比"流程"更具体）。
 * 不加 LLM 分类那一跳 —— 那要多付一次调用，而我们的 SLA 是百秒级。
 *
 * <p><b>失败保旧</b>：重编译时某个文件解析失败，只跳过它、保留它上一次的成功版本
 * （没有旧版就不注册），并打 WARN。改提示词改坏了不会把出图链路搞挂 ——
 * 与产品红线「除后端系统错误外一律不 error」同源。
 */
@Component
public class DiagramPromptRegistry {

    /** classpath 内置模板目录（jar 内保底） */
    private static final String CLASSPATH_PATTERN =
            "classpath*:prompts/whiteboard/diagrams/*.md";

    /**
     * 防抖窗口（毫秒）。编辑器「全部保存」会连发好几条事件，
     * 不合并就会同一份文件重编译三四遍。
     */
    private static final long DEBOUNCE_MS = 250L;

    private static final Logger LOG =
            LoggerFactory.getLogger(DiagramPromptRegistry.class);

    /** frontmatter 分隔行 `key: value` */
    private static final Pattern FRONTMATTER_LINE =
            Pattern.compile("^([A-Za-z][A-Za-z0-9_-]*)\\s*:\\s*(.*)$");

    /** 当前生效的模板表：整体原子替换，读路径永远看到一份完整的表 */
    private final AtomicReference<Map<String, DiagramTemplate>> templates =
            new AtomicReference<>(Map.of());

    /** 外部目录；null = 未配置（只用 classpath 内置） */
    private final Path externalDir;

    private volatile WatchService watcher;
    private volatile boolean closed;

    /** 无参构造（单测 / 非 Spring 场景）：只用 classpath 内置模板。 */
    public DiagramPromptRegistry() {
        this("");
    }

    @Autowired
    public DiagramPromptRegistry(
            @Value("${chartflow.prompts.external-dir:}") String externalDir) {
        this.externalDir = (externalDir == null || externalDir.isBlank())
                ? null
                : Path.of(externalDir.trim());
        reload();
        startWatcher();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 按用户原话挑模板（确定性路由）。
     *
     * @param requestText 用户在输入框里的原始需求（可空）
     * @return 命中的模板；没命中返回 {@code null}（此时只发内核，不额外注入）
     */
    public DiagramTemplate forRequest(String requestText) {
        if (requestText == null || requestText.isBlank()) {
            return null;
        }
        String lower = requestText.toLowerCase(Locale.ROOT);
        DiagramTemplate best = null;
        int bestLength = 0;
        for (DiagramTemplate template : templates.get().values()) {
            for (String keyword : template.keywords()) {
                String kw = keyword.toLowerCase(Locale.ROOT);
                // 长关键词优先：命中「流程图」时不该被「流程」抢走
                if (!kw.isEmpty() && lower.contains(kw) && kw.length() > bestLength) {
                    best = template;
                    bestLength = kw.length();
                }
            }
        }
        return best;
    }

    /** 按图型名取模板；没有返回 {@code null} */
    public DiagramTemplate template(String diagramType) {
        if (diagramType == null) {
            return null;
        }
        return templates.get().get(diagramType.trim());
    }

    /** 已注册的图型名 */
    public List<String> types() {
        return List.copyOf(templates.get().keySet());
    }

    /**
     * 这个图型名是否合法（spec 校验层用）。
     *
     * <p>合法 = 有对应模板。**与布局家族解耦**：图型有 25 种，版面家族只有
     * generic / sequence 两套。以前 spec 只认布局家族名，加图型就会判
     * 「未知图型」把模型打回修正轮 —— 那是「加图型」最大的隐性成本。
     */
    public boolean isKnown(String diagramType) {
        return template(diagramType) != null;
    }

    /**
     * 图型 → 排版用的布局家族名。
     *
     * @param diagramType 图型名；没注册过返回 {@code generic}
     */
    public String layoutOf(String diagramType) {
        DiagramTemplate found = template(diagramType);
        return found == null ? "generic" : found.layout();
    }

    /**
     * 渲染成要注入的段落。
     *
     * <p>段里**显式写出 diagramType 的字面值**：模型提交 spec 时要照抄这个值，
     * 光说"按模板来"它会自己编一个拼法。
     */
    public String render(DiagramTemplate template) {
        return "## 图型模板 · " + template.title()
                + "（diagramType: \"" + template.diagramType() + "\"）\n"
                + template.body();
    }

    // ------------------------------------------------------------------
    // 加载 / 热加载
    // ------------------------------------------------------------------

    /**
     * 全量重建模板表：classpath 内置先铺底，外部目录后覆盖。
     *
     * <p>先建新表、最后整体替换 —— 读路径不会看到"扫了一半"的表。
     * 单个文件坏掉只影响它自己（保旧），不影响其他模板。
     */
    public final void reload() {
        Map<String, DiagramTemplate> old = templates.get();
        Map<String, DiagramTemplate> next = new LinkedHashMap<>();
        int failed = 0;
        for (Resource resource : classpathTemplates()) {
            String name = resource.getFilename();
            try (InputStream in = resource.getInputStream()) {
                DiagramTemplate parsed =
                        parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                next.put(parsed.diagramType(), parsed);
            } catch (Exception e) {
                failed++;
                LOG.warn("图型模板 {} 解析失败，跳过: {}", name, e.getMessage());
            }
        }
        if (externalDir != null) {
            for (Path file : externalTemplates()) {
                try {
                    DiagramTemplate parsed = parse(file.getFileName().toString(),
                            Files.readString(file, StandardCharsets.UTF_8));
                    next.put(parsed.diagramType(), parsed);
                } catch (Exception e) {
                    // 保旧：外部这份改坏了就忽略它。此时该图型要么是 classpath
                    // 内置那份（还在表里），要么沿用上一次加载成功的版本 ——
                    // 两种都是"上一版可用的"，绝不会因为这个坏文件整条链跪掉。
                    failed++;
                    LOG.warn("外部图型模板 {} 解析失败，已忽略并保留上一可用版本: {}",
                            file.getFileName(), e.getMessage());
                }
            }
        }
        // 兜底：本次没扫到、但上次成功过的，沿用旧值（保旧的核心）
        for (Map.Entry<String, DiagramTemplate> entry : old.entrySet()) {
            next.putIfAbsent(entry.getKey(), entry.getValue());
        }
        templates.set(Map.copyOf(next));
        LOG.info("图型模板已加载 {} 个{}: {}", next.size(),
                failed > 0 ? "（" + failed + " 个解析失败，已保旧）" : "", next.keySet());
    }

    private Resource[] classpathTemplates() {
        try {
            return new PathMatchingResourcePatternResolver()
                    .getResources(CLASSPATH_PATTERN);
        } catch (IOException e) {
            LOG.warn("扫描 classpath 图型模板失败: {}", e.getMessage());
            return new Resource[0];
        }
    }

    private List<Path> externalTemplates() {
        if (externalDir == null || !Files.isDirectory(externalDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(externalDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.warn("读取外部提示词目录失败 {}: {}", externalDir, e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析一份模板文件（frontmatter + 正文）。
     *
     * @param fileName 文件名（只用于报错定位）
     * @param raw      文件全文
     * @return 模板
     * @throws IllegalArgumentException frontmatter 缺 diagramType
     */
    static DiagramTemplate parse(String fileName, String raw) {
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        Map<String, String> meta = new LinkedHashMap<>();
        String body = text;
        if (text.startsWith("---")) {
            int firstNewline = text.indexOf('\n');
            int close = firstNewline > 0 ? text.indexOf("\n---", firstNewline) : -1;
            if (close > firstNewline) {
                for (String line : text.substring(firstNewline + 1, close).split("\n")) {
                    var matcher = FRONTMATTER_LINE.matcher(line.strip());
                    if (matcher.matches()) {
                        meta.put(matcher.group(1), matcher.group(2).strip());
                    }
                }
                int bodyStart = text.indexOf('\n', close + 1);
                body = bodyStart > 0 ? text.substring(bodyStart + 1) : "";
            }
        }
        String type = meta.get("diagramType");
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                    fileName + " 的 frontmatter 缺少 diagramType");
        }
        return new DiagramTemplate(
                type.trim(),
                meta.getOrDefault("version", "0.0.0"),
                meta.getOrDefault("title", type.trim()),
                splitKeywords(meta.get("keywords")),
                meta.get("layout"),
                PromptText.stripComments(body));
    }

    private static List<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (String part : raw.split("[,，]")) {
            String kw = part.strip();
            if (!kw.isEmpty()) {
                out.add(kw);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 热加载
    // ------------------------------------------------------------------

    /**
     * 启动文件监听。
     *
     * <p>只监听**外部目录** —— classpath 内置那份在 jar 里，开发时靠 IDE 的
     * resources 复制、生产时靠重启，都没有监听的必要（也监听不了 jar 内）。
     */
    private void startWatcher() {
        if (externalDir == null) {
            return;
        }
        if (!Files.isDirectory(externalDir)) {
            LOG.warn("外部提示词目录不存在，只用 classpath 内置模板: {}", externalDir);
            return;
        }
        Thread thread = new Thread(this::watchLoop, "chartflow-prompt-watch");
        // 守护线程：进程退出时不必显式收尸（提示词目录不是关键资源）
        thread.setDaemon(true);
        thread.start();
    }

    private void watchLoop() {
        try (WatchService service = FileSystems.getDefault().newWatchService()) {
            watcher = service;
            externalDir.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            while (!closed) {
                WatchKey key = service.take();
                List<WatchEvent<?>> events = key.pollEvents();
                key.reset();
                if (events.isEmpty()) {
                    continue;
                }
                Thread.sleep(DEBOUNCE_MS);
                WatchKey extra;
                while ((extra = service.poll()) != null) {
                    extra.pollEvents();
                    extra.reset();
                }
                LOG.info("检测到提示词目录变动，重载图型模板");
                reload();
            }
        } catch (ClosedWatchServiceException e) {
            // close() 主动关掉监听，属正常退出
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.warn("提示词目录监听失败，热加载不可用: {}", e.getMessage());
        }
    }

    /** 停止监听（进程内复用时调用；守护线程不调也不会拖住 JVM 退出）。 */
    public void close() {
        closed = true;
        WatchService service = watcher;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                LOG.warn("关闭提示词监听失败: {}", e.getMessage());
            }
        }
    }
}
