package io.github.nihaoljx.flowchart.client;

/**
 * 能力矩阵：每个 Provider 声明自己支持哪种结构化输出（response_format）
 * 这是任务34"主备切换不制造新故障"的核心——切换时按各自能力拼请求体
 */
public enum ProviderCapability {
    JSON_SCHEMA,  // 支持 response_format=json_schema + strict:true（如 Kimi）
    JSON_OBJECT,  // 仅支持 response_format=json_object（如 MiMo，不支持 strict）
    NONE          // 不支持结构化输出，退回普通 chat()
}
