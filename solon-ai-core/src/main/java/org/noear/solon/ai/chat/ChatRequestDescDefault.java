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

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.skill.SkillUtil;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.net.http.HttpException;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.noear.solon.net.http.textstream.TextStreamUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final Prompt originalPrompt;

    private ChatSession session;
    private ChatOptions options;

    public ChatRequestDescDefault(ChatConfig config, ChatDialect dialect, ChatSession session, Prompt prompt) {
        this.config = config;
        this.dialect = dialect;
        this.session = session;
        this.originalPrompt = prompt;

        this.options = new ChatOptions();
        this.options.putAll(config.getModelOptions());
        this.options.role(config.getModelOptions().role());
        this.options.instruction(config.getModelOptions().instruction());
    }

    public ChatRequestDesc session(ChatSession session) {
        this.session = session;
        return this;
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
     * 准备
     */
    private void prepare() {
        if (prepared.compareAndSet(false, true)) {
            if (session == null) {
                session = InMemoryChatSession.builder().build();
            }

            if (originalPrompt != null) {
                session.addMessage(originalPrompt);
            }

            // 如果没有 sessionId 则推入
            options.toolContext().computeIfAbsent(ChatSession.ATTR_SESSIONID,
                    k -> session.getSessionId());

            //---

            StringBuilder instructionBuilder = new StringBuilder();

            if (Assert.isNotEmpty(options.role())) {
                instructionBuilder.append("## 你的角色\n").append(options.role()).append("\n\n");
            }

            if (Assert.isNotEmpty(options.instruction())) {
                instructionBuilder.append("## 执行指令\n").append(options.instruction()).append("\n");
            }

            if (originalPrompt != null && Assert.isNotEmpty(options.toolContext())) {
                originalPrompt.attrs().putAll(options.toolContext());
            }

            for (RankEntity<ChatInterceptor> item : options.interceptors()) {
                item.target.onPrepare(session, options, originalPrompt, instructionBuilder);
            }

            StringBuilder skillsInstruction = SkillUtil.activeSkills(options, originalPrompt, new StringBuilder());
            if (skillsInstruction.length() > 0) {
                instructionBuilder.append("\n");
                instructionBuilder.append(skillsInstruction);
            }

            if (instructionBuilder.length() > 0) {
                systemMessage = ChatMessage.ofSystem(instructionBuilder.toString());
            }
        }
    }

    private AtomicBoolean prepared = new AtomicBoolean(false);
    private SystemMessage systemMessage;

    /**
     * 调用
     */
    @Override
    public ChatResponse call() throws IOException {
        prepare();

        return internalCall();
    }

    protected ChatResponse internalCall() throws IOException {
        //构建请求数据（每次请求重新构建 finalPrompt）
        ChatRequest req = new ChatRequest(config, dialect, options, session, systemMessage, originalPrompt, false);

        CallChain chain = new CallChain(options.interceptors(), this::doCall);

        return chain.doIntercept(req);
    }

    /**
     * 调用
     */
    private ChatResponse doCall(ChatRequest req) throws IOException {
        HttpUtils httpUtils = dialect.createHttpUtils(config, req.isStream());

        String reqJson = req.toRequestData();

        if (log.isDebugEnabled()) {
            log.debug("llm-request: {}", reqJson);
        }

        String respJson = httpUtils.bodyOfJson(reqJson).post();

        if (log.isDebugEnabled()) {
            log.debug("llm-response: {}", respJson);
        }

        ChatResponseDefault resp = new ChatResponseDefault(req, false);
        resp.setResponseData(respJson);
        dialect.parseResponseJson(config, resp, respJson);

        if (resp.getError() != null) {
            throw resp.getError();
        }

        if (resp.hasChoices()) {
            AssistantMessage choiceMessage = resp.getMessage();
            session.addMessage(choiceMessage); //添加到记忆

            if (options.isAutoToolCall() && Assert.isNotEmpty(choiceMessage.getToolCalls())) {
                List<ToolMessage> returnDirectMessages = buildToolMessage(resp, choiceMessage);

                if (Assert.isEmpty(returnDirectMessages)) {
                    //没有直接返回的消息
                    return internalCall();
                } else {
                    //要求直接返回（转为新的响应消息）
                    choiceMessage = dialect.buildAssistantMessageByToolMessages(choiceMessage, returnDirectMessages);
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
    public Flux<ChatResponse> stream() {
        prepare();

        return Flux.from(internalStream());
    }

    private Flux<ChatResponse> internalStream() {
        //构建请求数据（每次请求重新构建 finalPrompt）
        ChatRequest req = new ChatRequest(config, dialect, options, session, systemMessage, originalPrompt, true);

        StreamChain chain = new StreamChain(options.interceptors(), this::doStream);

        return chain.doIntercept(req);
    }

    /**
     * 流响应
     */
    private Flux<ChatResponse> doStream(ChatRequest req) {
        HttpUtils httpUtils = dialect.createHttpUtils(config, req.isStream());

        String reqJson = req.toRequestData();

        if (log.isDebugEnabled()) {
            log.debug("llm-request: {}", reqJson);
        }

        return Mono.fromFuture(httpUtils.bodyOfJson(reqJson).execAsync("POST"))
                .flatMapMany(resp -> {
                    try {
                        if (resp.code() < 400) {
                            return parseResp(req, resp);
                        } else {
                            return Flux.error(createHttpException(resp));
                        }
                    } catch (IOException e) {
                        return Flux.error(e);
                    }
                });

    }

    private Flux<ChatResponse> parseResp(ChatRequest req, HttpResponse httpResp) throws IOException {
        ChatResponseDefault respDesc = new ChatResponseDefault(req, true);
        String contentType = httpResp.header("Content-Type");

        return Flux.<ChatResponse>create(sink -> {
            Flux<?> source = (contentType != null && contentType.startsWith(MimeType.TEXT_EVENT_STREAM_VALUE))
                    ? TextStreamUtil.parseSseStream(httpResp)
                    : TextStreamUtil.parseLineStream(httpResp);

            // 使用原子引用管理订阅，以便内部控制终止
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();

            Disposable disposable = source.subscribe(
                    data -> {
                        // [对接点]：检查 sink 状态，如果已经完成或取消，不再处理
                        if (sink.isCancelled() == false) {
                            try {
                                ServerSentEvent sse = (data instanceof ServerSentEvent)
                                        ? (ServerSentEvent) data : new ServerSentEvent(null, (String) data);

                                // [对接点]：利用 onEventStream 的返回值
                                if (!onEventStream(respDesc, sse, sink)) {
                                    // 返回 false 说明内部要求终止（如报错或逻辑中断）
                                    disposableRef.get().dispose();
                                }
                            } catch (Throwable e) {
                                sink.error(e);
                            }
                        }
                    },
                    sink::error,
                    () -> {
                        // 只有在没有被手动 dispose 的情况下才执行 End 逻辑
                        if (sink.isCancelled() == false) {
                            try {
                                onEventEnd(respDesc, sink);
                            } catch (Throwable e) {
                                sink.error(e);
                            }
                        }
                    }
            );

            disposableRef.set(disposable);
            sink.onDispose(disposable);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private void onEventEnd(ChatResponseDefault resp, FluxSink<? super ChatResponse> sink) {
        if (resp.toolCallBuilders.size() > 0) {
            if (buildStreamToolCallMessage(resp, sink) == false) {
                return; // 进入了内部递归流处理，不执行 complete
            }
        }

        //添加到记忆（最后的聚合消息）
        AssistantMessage aggregationMessage = resp.getAggregationMessage();
        if (aggregationMessage != null) {
            session.addMessage(aggregationMessage);
        }

        sink.complete();
    }

    /**
     * @return 是否结束流
     */
    private boolean onEventStream(ChatResponseDefault resp, ServerSentEvent event, FluxSink<? super ChatResponse> sink) {
        if (log.isDebugEnabled()) {
            log.debug("llm-response: {}", event.data());
        }

        resp.setResponseData(event.data());

        if (Assert.isEmpty(event.data())) {
            return true;
        }

        resp.reset();
        if (dialect.parseResponseJson(config, resp, event.data())) {
            if (resp.getError() != null) {
                sink.error(resp.getError());
                return false;
            }

            if (resp.hasChoices()) {
                AssistantMessage choiceMessage = resp.getMessage();
                if (Assert.isNotEmpty(choiceMessage.getToolCalls())) {
                    //messages.add(choiceMessage);
                    buildToolCallBuilder(resp, choiceMessage);
                }

                // 拆分 Choice 并在当前 Sink 中发射
                List<ChatChoice> choices = new ArrayList<>(resp.getChoices());
                for (ChatChoice choice : choices) {
                    resp.reset();
                    resp.addChoice(choice);
                    publishResponse(sink, resp, choice);
                }
            }
        }

        return true;
    }

    /**
     * @return 是否结束流
     */
    private boolean buildStreamToolCallMessage(ChatResponseDefault resp, FluxSink<? super ChatResponse> sink) {
        try {
            ONode oNode = dialect.buildAssistantToolCallMessageNode(resp, resp.toolCallBuilders);
            List<AssistantMessage> assistantMessages = dialect.parseAssistantMessage(resp, oNode);

            // 如果没有消息，说明工具调用解析失败或没有工具需要处理，直接完成
            if (assistantMessages.isEmpty()) {
                log.debug("The tool call resolution result is empty, ending the streaming response");
                return true; //触发外层的完成事件
            }

            session.addMessage(assistantMessages);

            if (options.isAutoToolCall()) {
                AssistantMessage choiceMessage = assistantMessages.get(0);
                List<ToolMessage> returnDirectMessages = buildToolMessage(resp, choiceMessage);

                if (Assert.isEmpty(returnDirectMessages)) {
                    Disposable disposable = internalStream().subscribe(
                            sink::next,
                            sink::error,
                            sink::complete
                    );
                    sink.onDispose(disposable);

                    return false; //不触发外层的完成事件
                } else {
                    //要求直接返回（转为新的响应消息）
                    AssistantMessage message = dialect.buildAssistantMessageByToolMessages(choiceMessage, returnDirectMessages);
                    resp.reset();
                    resp.addChoice(new ChatChoice(0, new Date(), "tool", message));
                    //resp.aggregationMessageContent.setLength(0);
                    publishResponse(sink, resp, resp.lastChoice());
                    return true; //触发外层的完成事件
                }
            } else {
                AssistantMessage message = assistantMessages.get(0);
                resp.reset();
                resp.addChoice(new ChatChoice(0, new Date(), "tool", message));
                //resp.aggregationMessageContent.setLength(0);

                publishResponse(sink, resp, resp.lastChoice());
                return true; //触发外层的完成事件
            }

        } finally {
            //用完清掉
            resp.toolCallBuilders.clear();
        }
    }

    private HttpException createHttpException(HttpResponse resp) {
        try {
            String message = resp.bodyAsString();
            String description = Assert.isEmpty(message)
                    ? "Error code:" + resp.code()
                    : "Error code:" + resp.code() + ", message:" + message;
            return new HttpException(description);
        } catch (IOException e) {
            return new HttpException("Error code:" + resp.code(), e);
        }
    }

    private void publishResponse(FluxSink<? super ChatResponse> sink, ChatResponseDefault resp, ChatChoice choice) {
        if (choice.getMessage().hasContent()) {
            resp.contentBuilder.append(choice.getMessage().getContent());
        }
        sink.next(resp);
    }

    private void buildToolCallBuilder(ChatResponseDefault resp, AssistantMessage acm) {
        if (Assert.isEmpty(acm.getToolCalls())) {
            return;
        }

        if (Assert.isNotEmpty(acm.getContent())) {
            resp.contentBuilder.append(acm.getContent());
        }

        if (Assert.isNotEmpty(acm.getReasoning())) {
            resp.reasoningBuilder.append(acm.getReasoning());
        }

        for (ToolCall call : acm.getToolCalls()) {
            ToolCallBuilder callBuilder = resp.toolCallBuilders.computeIfAbsent(call.getIndex(), k -> new ToolCallBuilder());

            if (call.getId() != null && call.getId().contentEquals(callBuilder.idBuilder)
                    && call.getName() != null && call.getName().contentEquals(callBuilder.nameBuilder)) {
                //说明 id 和 name 在全量增加
            } else {
                if (call.getId() != null) {
                    callBuilder.idBuilder.append(call.getId());
                }

                if (call.getName() != null) {
                    callBuilder.nameBuilder.append(call.getName());
                }
            }

            if (call.getArgumentsStr() != null) {
                callBuilder.argumentsBuilder.append(call.getArgumentsStr());
            }
        }
    }

    /**
     * @return returnDirect
     */
    private List<ToolMessage> buildToolMessage(ChatResponseDefault resp, AssistantMessage acm) throws ChatException {
        if (Assert.isEmpty(acm.getToolCalls())) {
            return null;
        }

        List<ToolMessage> toolMessages = new ArrayList<>();
        for (ToolCall call : acm.getToolCalls()) {
            FunctionTool tool = options.tool(call.getName());

            if (tool != null) {
                try {
                    ToolResult toolResult = doToolCall(resp, tool, call.getArguments());
                    ToolMessage toolMessage = ChatMessage.ofTool(toolResult, call.getName(), call.getId(), tool.returnDirect());
                    toolMessage.addMetadata(tool.meta());
                    toolMessage.addMetadata("__tool", tool.name());

                    session.addMessage(toolMessage);
                    toolMessages.add(toolMessage);
                } catch (Throwable ex) {
                    throw new ToolCallException("The tool call failed, name: '" + tool + "'", ex);
                }
            } else {
                //会存在调用的call实际上不存在的情况
                log.warn("Tool call not found: {}", call.getName());
            }
        }

        if (toolMessages.size() > 0 && toolMessages.stream().filter(m -> m.isReturnDirect() == false).count() == 0) {
            //说明全部要求直接返回
            return toolMessages;
        } else {
            return null;
        }
    }

    /**
     * 执行工具调用（支持拦截器）
     */
    private ToolResult doToolCall(ChatResponseDefault resp, FunctionTool func, Map<String, Object> args) throws Throwable {
        //收集拦截器
        ToolRequest req = new ToolRequest(resp.getRequest(), resp.getOptions().toolContext(), args);

        //构建请求数据
        ToolChain chain = new ToolChain(options.interceptors(), func);

        return chain.doIntercept(req);
    }
}