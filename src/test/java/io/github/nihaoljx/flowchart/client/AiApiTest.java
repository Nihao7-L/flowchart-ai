package io.github.nihaoljx.flowchart.client;

import io.github.nihaoljx.flowchart.client.AiApiClient;

public class AiApiTest {
    public static void main(String[] args) throws Exception {
        AiApiClient client = new AiApiClient();

        // 测试阶段手动注入属性（后面 Spring 会自动注入）
        var urlField = AiApiClient.class.getDeclaredField("apiUrl");
        urlField.setAccessible(true);
        urlField.set(client, "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent");

        var keyField = AiApiClient.class.getDeclaredField("apiKey");
        keyField.setAccessible(true);
        keyField.set(client, "LLM_API_KEY");  // 或 System.getenv("LLM_API_KEY")

        String result = client.chat("你好，请只用JSON格式回复：{\"greeting\": \"Hello\"}");
        System.out.println("原始返回:");
        System.out.println(result);
        System.out.println("---");
        System.out.println("提取文本:");
//        System.out.println(client.extractText(result));//该方法已弃用，相关功能请使用ParserService.extractText
    }
}
