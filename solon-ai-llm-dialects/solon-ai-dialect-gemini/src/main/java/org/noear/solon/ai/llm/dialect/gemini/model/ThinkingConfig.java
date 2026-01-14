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

/**
 * 思考配置
 * <p>
 * 用于配置 Gemini 模型的思考过程（Thinking）相关参数。
 * 支持启用或禁用思考过程显示，以及设置思考的 Token 预算。
 * <p>
 * 思考过程是 Gemini 模型在生成最终回答前的内部推理过程，
 * 启用后可以在响应中查看模型的思考步骤。
 * <p>
 * 示例配置：
 * <pre>{@code
 * ThinkingConfig config = new ThinkingConfig()
 *     .setIncludeThoughts(true)
 *     .setThinkingBudget(1024);
 * }</pre>
 *
 * @author cwdhf
 * @since 3.1
 */
public class ThinkingConfig {

    /**
     * 是否包含思考内容
     * <p>
     * 设置为 true 时，响应中将包含模型的思考过程。
     * 设置为 false 时，不显示思考过程，只返回最终回答。
     * <p>
     * 思考内容会标记在 "**<text>**" 和 "**<text>**" 之间。
     */
    private Boolean includeThoughts;

    /**
     * 思考预算
     * <p>
     * 指定用于思考过程的 Token 数量上限。
     * 取值范围：1 到 24576 之间的整数。
     * <ul>
     *   <li>较小的值（如 1024）：限制思考深度，减少延迟</li>
     *   <li>较大的值（如 8192）：允许更深入的推理，但会增加延迟</li>
     * </ul>
     * <p>
     * 注意：此值必须小于 maxOutputTokens。
     */
    private Integer thinkingBudget;

    /**
     * 思考级别
     * <p>
     * 控制模型在生成响应之前的内部推理过程的最大深度。
     * 如果未指定，默认为 HIGH。
     * <p>
     * 推荐用于 Gemini 3 或更高版本的模型。
     * 在早期模型上使用会导致错误。
     */
    private ThinkingLevel thinkingLevel;

    public Boolean getIncludeThoughts() {
        return includeThoughts;
    }

    public ThinkingConfig setIncludeThoughts(Boolean includeThoughts) {
        this.includeThoughts = includeThoughts;
        return this;
    }

    public Integer getThinkingBudget() {
        return thinkingBudget;
    }

    public ThinkingConfig setThinkingBudget(Integer thinkingBudget) {
        this.thinkingBudget = thinkingBudget;
        return this;
    }

    public ThinkingLevel getThinkingLevel() {
        return thinkingLevel;
    }

    public ThinkingConfig setThinkingLevel(ThinkingLevel thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
        return this;
    }

    /**
     * 思考级别枚举
     * <p>
     * 允许用户使用枚举而不是整数预算来指定思考深度。
     */
    public enum ThinkingLevel {

        /**
         * 未指定思考级别
         * <p>
         * 默认值。
         */
        THINKING_LEVEL_UNSPECIFIED,

        /**
         * 低思考级别
         * <p>
         * 较浅的推理深度。
         */
        LOW,

        /**
         * 高思考级别
         * <p>
         * 较深的推理深度。
         */
        HIGH
    }
}
