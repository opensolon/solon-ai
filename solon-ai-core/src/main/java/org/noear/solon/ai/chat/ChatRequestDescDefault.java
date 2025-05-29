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
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.net.http.HttpException;
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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * 聊天请求描述实现
 *
 * @author noear
 * @since 3.1
 */
public class ChatRequestDescDefault implements ChatRequestDesc {
    private static final Logger log = LoggerFactory.getLogger(ChatRequestDescDefault.class);

    private final ChatConfig config;
    private final ChatDialect dialect;
    private final ChatSession session;

    private ChatOptions options;

    public ChatRequestDescDefault(ChatConfig config, ChatDialect dialect, ChatSession session) {
        this.config = config;
        this.dialect = dialect;
        this.session = session;
        this.options = new ChatOptions();
    }

    /**
     * 选项设置
     *
     * @param options 选项
     */
    @Override
    public ChatRequestDesc options(ChatOptions options) {
        if (options != null) {
            //重置
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
    public ChatRequestDesc options(Consumer<ChatOptions> optionsBuilder) {
        //可多次调用
        optionsBuilder.accept(options);
        return this;
    }

    /**
     * 调用
     */
    @Override
    public ChatResponse call() throws IOException {
        //收集拦截器
        List<RankEntity<ChatInterceptor>> interceptorList = new ArrayList<>();
        interceptorList.addAll(config.getDefaultInterceptors());
        interceptorList.addAll(options.interceptors());
        if (interceptorList.size() > 1) {
            Collections.sort(interceptorList);
        }

        //构建请求数据
        ChatRequest req = new ChatRequest(config, dialect, options, false, session.getMessages());

        CallChain chain = new CallChain(interceptorList, this::doCall);

        return chain.doIntercept(req);
    }

    /**
     * 调用
     */
    private ChatResponse doCall(ChatRequest req) throws IOException {
        HttpUtils httpUtils = config.createHttpUtils();

        String reqJson = req.toRequestData();

        if (log.isTraceEnabled()) {
            log.trace("ai-request: {}", reqJson);
        }

        String respJson = httpUtils.bodyOfJson(reqJson).post();

        if (log.isTraceEnabled()) {
            log.trace("ai-response: {}", respJson);
        }

        ChatResponseDefault resp = new ChatResponseDefault(false);
        resp.setResponseData(respJson);
        dialect.parseResponseJson(config, resp, respJson);

        if (resp.getError() != null) {
            throw resp.getError();
        }

        if (resp.hasChoices()) {
            AssistantMessage choiceMessage = resp.getMessage();
            session.addMessage(choiceMessage); //添加到记忆
            if (Utils.isNotEmpty(choiceMessage.getToolCalls())) {
                List<ToolMessage> returnDirectMessages = buildToolMessage(resp, choiceMessage);

                if (Utils.isEmpty(returnDirectMessages)) {
                    //没有直接返回的消息
                    return call();
                } else {
                    //要求直接返回（转为新的响应消息）
                    choiceMessage = dialect.buildAssistantMessageByToolMessages(returnDirectMessages);
                    resp.reset();
                    resp.addChoice(new ChatChoice(0, new Date(), "tool", choiceMessage));
                    session.addMessage(choiceMessage); //添加到记忆
                }
            }
        }

        return resp;
    }

    /**
     * 流响应
     */
    @Override
    public Publisher<ChatResponse> stream() {
        //收集拦截器
        List<RankEntity<ChatInterceptor>> interceptorList = new ArrayList<>();
        interceptorList.addAll(config.getDefaultInterceptors());
        interceptorList.addAll(options.interceptors());
        if (interceptorList.size() > 1) {
            Collections.sort(interceptorList);
        }

        //构建请求数据
        ChatRequest req = new ChatRequest(config, dialect, options, true, session.getMessages());

        StreamChain chain = new StreamChain(interceptorList, this::doStream);

        return chain.doIntercept(req);
    }

    /**
     * 流响应
     */
    private Publisher<ChatResponse> doStream(ChatRequest req) {
        HttpUtils httpUtils = config.createHttpUtils();

        String reqJson = req.toRequestData();

        if (log.isTraceEnabled()) {
            log.trace("ai-request: {}", reqJson);
        }

        return subscriber -> {
            httpUtils.bodyOfJson(reqJson).execAsync("POST")
                    .whenComplete((resp, err) -> {
                        Subscriber<? super ChatResponse> subscriberProxy = ChatSubscriberProxy.of(subscriber);

                        if (err == null) {
                            try {
                                if (resp.code() < 400) {
                                    parseResp(resp, subscriberProxy);
                                } else {
                                    subscriberProxy.onError(new HttpException("Error code:" + resp.code()));
                                }
                            } catch (IOException e) {
                                subscriberProxy.onError(e);
                            }
                        } else {
                            subscriberProxy.onError(err);
                        }
                    });
        };
    }

    private void parseResp(HttpResponse httpResp, Subscriber<? super ChatResponse> subscriber) throws IOException {
        ChatResponseDefault resp = new ChatResponseDefault(true);
        String contentType = httpResp.header("Content-Type");

        try {
            if (contentType != null && contentType.startsWith(MimeType.TEXT_EVENT_STREAM_VALUE)) {
                TextStreamUtil.parseSseStream(httpResp.body(), new SimpleSubscriber<ServerSentEvent>()
                        .doOnSubscribe(subscriber::onSubscribe)//不要做订阅（外部不支持多次触发）
                        .doOnNext(event -> {
                            return onEventStream(resp, event, subscriber);
                        })
                        .doOnComplete(() -> {
                            onEventEnd(resp, subscriber);
                        })
                        .doOnError(subscriber::onError));
            } else {
                TextStreamUtil.parseLineStream(httpResp.body(), new SimpleSubscriber<String>()
                        .doOnSubscribe(subscriber::onSubscribe)
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
            if (buildStreamToolMessage(resp, subscriber) == false) {
                return;
            }
        }

        //添加到记忆（最后的聚合消息）
        AssistantMessage aggregationMessage = resp.getAggregationMessage();
        if (aggregationMessage != null) {
            session.addMessage(aggregationMessage);
        }

        subscriber.onComplete();
    }

    private boolean onEventStream(ChatResponseDefault resp, ServerSentEvent event, Subscriber<? super ChatResponse> subscriber) {
        if (log.isTraceEnabled()) {
            log.trace("ai-response: {}", event.data());
        }

        resp.setResponseData(event.data());

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
                            publishResponse(subscriber, resp, choice);
                        }
                    } else {
                        publishResponse(subscriber, resp, resp.getChoices().get(0));
                    }
                }
            }

            if (resp.isFinished()) {
                if (resp.toolCallBuilders.size() > 0) {
                    return buildStreamToolMessage(resp, subscriber);
                }
            }
        }

        return true;
    }

    private boolean buildStreamToolMessage(ChatResponseDefault resp, Subscriber<? super ChatResponse> subscriber) {
        ONode oNode = dialect.buildAssistantMessageNode(resp.toolCallBuilders);

        List<AssistantMessage> assistantMessages = dialect.parseAssistantMessage(resp, oNode);

        session.addMessage(assistantMessages);

        List<ToolMessage> returnDirectMessages = buildToolMessage(resp, assistantMessages.get(0));

        if (Utils.isEmpty(returnDirectMessages)) {
            //没有要求直接返回
            stream().subscribe(subscriber);
            return false; //不触发外层的完成事件
        } else {
            //要求直接返回（转为新的响应消息）
            AssistantMessage message = dialect.buildAssistantMessageByToolMessages(returnDirectMessages);
            resp.reset();
            resp.addChoice(new ChatChoice(0, new Date(), "tool", message));
            resp.aggregationMessageContent.setLength(0);
            publishResponse(subscriber, resp, resp.lastChoice());
            return true; //触发外层的完成事件
        }
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

    /**
     * @return returnDirect
     */
    private List<ToolMessage> buildToolMessage(ChatResponseDefault resp, AssistantMessage acm) throws ChatException {
        if (Utils.isEmpty(acm.getToolCalls())) {
            return null;
        }

        List<ToolMessage> toolMessages = new ArrayList<>();
        for (ToolCall call : acm.getToolCalls()) {
            FunctionTool func = config.getDefaultTool(call.name());

            if (func == null) {
                func = options.tool(call.name());
            }

            if (func != null) {
                try {
                    String content = func.handle(call.arguments());
                    ToolMessage toolMessage = (ToolMessage) ChatMessage.ofTool(content, call.name(), call.id(), func.returnDirect());
                    session.addMessage(toolMessage);
                    toolMessages.add(toolMessage);
                } catch (Throwable ex) {
                    throw new ChatException("The function call failed!", ex);
                }
            } else {
                //会存在调用的call实际上不存在的情况
                log.warn("Tool call not found: {}", call.name());
            }
        }

        if (toolMessages.size() > 0 && toolMessages.stream().filter(m -> m.isReturnDirect() == false).count() == 0) {
            //说明全部要求直接返回
            return toolMessages;
        } else {
            return null;
        }
    }
}