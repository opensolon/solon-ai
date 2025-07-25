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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天响应实现
 *
 * @author noear
 * @since 3.1
 */
public class ChatResponseDefault implements ChatResponse {
    private final boolean stream;

    protected String responseData;

    protected final List<ChatChoice> choices = new ArrayList<>();
    protected ChatException error;
    protected AiUsage usage;
    protected String model;
    protected boolean finished;
    protected final Map<Integer, ToolCallBuilder> toolCallBuilders = new ConcurrentHashMap();

    //取合消息内容
    protected StringBuilder aggregationMessageContent = new StringBuilder();

    public ChatResponseDefault(boolean stream) {
        this.stream = stream;
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

    /**
     * 获取聚合消息
     */
    @Override
    public AssistantMessage getAggregationMessage() {
        if (hasChoices()) {
            if (stream) {
                return new AssistantMessage(aggregationMessageContent.toString(), lastChoice().getMessage().isThinking());
            } else {
                return lastChoice().getMessage();
            }
        } else {
            if (aggregationMessageContent.length() > 0) {
                return new AssistantMessage(aggregationMessageContent.toString(), false);
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
     * 获取消息内容
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