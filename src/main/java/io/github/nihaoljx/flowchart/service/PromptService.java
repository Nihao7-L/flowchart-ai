package io.github.nihaoljx.flowchart.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Prompt 组装服务
 *
 * 职责：读模板文件 + 把用户输入塞进去 → 输出完整的 prompt 字符串
 *
 * 为什么要把 prompt 放在单独文件？
 * 1. prompt 会反复调优，改文件比改代码方便
 * 2. 非技术人员也能打开 txt 文件看内容
 * 3. 文本文件和代码分开，版本管理更干净（改 prompt 不会跟改代码混在一起）
 */
@Service  // 告诉 Spring：这是个 Bean，需要的时候自动注入
public class PromptService {

    /**
     * 系统 prompt 模板的类路径位置
     * ClassPathResource 会去 src/main/resources/ 下找这个文件
     */
    private static final String TEMPLATE_PATH = "templates/flowchart-prompt.txt";

    /** 缓存：模板内容只读一次，后面复用 */
    private String cachedTemplate;

    /**
     * 加载模板文件到内存
     * 用懒加载（第一次调用时才读），因为 Spring 启动时不需要这个文件
     */
    private String loadTemplate() throws IOException {
        if (cachedTemplate == null) {
            ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
            // Files.readString: Java 11 引入，一行读完整个文件
            cachedTemplate = Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8);
        }
        return cachedTemplate;
    }

    /**
     * 核心方法：模板 + 用户文本 → 完整 prompt
     *
     * @param userText 用户在网页输入框里的文字，如 "用户登录 → 验证 → 进入首页"
     * @return 拼接好的完整 prompt，可以直接发给 LLM
     */
    public String buildPrompt(String userText) throws IOException {
        String template = loadTemplate();
        // 把模板里的占位符 {userText} 替换成用户输入
        return template.replace("{userText}", userText);
    }
}
