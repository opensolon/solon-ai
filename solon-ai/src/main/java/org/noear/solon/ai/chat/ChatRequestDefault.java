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

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.noear.solon.net.http.textstream.TextStreamUtil;
import org.noear.solon.rx.SimpleSubscriber;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 聊天请求实现
 *
 * @author noear
 * @since 3.1
 */
public class ChatRequestDefault implements ChatRequest {
    private static final Logger log = LoggerFactory.getLogger(ChatRequestDefault.class);
    private static final ChatOptions OPTIONS_DEFAULT = new ChatOptions();

    private final ChatConfig config;
    private final ChatDialect dialect;
    private final List<ChatMessage> messages;

    private ChatOptions options;

    public ChatRequestDefault(ChatConfig config, ChatDialect dialect, List<ChatMessage> messages) {
        this.config = config;
        this.dialect = dialect;
        this.messages = messages;
        this.options = OPTIONS_DEFAULT;
    }

    /**
     * 选项设置
     *
     * @param options 选项
     */
    @Override
    public ChatRequest options(ChatOptions options) {
        if (options != null) {
            this.options = options;
        }

        return this;
    }

    /**
     * 选项配置
     *
     * @param optionsBuilder 选项构建器
     */
    @Override
    public ChatRequest options(Consumer<ChatOptions> optionsBuilder) {
        this.options = ChatOptions.of();
        optionsBuilder.accept(options);
        return this;
    }

    /**
     * 调用
     */
    @Override
    public ChatResponse call() throws IOException {
        HttpUtils httpUtils = config.createHttpUtils();

        String reqJson = dialect.buildRequestJson(config, options, messages, false);

        if (log.isTraceEnabled()) {
            log.trace("ai-request: {}", reqJson);
        }

        String respJson = httpUtils.bodyOfJson(reqJson).post();

        if (log.isTraceEnabled()) {
            log.trace("ai-response: {}", respJson);
        }

        ChatResponseDefault resp = new ChatResponseDefault(false);
        dialect.parseResponseJson(config, resp, respJson);

        if (resp.getError() != null) {
            throw resp.getError();
        }

        if (resp.hasChoices()) {
            AssistantMessage choiceMessage = resp.getMessage();
            messages.add(choiceMessage); //添加到记忆
            if (Utils.isNotEmpty(choiceMessage.getToolCalls())) {
                buildToolMessage(resp, choiceMessage);

                return call();
            }
        }

        return resp;
    }

    /**
     * 流响应
     */
    @Override
    public Publisher<ChatResponse> stream() {
        HttpUtils httpUtils = config.createHttpUtils();

        String reqJson = dialect.buildRequestJson(config, options, messages, true);

        if (log.isTraceEnabled()) {
            log.trace("ai-request: {}", reqJson);
        }

        return subscriber -> {
            httpUtils.bodyOfJson(reqJson).execAsync("POST")
                    .whenComplete((resp, err) -> {
                        if (err == null) {
                            try {
                                parseResp(resp, subscriber);
                            } catch (IOException e) {
                                subscriber.onError(e);
                            }
                        } else {
                            subscriber.onError(err);
                        }
                    });
        };
    }

    private void parseResp(HttpResponse httpResp, Subscriber<? super ChatResponse> subscriber) throws IOException {
        ChatResponseDefault resp = new ChatResponseDefault(true);
        String contentType = httpResp.header("Content-Type");

        try {
            if (contentType != null && contentType.startsWith(MimeType.TEXT_EVENT_STREAM_VALUE)) {
                TextStreamUtil.parseEventStream(httpResp.body(), new SimpleSubscriber<ServerSentEvent>()
                        //.doOnSubscribe(subscriber::onSubscribe)//不要做订阅（外部不支持多次触发）
                        .doOnNext(event -> {
                            return onEventStream(resp, event, subscriber);
                        })
                        .doOnComplete(() -> {
                            onEventEnd(resp, subscriber);
                        })
                        .doOnError(subscriber::onError));
            } else {
                TextStreamUtil.parseTextStream(httpResp.body(), new SimpleSubscriber<String>()
                        //.doOnSubscribe(subscriber::onSubscribe)
                        .doOnNext(data -> {
                            return onEventStream(resp, new ServerSentEvent(null, data), subscriber);
                        })
                        .doOnComplete(() -> {
                            onEventEnd(resp, subscriber);
                        })
                        .doOnError(subscriber::onError));
            }
        } catch (Throwable ex) {
            subscriber.onError(ex);
        }
    }

    private void onEventEnd(ChatResponseDefault resp, Subscriber<? super ChatResponse> subscriber) {
        if (resp.isFinished() == false && resp.toolCallBuilders.size() > 0) {
            buildStreamToolMessage(resp);
            stream().subscribe(subscriber);
            return;
        }

        //添加到记忆（最后的聚合消息）
        AssistantMessage aggregationMessage = resp.getAggregationMessage();
        if (aggregationMessage != null) {
            messages.add(aggregationMessage);
        }

        subscriber.onComplete();
    }

    private boolean onEventStream(ChatResponseDefault resp, ServerSentEvent event, Subscriber<? super ChatResponse> subscriber) {
        if (log.isTraceEnabled()) {
            log.trace("ai-response: {}", event.data());
        }

        if (Utils.isEmpty(event.data())) {
            return true;
        }

        resp.reset();
        if (dialect.parseResponseJson(config, resp, event.data())) {
            if (resp.getError() != null) {
                subscriber.onError(resp.getError());
                return false;
            }

            if (resp.hasChoices()) {
                AssistantMessage choiceMessage = resp.getMessage();
                if (Utils.isNotEmpty(choiceMessage.getToolCalls())) {
                    //messages.add(choiceMessage);
                    buildToolCallBuilder(resp, choiceMessage);
                }

                if (choiceMessage != null) {
                    if (resp.getChoices().size() > 1) {
                        //有多个选择时，拆成多个。
                        List<ChatChoice> choices = new ArrayList<>(resp.getChoices());
                        for (ChatChoice choice : choices) {
                            resp.reset();
                            resp.addChoice(choice);
                            subscriber.onNext(resp);
                            publishResponse(subscriber, resp, choice);
                        }
                    } else {
                        publishResponse(subscriber, resp, resp.getChoices().get(0));
                    }
                }
            }

            if (resp.isFinished()) {
                if (resp.toolCallBuilders.size() > 0) {
                    buildStreamToolMessage(resp);
                    stream().subscribe(subscriber);
                    return false; //这样，不会触发外层的完成事件
                }
            }
        }

        return true;
    }

    private void buildStreamToolMessage(ChatResponseDefault resp) {
        ONode oNode = dialect.buildAssistantMessageNode(resp.toolCallBuilders);

        List<AssistantMessage> assistantMessages = dialect.parseAssistantMessage(resp, oNode);

        messages.addAll(assistantMessages);

        buildToolMessage(resp, assistantMessages.get(0));
    }

    private void publishResponse(Subscriber<? super ChatResponse> subscriber, ChatResponseDefault resp, ChatChoice choice) {
        if (choice.getMessage().getContent() != null) {
            resp.aggregationMessageContent.append(choice.getMessage().getContent());
        }
        subscriber.onNext(resp);
    }

    private void buildToolCallBuilder(ChatResponseDefault resp, AssistantMessage acm) {
        if (Utils.isEmpty(acm.getToolCalls())) {
            return;
        }

        for (ToolCall call : acm.getToolCalls()) {
            ToolCallBuilder callBuilder = resp.toolCallBuilders.computeIfAbsent(call.index(), k -> new ToolCallBuilder());

            if (call.id() != null) {
                callBuilder.idBuilder.append(call.id());
            }

            if (call.name() != null) {
                callBuilder.nameBuilder.append(call.name());
            }

            if (call.argumentsStr() != null) {
                callBuilder.argumentsBuilder.append(call.argumentsStr());
            }
        }
    }

    private void buildToolMessage(ChatResponseDefault resp, AssistantMessage acm) throws ChatException {
        if (Utils.isEmpty(acm.getToolCalls())) {
            return;
        }

        for (ToolCall call : acm.getToolCalls()) {
            FunctionTool func = config.getDefaultTool(call.name());

            if (func == null) {
                func = options.tool(call.name());
            }

            if (func != null) {
                try {
                    String content = func.handle(call.arguments());
                    messages.add(ChatMessage.ofTool(content, call.name(), call.id()));
                } catch (Throwable ex) {
                    throw new ChatException("The function call failed!", ex);
                }
            } else {
                //会存在调用的call实际上不存在的情况
                log.warn("Tool call not found: {}", call.name());
            }
        }
    }
}