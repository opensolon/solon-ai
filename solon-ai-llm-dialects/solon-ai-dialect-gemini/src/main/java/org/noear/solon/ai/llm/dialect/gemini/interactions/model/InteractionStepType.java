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

/**
 * Interactions API 步骤类型枚举
 * <p>
 * 定义 Gemini Interactions API 中支持的步骤类型。
 * 步骤（Step）是 Interactions API 的基本响应单元，替代了 Generate Content API 中的 candidates。
 *
 * @since 3.1
 */
public enum InteractionStepType {

    /**
     * 用户输入步骤
     * <p>
     * 表示用户发送的消息内容。
     * 在请求的 input[] 中使用。
     */
    USER_INPUT("user_input"),

    /**
     * 模型输出步骤
     * <p>
     * 表示模型生成的文本响应。
     * 是 steps[] 中最常见的响应类型。
     */
    MODEL_OUTPUT("model_output"),

    /**
     * 思考步骤
     * <p>
     * 表示模型的内部推理过程。
     * 包含 summary（思考摘要）和 signature（签名，用于工具调用验证）。
     */
    THOUGHT("thought"),

    /**
     * 函数调用步骤
     * <p>
     * 表示模型决定调用一个工具/函数。
     * 包含 name（函数名）、arguments（参数）和 call_id（调用标识）。
     * 对应的响应状态为 requires_action。
     */
    FUNCTION_CALL("function_call"),

    /**
     * 函数结果步骤
     * <p>
     * 表示工具/函数调用的返回结果。
     * 在请求的 input[] 中用于传递工具执行结果。
     */
    FUNCTION_RESULT("function_result"),

    /**
     * 谷歌搜索调用步骤
     * <p>
     * 表示模型触发了 Google 搜索（当配置了 google_search 工具时）。
     */
    GOOGLE_SEARCH_CALL("google_search_call"),

    /**
     * 谷歌搜索结果步骤
     * <p>
     * 表示 Google 搜索返回的结果。
     */
    GOOGLE_SEARCH_RESULT("google_search_result");

    private final String apiValue;

    InteractionStepType(String apiValue) {
        this.apiValue = apiValue;
    }

    /**
     * 获取 API 使用的字符串值
     */
    public String getApiValue() {
        return apiValue;
    }

    /**
     * 从 API 字符串值解析枚举
     *
     * @param value API 返回的 type 字符串
     * @return 对应的枚举值，如果未知则返回 null
     */
    public static InteractionStepType fromApiValue(String value) {
        if (value == null) return null;
        for (InteractionStepType type : values()) {
            if (type.apiValue.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
