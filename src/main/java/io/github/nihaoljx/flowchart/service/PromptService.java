package io.github.nihaoljx.flowchart.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Prompt 组装服务
 *
 * 职责：读模板文件 + 把用户输入塞进去 → 输出完整的 prompt 字符串
 *
 * 为什么要把 prompt 放在单独文件？
 * 1. prompt 会反复调优，改文件比改代码方便
 * 2. 非技术人员也能打开 txt 文件看内容
 * 3. 文本文件和代码分开，版本管理更干净（改 prompt 不会跟改代码混在一起）
 *
 * 为什么从单个模板改成 Map 缓存？
 * 任务 22 加了思维导图、架构图，每个图表类型一套模板。
 * 模板从"一个 String"变成"一组 String"，缓存也得跟着变：
 * 单个缓存  →  缓存 Map<type, 模板内容>
 */
@Service  // 告诉 Spring：这是个 Bean，需要的时候自动注入
public class PromptService {

    /**
     * 模板路径表：图表类型 → 模板文件路径
     * Map.of：Java 9 引入的静态工厂方法，创建不可变 Map，适合常量表
     */
    private static final Map<String, String> TEMPLATE_PATHS = Map.of(
            "flowchart", "templates/flowchart-prompt.txt",
            "mindmap", "templates/mindmap-prompt.txt",
            "architecture", "templates/architecture-prompt.txt"
    );

    /**
     * JSON Schema 路径表：图表类型 → Schema 文件路径（任务33新增）
     *
     * 为什么 Schema 要独立成文件而不是写死在代码里？
     * 1. Schema 是"数据"不是"代码"——放 resources 里可维护、可复用
     * 2. 改 Schema 不用重编译（改模板也是这个道理）
     * 3. 面试讲"配置与代码分离"时是加分项
     */
    private static final Map<String, String> SCHEMA_PATHS = Map.of(
            "flowchart", "schemas/flowchart-schema.json",
            "mindmap", "schemas/mindmap-schema.json",
            "architecture", "schemas/architecture-schema.json"
    );

    /**
     * 缓存：type → 模板内容
     * 每个模板只读一次，后面复用
     * 线程安全：HashMap 理论上并发会出问题，但这里是个人项目、请求量低，
     *           而且最坏情况只是"重复读一次文件"，无害。
     *           （面试加分点：生产环境应该用 ConcurrentHashMap 或双检锁）
     */
    private final Map<String, String> templateCache = new HashMap<>();

    /** Schema 缓存：type → Schema 内容（任务33新增，同样懒加载） */
    private final Map<String, String> schemaCache = new HashMap<>();

    /** Mermaid 模板缓存（任务37：让 LLM 直接产出 mermaid 文本，不走 PlantUML） */
    private final Map<String, String> mermaidTemplateCache = new HashMap<>();

    /**
     * 加载模板文件到内存（懒加载：第一次用到某类型时才读）
     */
    private String loadTemplate(String type) throws IOException {
        return loadText(TEMPLATE_PATHS.get(type), templateCache);
    }

    /**
     * 加载 JSON Schema 文件到内存（任务33新增）
     *
     * @param type 图表类型：flowchart | mindmap | architecture
     * @return Schema 的 JSON 字符串，直接传给 chatStructured
     */
    public String loadSchema(String type) throws IOException {
        // 类型不存在时兜底成流程图 Schema
        if (!SCHEMA_PATHS.containsKey(type)) {
            type = "flowchart";
        }
        return loadText(SCHEMA_PATHS.get(type), schemaCache);
    }

    /**
     * 通用文件加载：读 resources 下的文本文件，带缓存
     *
     * computeIfAbsent 是 Map 的"取不到就放"方法：
     * - key 存在 → 直接返回缓存值，不执行 lambda
     * - key 不存在 → 执行 lambda 读文件，存进 Map，再返回
     *
     * 注意：lambda 里不能抛受检异常（IOException），
     *       所以把 IOException 包成 RuntimeException 抛出去，
     *       外面 buildPrompt 声明了 throws IOException，再解包即可。
     */
    private String loadText(String path, Map<String, String> cache) throws IOException {
        try {
            return cache.computeIfAbsent(path, p -> {
                try {
                    ClassPathResource resource = new ClassPathResource(p);
                    // Files.readString: Java 11 引入，一行读完整个文件
                    return Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            throw (IOException) e.getCause();
        }
    }

    /**
     * 核心方法：模板 + 用户文本 → 完整 prompt
     *
     * @param userText 用户在网页输入框里的文字
     * @param type     图表类型：flowchart | mindmap | architecture
     * @return 拼接好的完整 prompt，可以直接发给 LLM
     */
    public String buildPrompt(String userText, String type) throws IOException {
        // 类型不存在时，get 会拿到 null → 空指针，这里兜底成流程图模板
        if (!TEMPLATE_PATHS.containsKey(type)) {
            type = "flowchart";
        }
        String template = loadTemplate(type);
        // 把模板里的占位符 {userText} 替换成用户输入
        return template.replace("{userText}", userText);
    }

    /**
     * 任务37：Mermaid 输出专用 prompt 组装
     *
     * 和 buildPrompt 的区别：这里不约束成 {title, nodes, edges} 的图数据，
     * 而是让 LLM 直接产出一段**可被 Mermaid 渲染的图表代码文本**，
     * 放进 JSON 的 `mermaid` 字段里，后端不再调 PlantUML 渲染。
     *
     * @param userText 用户在网页输入框里的文字
     * @param type     图表类型：flowchart / mindmap / architecture（决定 Mermaid 语法示例）
     * @return 拼接好的完整 prompt
     */
    public String buildMermaidPrompt(String userText, String type) throws IOException {
        String template = loadText("templates/mermaid-prompt.txt", mermaidTemplateCache);
        // 模板里有 {userText} 和 {type} 两个占位符
        return template.replace("{userText}", userText).replace("{type}", type);
    }

    /**
     * 任务37：加载 Mermaid 的 JSON Schema（约束 LLM 只输出 { "mermaid": "..." }）
     */
    public String loadMermaidSchema() throws IOException {
        return loadText("schemas/mermaid-schema.json", schemaCache);
    }
}
