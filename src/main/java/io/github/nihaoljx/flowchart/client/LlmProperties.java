package io.github.nihaoljx.flowchart.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 候选表（外置配置，代码不写死 model id）。
 *
 * <p>yml 前缀 llm，对应 {@code llm.failover-enabled} 与 {@code llm.candidates[...]}。
 * 厂商会更新 / 下线模型 —— 改这张表即可，不用动 Java 代码。
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 是否启用多候选故障转移；false = 走旧的单家配置（llm.base-url 等） */
    private boolean failoverEnabled = false;

    /** 有序候选表（代码内会按 tier 再排一次） */
    private List<Candidate> candidates = new ArrayList<>();

    public boolean isFailoverEnabled() {
        return failoverEnabled;
    }

    public void setFailoverEnabled(boolean failoverEnabled) {
        this.failoverEnabled = failoverEnabled;
    }

    public List<Candidate> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<Candidate> candidates) {
        this.candidates = candidates;
    }

    /** 一个模型候选：tier 越小越优先；minRound 控制"按难度升档"。 */
    public static class Candidate {

        /** 逻辑名（日志/观测用），随便起，别和真实 model id 混 */
        private String name = "candidate";
        /** OpenAI 兼容的完整 chat/completions 地址 */
        private String baseUrl;
        private String apiKey;
        /** 真实 model id */
        private String model;
        /** 质量档：越小越好（1 = 最强，优先尝试） */
        private int tier = 5;
        /** 从第几轮才允许使用（默认 1；限量强模型设 2 = 首轮不烧额度） */
        private int minRound = 1;
        /** 是否支持视觉（多模态） */
        private boolean vision = false;
        /** 是否"大输出"候选（发生截断时优先换到它） */
        private boolean largeOutput = false;
        /** 该候选单次调用上限（秒）—— 流式下是"兜底总时限"，不是判死主依据 */
        private int timeoutSeconds = 120;
        /**
         * 是否用流式传输（默认 true）。
         *
         * <p>流式才能拿"多久没有新内容"当判据，做到"模型还在吐字就一直等"；
         * 非流式只有总时限一把尺子，大图会被误杀。设为 false 只在
         * 该厂商不支持 stream 时用。
         */
        private boolean stream = true;
        /**
         * 空闲判死阈值（秒）：连续这么久没有新内容就认为模型卡死了。
         *
         * <p>这是流式模式真正的主判据。推理模型"想"的阶段可能静止较久，
         * 别定太紧（默认 120s）。
         */
        private int idleTimeoutSeconds = 120;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTier() {
            return tier;
        }

        public void setTier(int tier) {
            this.tier = tier;
        }

        public int getMinRound() {
            return minRound;
        }

        public void setMinRound(int minRound) {
            this.minRound = minRound;
        }

        public boolean isVision() {
            return vision;
        }

        public void setVision(boolean vision) {
            this.vision = vision;
        }

        public boolean isLargeOutput() {
            return largeOutput;
        }

        public void setLargeOutput(boolean largeOutput) {
            this.largeOutput = largeOutput;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isStream() {
            return stream;
        }

        public void setStream(boolean stream) {
            this.stream = stream;
        }

        public int getIdleTimeoutSeconds() {
            return idleTimeoutSeconds;
        }

        public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
            this.idleTimeoutSeconds = idleTimeoutSeconds;
        }
    }
}
