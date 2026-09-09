package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 真实工具：读本地文件内容，作为出图素材（LLM 长出"读文件"的手脚）。
 * 安全：限定在程序运行目录内，禁止越权读系统文件。
 */
@Component
public class ReadFileTool implements Tool {

    // 只允许读程序运行目录（项目根）下的文件，防越权
    private final String allowedDir = System.getProperty("user.dir");

    @Override public String name() { return "read_file"; }

    @Override public String description() {
        return "读取项目目录内的文本文件（.txt/.md/.json/.plantuml）内容，作为画图参考素材。当用户提到某个具体文件名时使用。";
    }

    @Override public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"相对项目根目录的文件路径，如 docs/login.md\"}},\"required\":[\"path\"]}";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String p = String.valueOf(args.getOrDefault("path", ""));
        if (p.isBlank()) return "ERROR: 缺少 path 参数";

        Path root = Path.of(allowedDir).normalize();
        Path path = root.resolve(p).normalize();
        if (!path.startsWith(root)) return "ERROR: 路径越权，拒绝读取";
        if (!Files.exists(path) || !Files.isRegularFile(path)) return "ERROR: 文件不存在";

        String content = Files.readString(path);
        if (content.length() > 8000) content = content.substring(0, 8000) + "\n...[已截断 8000 字]";
        return "【文件 " + p + " 内容】\n" + content;
    }
}
