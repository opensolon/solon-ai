/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.llm.dialect.gemini.model;

import java.util.List;

/**
 * Gemini 生成配置
 * <p>
 * 用于配置 Gemini API 的生成参数，包括输出格式、采样策略、停止条件等。
 * 这些配置会影响模型生成内容的方式和格式。
 * <p>
 * 示例配置：
 * <pre>{@code
 * GenerationConfig config = new GenerationConfig()
 *     .setTemperature(0.7)
 *     .setMaxOutputTokens(1024)
 *     .setTopP(0.9)
 *     .setResponseMimeType("application/json");
 * }</pre>
 *
 * @author cwdhf
 * @since 3.1
 */
public class GenerationConfig {

    /**
     * 停止序列列表
     * <p>
     * 当模型生成到这些序列之一时，将停止生成并返回结果。
     * 常用于控制输出格式，如停止于特定的结束标记。
     */
    private List<String> stopSequences;

    /**
     * 响应 MIME 类型
     * <p>
     * 指定生成响应的格式类型。支持的类型包括：
     * <ul>
     *   <li>"text/plain" - 纯文本（默认）</li>
     *   <li>"application/json" - JSON 格式</li>
     * </ul>
     */
    private String responseMimeType;

    /**
     * 响应 Schema
     * <p>
     * 当 responseMimeType 为 "application/json" 时，
     * 此字段指定响应的 JSON Schema，用于约束输出格式。
     */
    private Schema responseSchema;

    /**
     * 响应 JSON Schema（保留字段）
     * <p>
     * 内部使用，用于兼容不同版本的 API。
     */
    private Object responseJsonSchema;

    /**
     * 响应模态列表
     * <p>
     * 指定生成响应的模态类型，如文本、音频等。
     */
    private List<Modality> responseModalities;

    /**
     * 候选答案数量
     * <p>
     * 指定返回的候选答案数量。
     * 值为 1 到 8 之间的整数。
     */
    private Integer candidateCount;

    /**
     * 最大输出 Token 数量
     * <p>
     * 限制生成内容的最大长度，以 Token 为单位。
     * 不同的模型有不同的最大限制。
     */
    private Integer maxOutputTokens;

    /**
     * 温度
     * <p>
     * 控制生成内容的随机性。取值范围 0.0 到 2.0：
     * <ul>
     *   <li>较低的值（如 0.2）使输出更加确定性、聚焦</li>
     *   <li>较高的值（如 0.8）使输出更加随机、多样化</li>
     * </ul>
     */
    private Double temperature;

    /**
     * Top-P 采样概率
     * <p>
     * 控制ucleus 采样的概率质量阈值。
     * 取值范围 0.0 到 1.0，默认为 0.95。
     * 较低的值使分布更集中在高概率词上。
     */
    private Double topP;

    /**
     * Top-K 采样
     * <p>
     * 控制每次生成时考虑的候选词数量。
     * 取值范围 1 到 40。
     * 较低的值使输出更加确定性。
     */
    private Integer topK;

    /**
     * 随机种子
     * <p>
     * 用于控制生成结果的确定性。
     * 相同的种子和配置应该产生相同的结果。
     */
    private Long seed;

    /**
     * 存在惩罚
     * <p>
     * 对已出现在生成文本中的 token 进行惩罚，
     * 取值范围 -2.0 到 2.0，正值减少重复。
     */
    private Double presencePenalty;

    /**
     * 频率惩罚
     * <p>
     * 根据 token 在生成文本中的出现频率进行惩罚，
     * 取值范围 -2.0 到 2.0，正值减少重复。
     */
    private Double frequencyPenalty;

    /**
     * 是否返回 Log probabilities
     * <p>
     * 设置为 true 时，返回生成结果的概率信息。
     */
    private Boolean responseLogprobs;

    /**
     * Log probabilities 数量
     * <p>
     * 指定每个位置返回多少个最可能的 token 的 log probability。
     */
    private Integer logprobs;

    /**
     * 是否启用增强的公民回答
     * <p>
     * 启用后，模型将提供更符合公民责任的回答。
     */
    private Boolean enableEnhancedCivicAnswers;

    /**
     * 语音配置
     * <p>
     * 配置语音输出相关的参数。
     */
    private SpeechConfig speechConfig;

    /**
     * 思考配置
     * <p>
     * 配置模型思考过程的参数，如是否显示思考过程、思考预算等。
     */
    private ThinkingConfig thinkingConfig;

    /**
     * 图像配置
     * <p>
     * 配置图像生成或处理相关的参数。
     */
    private ImageConfig imageConfig;

    /**
     * 媒体分辨率
     * <p>
     * 指定生成媒体的分辨率级别。
     */
    private MediaResolution mediaResolution;

    public List<String> getStopSequences() {
        return stopSequences;
    }

    public GenerationConfig setStopSequences(List<String> stopSequences) {
        this.stopSequences = stopSequences;
        return this;
    }

    public String getResponseMimeType() {
        return responseMimeType;
    }

    public GenerationConfig setResponseMimeType(String responseMimeType) {
        this.responseMimeType = responseMimeType;
        return this;
    }

    public Schema getResponseSchema() {
        return responseSchema;
    }

    public GenerationConfig setResponseSchema(Schema responseSchema) {
        this.responseSchema = responseSchema;
        return this;
    }

    public Object getResponseJsonSchema() {
        return responseJsonSchema;
    }

    public GenerationConfig setResponseJsonSchema(Object responseJsonSchema) {
        this.responseJsonSchema = responseJsonSchema;
        return this;
    }

    public List<Modality> getResponseModalities() {
        return responseModalities;
    }

    public GenerationConfig setResponseModalities(List<Modality> responseModalities) {
        this.responseModalities = responseModalities;
        return this;
    }

    public Integer getCandidateCount() {
        return candidateCount;
    }

    public GenerationConfig setCandidateCount(Integer candidateCount) {
        this.candidateCount = candidateCount;
        return this;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public GenerationConfig setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
        return this;
    }

    public Double getTemperature() {
        return temperature;
    }

    public GenerationConfig setTemperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    public Double getTopP() {
        return topP;
    }

    public GenerationConfig setTopP(Double topP) {
        this.topP = topP;
        return this;
    }

    public Integer getTopK() {
        return topK;
    }

    public GenerationConfig setTopK(Integer topK) {
        this.topK = topK;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    public GenerationConfig setSeed(Long seed) {
        this.seed = seed;
        return this;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public GenerationConfig setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
        return this;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public GenerationConfig setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
        return this;
    }

    public Boolean getResponseLogprobs() {
        return responseLogprobs;
    }

    public GenerationConfig setResponseLogprobs(Boolean responseLogprobs) {
        this.responseLogprobs = responseLogprobs;
        return this;
    }

    public Integer getLogprobs() {
        return logprobs;
    }

    public GenerationConfig setLogprobs(Integer logprobs) {
        this.logprobs = logprobs;
        return this;
    }

    public Boolean getEnableEnhancedCivicAnswers() {
        return enableEnhancedCivicAnswers;
    }

    public GenerationConfig setEnableEnhancedCivicAnswers(Boolean enableEnhancedCivicAnswers) {
        this.enableEnhancedCivicAnswers = enableEnhancedCivicAnswers;
        return this;
    }

    public SpeechConfig getSpeechConfig() {
        return speechConfig;
    }

    public GenerationConfig setSpeechConfig(SpeechConfig speechConfig) {
        this.speechConfig = speechConfig;
        return this;
    }

    public ThinkingConfig getThinkingConfig() {
        return thinkingConfig;
    }

    public GenerationConfig setThinkingConfig(ThinkingConfig thinkingConfig) {
        this.thinkingConfig = thinkingConfig;
        return this;
    }

    public ImageConfig getImageConfig() {
        return imageConfig;
    }

    public GenerationConfig setImageConfig(ImageConfig imageConfig) {
        this.imageConfig = imageConfig;
        return this;
    }

    public MediaResolution getMediaResolution() {
        return mediaResolution;
    }

    public GenerationConfig setMediaResolution(MediaResolution mediaResolution) {
        this.mediaResolution = mediaResolution;
        return this;
    }
}
