package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实工具：联网搜索（百度千帆 AI Search，国内直连 / 合规 / 免费 1500 次每月）。
 * 复用 llm.webSearchEndpoint + llm.webSearchKey 两个配置键：
 *   - webSearchEndpoint 默认即百度地址
 *   - webSearchKey 填百度千帆 API Key（Bearer，从 console.bce.baidu.com/qianfan/ais/console/apiKey 获取）
 * 不配 key 时 execute 直接返回提示，不阻断主流程（降级）。
 *
 * 请求格式与 SerpAPI 完全不同（POST + messages 体 + Bearer 头），故单独成类——
 * 这正是"可插拔 Tool"设计：换搜索厂商 = 新建一个 Tool 类（加 @Component 即可），不碰核心代码。
 */
@Component
public class BaiduQianfanSearchTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 8;

    @Value("${llm.webSearchKey:}") private String apiKey;
    @Value("${llm.webSearchEndpoint:https://qianfan.baidubce.com/v2/ai_search/web_search}") private String endpoint;

    /** 最近一次搜索的结构化结果（execute 时填充，structuredResults 读取；单例 bean 串行调用，无需加锁） */
    private volatile List<Map<String, Object>> lastResults = List.of();

    @Override public String name() { return "web_search"; }

    @Override public String description() {
        return "当画图需要最新资料或模型训练数据未覆盖的外部知识时，联网搜索（百度千帆 AI Search）返回网页标题/链接/摘要，避免瞎编。中文场景首选。";
    }

    @Override public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"搜索关键词\"}},\"required\":[\"query\"]}";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        if (apiKey.isBlank()) return "ERROR: 未配置 llm.webSearchKey（百度千帆 API Key），跳过联网搜索";
        if (endpoint.isBlank()) return "ERROR: 未配置 llm.webSearchEndpoint，跳过联网搜索";
        String q = String.valueOf(args.getOrDefault("query", "")).trim();
        if (q.isBlank()) return "ERROR: 搜索词为空";

        // 百度千帆 AI Search 请求体：messages 对话式 + baidu_search_v2 数据源
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"" + escape(q) + "\"}],"
                + "\"search_source\":\"baidu_search_v2\","
                + "\"resource_type_filter\":[{\"type\":\"web\",\"top_k\":10}]}";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(500, resp.body().length()));
            return "ERROR: 百度 AI Search 返回 " + resp.statusCode() + "：" + snippet;
        }
        return format(resp.body(), q);
    }

    /** 从 references 数组抽标题/链接/摘要；没有 references 则退化为原文截断 */
    private String format(String json, String q) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode refs = root.get("references");

        // 同时解析成结构化列表（供前端"搜索结果面板"展示，任务39 UI 改版）
        List<Map<String, Object>> items = new ArrayList<>();
        if (refs != null && refs.isArray()) {
            int idx = 0;
            for (JsonNode r : refs) {
                if (idx >= MAX_RESULTS) break;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", str(r, "title"));
                m.put("url", str(r, "url"));
                String summary = str(r, "summary");
                if (summary.isBlank()) summary = str(r, "content");
                m.put("snippet", summary.length() > 160 ? summary.substring(0, 160) + "…" : summary);
                m.put("site", siteOf(str(r, "url")));
                String date = str(r, "date");
                if (!date.isBlank()) m.put("date", date);
                idx++;
                items.add(m);
            }
        }
        this.lastResults = items;

        StringBuilder sb = new StringBuilder("【搜索 \"").append(q).append("\" 结果】\n");
        if (refs != null && refs.isArray() && refs.size() > 0) {
            int n = 0;
            for (JsonNode r : refs) {
                if (n >= MAX_RESULTS) break;
                n++;
                String title = str(r, "title");
                String url = str(r, "url");
                String summary = str(r, "summary");
                if (summary.isBlank()) summary = str(r, "content");
                sb.append(n).append(". ").append(title).append("\n   链接: ").append(url)
                  .append("\n   摘要: ").append(summary).append("\n");
            }
            return sb.toString();
        }
        return sb.append(json.substring(0, Math.min(4000, json.length()))).toString();
    }

    /** 从 URL 提取站点名（如 https://www.zhihu.com/xxx → zhihu.com），给前端当来源标签 */
    private static String siteOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return "web";
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "web";
        }
    }

    /** 任务39 UI 改版：把最近一次搜索的结构化结果交给 ToolExecutor 推给前端 */
    @Override
    public List<Map<String, Object>> structuredResults(Map<String, Object> args, String result) {
        return lastResults;
    }

    private static String str(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v != null && !v.isNull() ? v.asText() : "";
    }

    /** 防止搜索词里的引号/反斜杠破坏 JSON 字符串 */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
