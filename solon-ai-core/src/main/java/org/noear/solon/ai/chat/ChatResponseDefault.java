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
package org.noear.solon.ai.chat;

import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天响应实现
 *
 * @author noear
 * @since 3.1
 */
public class ChatResponseDefault implements ChatResponse {
    private final ChatRequest request;
    private final ChatConfigReadonly config;
    private final ChatOptions options;
    private final boolean stream;

    protected String responseData;

    protected final List<ChatChoice> choices = new ArrayList<>();
    protected ChatException error;
    protected AiUsage usage;
    protected String model;
    protected boolean finished;

    protected final StringBuilder contentBuilder = new StringBuilder();
    protected final StringBuilder reasoningBuilder = new StringBuilder();
    protected final Map<String, ToolCallBuilder> toolCallBuilders = new LinkedHashMap<>();

    public ChatResponseDefault(ChatRequest req, boolean stream) {
        this.request = req;
        this.config = req.getConfig();
        this.options = req.getOptions();
        this.stream = stream;
    }

    public ChatRequest getRequest() {
        return request;
    }

    @Override
    public ChatConfigReadonly getConfig() {
        return config;
    }

    @Override
    public ChatOptions getOptions() {
        return options;
    }

    /**
     * 获取响应数据
     */
    @Override
    public String getResponseData() {
        return responseData;
    }

    /**
     * 获取模型
     */
    @Override
    public String getModel() {
        return model;
    }

    /**
     * 获取错误
     */
    @Override
    public ChatException getError() {
        return error;
    }

    /**
     * 是否有工具构建器
     */
    public boolean hasToolCallBuilders() {
        return Utils.isNotEmpty(toolCallBuilders);
    }

    /**
     * 获取所有选择
     */
    @Override
    public List<ChatChoice> getChoices() {
        return choices;
    }

    /**
     * 是否有消息
     */
    @Override
    public boolean hasChoices() {
        return Utils.isNotEmpty(choices);
    }

    /**
     * 最后一个选择
     */
    public ChatChoice lastChoice() {
        return choices.get(choices.size() - 1);
    }

    /**
     * 获取消息
     */
    @Override
    public AssistantMessage getMessage() {
        if (hasChoices()) {
            //取最后条消息
            return lastChoice().getMessage();
        } else {
            return null;
        }
    }

    public String getAggregationContent() {
        if (hasChoices()) {
            if (stream) {
                return contentBuilder.toString();
            } else {
                return lastChoice().getMessage().getContent();
            }
        } else {
            return contentBuilder.toString();
        }
    }

    /**
     * 获取聚合消息
     */
    @Override
    public AssistantMessage getAggregationMessage() {
        if (hasChoices()) {
            if (stream) {
                return new AssistantMessage(contentBuilder.toString(), lastChoice().getMessage().isThinking());
            } else {
                return lastChoice().getMessage();
            }
        } else {
            if (contentBuilder.length() > 0) {
                return new AssistantMessage(contentBuilder.toString(), false);
            } else {
                return null;
            }
        }
    }

    /**
     * 是否有消息内容
     */
    @Override
    public boolean hasContent() {
        return getContent() != null;
    }

    /**
     * 获取消息原始内容
     */
    @Override
    public String getContent() {
        if (hasChoices()) {
            return lastChoice().getMessage().getContent();
        } else {
            return null;
        }
    }

    /**
     * 获取消息结果内容（清理过思考）
     */
    @Override
    public String getResultContent() {
        if (hasChoices()) {
            return lastChoice().getMessage().getResultContent();
        } else {
            return null;
        }
    }

    /**
     * 获取使用情况（完成时，才会有使用情况）
     */
    @Override
    public @Nullable AiUsage getUsage() {
        return usage;
    }

    /**
     * 是否完成
     */
    @Override
    public boolean isFinished() {
        return finished;
    }

    /**
     * 是否为流响应
     */
    @Override
    public boolean isStream() {
        return stream;
    }

    /// //////////////////////////

    /**
     * 在思考中
     */
    public boolean in_thinking;

    /**
     * 有推理字段
     */
    public boolean has_reasoning_field;

    /**
     * 最后的 callId
     * */
    public String lastToolCallId;

    /**
     * 最后的 finishReason（保存 LLM 返回的原始值，使用时通过 normalizeFinishReason 归一化）
     */
    public String lastFinishReason;

    /**
     * 获取归一化后的 finishReason，如果没有则返回默认值 "stop"
     *
     * @return 归一化后的 finishReason
     */
    public String getLastFinishReasonNormalized() {
        String normalized = normalizeFinishReason(lastFinishReason);
        return normalized != null ? normalized : "stop";
    }

    /**
     * 归一化 finishReason
     *
     * <p>将各 LLM 返回的不同值映射为框架统一定义的值：
     * <ul>
     *   <li>工具调用："tool"</li>
     *   <li>正常结束："stop"</li>
     * </ul>
     *
     * @param finishReason LLM 返回的原始 finishReason
     * @return 归一化后的 finishReason
     */
    public static String normalizeFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isEmpty()) {
            return finishReason;
        }

        String lower = finishReason.toLowerCase();

        // 工具调用 → "tool"
        if (lower.contains("tool") || lower.contains("function")) {
            return "tool";
        }

        // 正常结束 → "stop"
        if (lower.contains("stop") || lower.contains("end")) {
            return "stop";
        }

        // 其他保持原值
        return finishReason;
    }

    /**
     * 重置响应数据
     */
    public void reset() {
        this.error = null;
        this.choices.clear();
    }

    /**
     * 设置响应数据
     */
    public void setResponseData(String responseData) {
        this.responseData = responseData;
    }

    /**
     * 添加输出选择
     *
     * @param choice 选择
     */
    public void addChoice(ChatChoice choice) {
        this.choices.add(choice);
    }

    /**
     * 设置错误
     *
     * @param error 错误
     */
    public void setError(ChatException error) {
        this.error = error;
    }

    /**
     * 设置使用情况
     *
     * @param usage 使用情况
     */
    public void setUsage(AiUsage usage) {
        this.usage = usage;
    }

    /**
     * 设置模型
     *
     * @param model 响应模型
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 设置完成状态
     *
     * @param finished 完成状态
     */
    public void setFinished(boolean finished) {
        this.finished = finished;
    }
}