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
package org.noear.solon.ai.llm.dialect.gemini.interactions.model;

import java.util.List;

/**
 * Interactions API 配置对象
 * <p>
 * 对应 Interactions API 请求体中的 config 字段。
 * 使用 snake_case 命名以匹配 JSON 字段名。
 *
 * @since 3.1
 */
public class InteractionConfig {

    /**
     * 温度参数，控制输出随机性
     */
    private Double temperature;

    /**
     * Top-P 采样参数
     */
    private Double top_p;

    /**
     * 最大输出 Token 数
     */
    private Integer max_output_tokens;

    /**
     * 停止序列列表
     */
    private List<String> stop_sequences;

    /**
     * 响应模态列表（如 ["TEXT", "IMAGE"]）
     */
    private List<String> response_modalities;

    /**
     * 思考级别（"low" / "medium" / "high"）
     */
    private String thinking_level;

    /**
     * 是否返回思考摘要
     */
    private Boolean thinking_summaries;

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTop_p() {
        return top_p;
    }

    public void setTop_p(Double top_p) {
        this.top_p = top_p;
    }

    public Integer getMax_output_tokens() {
        return max_output_tokens;
    }

    public void setMax_output_tokens(Integer max_output_tokens) {
        this.max_output_tokens = max_output_tokens;
    }

    public List<String> getStop_sequences() {
        return stop_sequences;
    }

    public void setStop_sequences(List<String> stop_sequences) {
        this.stop_sequences = stop_sequences;
    }

    public List<String> getResponse_modalities() {
        return response_modalities;
    }

    public void setResponse_modalities(List<String> response_modalities) {
        this.response_modalities = response_modalities;
    }

    public String getThinking_level() {
        return thinking_level;
    }

    public void setThinking_level(String thinking_level) {
        this.thinking_level = thinking_level;
    }

    public Boolean getThinking_summaries() {
        return thinking_summaries;
    }

    public void setThinking_summaries(Boolean thinking_summaries) {
        this.thinking_summaries = thinking_summaries;
    }
}
