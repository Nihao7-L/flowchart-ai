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
     * 缓存：type → 模板内容
     * 每个模板只读一次，后面复用
     * 线程安全：HashMap 理论上并发会出问题，但这里是个人项目、请求量低，
     *           而且最坏情况只是"重复读一次文件"，无害。
     *           （面试加分点：生产环境应该用 ConcurrentHashMap 或双检锁）
     */
    private final Map<String, String> templateCache = new HashMap<>();

    /**
     * 加载模板文件到内存（懒加载：第一次用到某类型时才读）
     *
     * computeIfAbsent 是 Map 的"取不到就放"方法：
     * - key 存在 → 直接返回缓存值，不执行 lambda
     * - key 不存在 → 执行 lambda 读文件，存进 Map，再返回
     *
     * 注意：lambda 里不能抛受检异常（IOException），
     *       所以把 IOException 包成 RuntimeException 抛出去，
     *       外面 buildPrompt 声明了 throws IOException，再解包即可。
     */
    private String loadTemplate(String type) throws IOException {
        try {
            return templateCache.computeIfAbsent(type, t -> {
                try {
                    ClassPathResource resource = new ClassPathResource(TEMPLATE_PATHS.get(t));
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
}
