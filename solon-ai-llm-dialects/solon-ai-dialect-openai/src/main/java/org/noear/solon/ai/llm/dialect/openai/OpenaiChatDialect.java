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
package org.noear.solon.ai.llm.dialect.openai;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.Date;
import java.util.List;

/**
 * Openai 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OpenaiChatDialect extends AbstractChatDialect {
    private static final OpenaiChatDialect instance = new OpenaiChatDialect();
    public static OpenaiChatDialect getInstance() {
        return instance;
    }

    @Override
    protected String getApiUrl(ChatConfig config) {
        return OpenaiDialectSupport.buildApiUrl(config.getApiUrl(), "chat/completions");
    }

    /**
     * 是否为默认
     */
    @Override
    public boolean isDefault() {
        return true;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return false;
    }

    @Override
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        ONode oNode = super.buildRequestJson(config, options, messages, isStream);

        // 官方流式协议：仅当 stream_options.include_usage=true 时最后一个 chunk 才会携带 usage（否则流式 usage 恒为 null）
        // 用户已显式配置 stream_options 时不覆盖；OpenAI 官方及主流兼容端点（DeepSeek/vLLM 等）均支持
        if (isStream && oNode.hasKey("stream_options") == false) {
            oNode.getOrNew("stream_options").set("include_usage", true);
        }

        return oNode;
    }

    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        if ("[DONE]".equals(json)) { //不是数据结构
            if(resp.isFinished() == false) {
                resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                resp.setFinished(true);
            }
            return true;
        }

        //有些中转会直接输出："error xxx" 内容
        if (tryParseErrorText(resp, json)) {
            return true;
        }

        //解析
        ONode oResp = ONode.ofJson(json);

        if (oResp.isObject() == false) {
            return false;
        }

        // 非官方规范的顶层错误形态（个别兼容端点）与官方 {error:{message,type,code}} 统一走规范提取，
        // 避免 message 为对象时取出 null
        if ("error".equals(oResp.get("object").getString())) {
            resp.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(
                    oResp.hasKey("error") ? oResp.get("error") : oResp.getOrNull("message"))));
        } else if (oResp.hasKey("error")) {
            // 规范错误提取：error 为对象（{message,type,code}），不能整体序列化为字符串
            resp.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(oResp.get("error"))));
        } else {
            resp.setModel(oResp.get("model").getString());

            // created 为官方必填，但部分兼容端点可能缺失；缺省或非法时用当前时间兜底，避免得到 epoch-0
            long createdSeconds = oResp.get("created").getLong();
            Date created = createdSeconds > 0 ? new Date(createdSeconds * 1000) : new Date();

            // 官方 include_usage=true 时最后一个 usage chunk 的 choices 为空数组；个别端点可能缺省该字段，做防御
            ONode oChoices = oResp.getOrNull("choices");
            if (oChoices != null && oChoices.isArray()) {
                for (ONode oChoice1 : oChoices.getArray()) {
                    int index = oChoice1.get("index").getInt();
                    String finish_reason = oChoice1.get("finish_reason").getString();

                    List<AssistantMessage> messageList;
                    if (resp.isStream()) {   //object=chat.completion.chunk
                        messageList = parseAssistantMessage(resp, oChoice1.get("delta"));
                    } else {
                        //object=chat.completion
                        messageList = parseAssistantMessage(resp, oChoice1.get("message"));
                    }

                    for (AssistantMessage msg1 : messageList) {
                        resp.addChoice(new ChatChoice(index, created, finish_reason, msg1));
                    }

                    if (Utils.isNotEmpty(finish_reason)) {
                        resp.setFinished(true);
                        resp.lastFinishReason = finish_reason;
                    }
                }
            }

            if (resp.isStream() == false) {
                // 非流式：一次就是全部。部分兼容端点不回 finish_reason，此处统一标完成，
                // 与 Responses 方言的非流式语义保持一致，避免上层拿到 isFinished=false
                resp.setFinished(true);
            }

            if (resp.isFinished()) {
                if (resp.hasChoices() == false) { //完成时。如果为空，则补位
                    resp.addChoice(new ChatChoice(0, created, resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                }
            }

            ONode oUsage = oResp.getOrNull("usage");
            if (oUsage != null) {
                long promptTokens = oUsage.get("prompt_tokens").getLong();
                long completionTokens = oUsage.get("completion_tokens").getLong();
                // 官方 SDK 中 total_tokens 为 optional（CompletionUsage），缺省时用输入+输出兜底
                long totalTokens = oUsage.hasKey("total_tokens")
                        ? oUsage.get("total_tokens").getLong() : (promptTokens + completionTokens);

                // 思考 token 统计：优先 DeepSeek 形态 completion_tokens_details.reasoning_tokens，兜底 think_tokens
                long thinkTokens = 0L;
                ONode completionTokensDetails = oUsage.getOrNull("completion_tokens_details");
                if (completionTokensDetails != null) {
                    thinkTokens = completionTokensDetails.get("reasoning_tokens").getLong();
                }
                if (thinkTokens == 0L) {
                    thinkTokens = oUsage.get("think_tokens").getLong();
                }

                // 缓存 token 统计（官方 prompt_tokens_details 含 cached_tokens / cache_write_tokens；
                // 另兼容 DeepSeek 形态 prompt_cache_hit_tokens）
                long cacheReadInputTokens = 0L;
                long cacheCreationInputTokens = 0L;
                ONode promptTokensDetails = oUsage.getOrNull("prompt_tokens_details");
                if (promptTokensDetails != null) {
                    cacheReadInputTokens = promptTokensDetails.get("cached_tokens").getLong();
                    cacheCreationInputTokens = promptTokensDetails.get("cache_write_tokens").getLong();
                }
                if (cacheReadInputTokens == 0L) {
                    cacheReadInputTokens = oUsage.get("prompt_cache_hit_tokens").getLong();
                }

                resp.setUsage(new AiUsage(promptTokens, thinkTokens, completionTokens, totalTokens,
                        cacheCreationInputTokens, cacheReadInputTokens, oUsage));
            }
        }

        return true;
    }
}