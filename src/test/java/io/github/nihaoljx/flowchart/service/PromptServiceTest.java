package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.service.PromptService;

/**
 * PromptService 单元测试
 * 不调 LLM，只验证 prompt 拼接是否正确
 */
public class PromptServiceTest {
    public static void main(String[] args) throws Exception {
        PromptService service = new PromptService();

        // 模拟用户输入
        String userInput = "用户输入账号密码 → 系统验证 → 成功进入首页，失败提示错误";

        // 拼接 prompt
        String fullPrompt = service.buildPrompt(userInput);

        System.out.println("===== 拼接后的完整 Prompt =====");
        System.out.println(fullPrompt);

        // 验证：模板里的 {userText} 是否被正确替换
        if (fullPrompt.contains("{userText}")) {
            System.out.println("\n❌ 失败：占位符 {userText} 没有被替换！");
        } else if (fullPrompt.contains(userInput)) {
            System.out.println("\n✅ 成功：用户输入已正确注入模板");
        } else {
            System.out.println("\n⚠️ 警告：结果可能异常，请检查");
        }

        System.out.println("\n总字符数：" + fullPrompt.length());
    }
}
