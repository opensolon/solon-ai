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
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.talent.TalentUtil;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.noear.solon.net.http.textstream.TextStreamUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
    private ChatEventFilter eventFilter = ChatEventFilter.DEFAULT;

    public ChatRequestDescDefault(ChatConfig config, ChatDialect dialect, ChatSession session, Prompt prompt) {
        this.config = config;
        this.dialect = dialect;
        this.session = session;
        this.originalPrompt = prompt;

        this.options = config.getModelOptions().copy();
    }

    public ChatRequestDesc session(ChatSession session) {
        this.session = session;
        return this;
    }

    /**
     * 角色
     *
     * @since 4.0.4
     */
    @Override
    public ChatRequestDesc role(String role) {
        if (options != null) {
            options.role(role);
        }

        return this;
    }

    /**
     * 指令
     *
     * @since 4.0.4
     */
    @Override
    public ChatRequestDesc instruction(String instruction) {
        if (options != null) {
            options.instruction(instruction);
        }

        return this;
    }

    /**
     * 系统提示词
     *
     * @since 4.0.4
     */
    @Override
    public ChatRequestDesc systemPrompt(String systemPrompt) {
        if (options != null) {
            options.systemPrompt(systemPrompt);
        }

        return this;
    }

    /**
     * 选项设置
     *
     * @param options 选项
     * @deprecated 4.0.4
     */
    @Deprecated
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
                // 先补 sessionId 再入会话：判空写反过会让 else 分支解引用 null（当前调用方保证非空故未触发）
                originalPrompt.attrs().computeIfAbsent(ChatSession.ATTR_SESSIONID,
                        k -> session.getSessionId());
                session.addMessage(originalPrompt);
            }

            // 如果没有 sessionId 则推入
            options.toolContext().computeIfAbsent(ChatSession.ATTR_SESSIONID,
                    k -> session.getSessionId());

            //---

            StringBuilder instructionBuilder = new StringBuilder();

            if (Assert.isNotEmpty(options.systemPrompt())) {
                //如果有系统提示词（优先用）
                instructionBuilder.append(options.systemPrompt()).append("\n\n");
            } else {
                //如果没有尝试结构化构建
                if (Assert.isNotEmpty(options.role())) {
                    instructionBuilder.append("## 你的角色\n").append(options.role()).append("\n\n");
                }

                if (Assert.isNotEmpty(options.instruction())) {
                    instructionBuilder.append("## 执行指令\n").append(options.instruction()).append("\n");
                }
            }

            if(Assert.isNotEmpty(options.outputSchema())) {
                dialect.prepareOutputSchemaInstruction(options.outputSchema(), instructionBuilder);
                dialect.prepareOutputFormatOptions(options);
            }

            if (originalPrompt != null && Assert.isNotEmpty(options.toolContext())) {
                originalPrompt.attrs().putAll(options.toolContext());
            }

            for (RankEntity<ChatInterceptor> item : options.interceptors()) {
                if (item.target.isEnabled()) {
                    item.target.onPrepare(session, options, originalPrompt, instructionBuilder);
                }
            }

            StringBuilder talentsInstruction = TalentUtil.activeTalents(options, originalPrompt, new StringBuilder());
            if (talentsInstruction.length() > 0) {
                instructionBuilder.append("\n");
                instructionBuilder.append(talentsInstruction);
            }

            if (instructionBuilder.length() > 0) {
                systemMessage = ChatMessage.ofSystem(instructionBuilder.toString());
            }
        }
    }

    private AtomicBoolean prepared = new AtomicBoolean(false);
    private SystemMessage systemMessage;

    /**
     * 事件投递过滤器
     *
     * @since 4.1
     */
    @Override
    public ChatRequestDesc eventFilter(ChatEventFilter filter) {
        if (filter != null) {
            this.eventFilter = filter;
        }

        return this;
    }

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
        if(req.getOptions().httpCustomize() != null){
            req.getOptions().httpCustomize().accept(httpUtils);
        }

        String reqJson = req.toRequestData();

        if (log.isDebugEnabled()) {
            log.debug("llm-request[{}]: {}", req.getAgentAndModel(), reqJson);
        }

        String respJson = httpUtils.bodyOfJson(reqJson).post();

        if (log.isDebugEnabled()) {
            log.debug("llm-response[{}]: {}", req.getAgentAndModel(), respJson);
        }

        //与流式对称：响应体不是 JSON 时给出指向配置的错误，而不是抛一个裸 JSON 解析异常
        if (Assert.isNotEmpty(respJson) && isModelFrameShape(respJson) == false) {
            throw new ChatException("LLM response is unrecognizable: not a json body."
                    + " Check the apiUrl and standard/provider config. body: " + abbreviate(respJson));
        }

        ChatAccumulator acc = new ChatAccumulator(req, false);
        acc.setFrameRaw(respJson);
        //非流式也要接 emitter：方言在非流式分支同样会解析出引用 / 服务端工具结果 /
        //思考签名 / 拒答等语义，丢了就是净损失（且无异常无日志）。收集到结果上供 ChatResponse#getEvents 取用。
        dialect.parseResponseJson(newContext(req, acc, null, 0, acc::addEvent), respJson);

        if (acc.getError() != null) {
            throw acc.getError();
        }

        if (acc.hasContentItems()) {
            AssistantMessage itemMessage = acc.lastItem();
            session.addMessage(itemMessage); //添加到记忆

            if (options.isAutoToolCall() && Assert.isNotEmpty(itemMessage.getToolCalls())) {
                List<ToolMessage> returnDirectMessages = buildToolMessage(acc, itemMessage);

                if (Assert.isEmpty(returnDirectMessages)) {
                    //没有直接返回的消息
                    return internalCall();
                } else {
                    //要求直接返回（转为新的响应消息）
                    itemMessage = dialect.buildAssistantMessageByToolMessages(itemMessage, returnDirectMessages);
                    acc.reset();
                    acc.lastFinishReason = "tool";
                    acc.addContentItem(itemMessage);
                    session.addMessage(itemMessage); //添加到记忆
                }
            }
        }

        return acc.snapshotTerminal();
    }

    /**
     * 事件流响应
     *
     * <p>内部只有一条事件流，不存在并行的第二条管道，因此不会出现双源真相漂移。</p>
     *
     * <p><b>终止事件互斥</b>：正常完成发 {@code RESPONSE_END}，失败发 {@code ERROR}，
     * 二者共用同一个门閃——全流恰好一个终止事件。不在失败路径上补 {@code RESPONSE_END}：
     * 那会让「收到 RESPONSE_END 即视为成功」的订阅方静默误判。</p>
     */
    @Override
    public Flux<ChatEvent> stream() {
        // 所有运行时状态都必须在订阅时创建：同一个请求描述返回的 Flux 可以被重复订阅，
        // 不能让上一次订阅的生命周期、归一化状态或终态响应污染下一次订阅。
        final ChatEventFilter filter = ChatEventFilter.guarded(this.eventFilter);

        return Flux.defer(() -> {
            prepare();

            final ChatStreamSession streamSession = new ChatStreamSession();
            final ChatEventNormalizer normalizer = new ChatEventNormalizer();
            final AtomicReference<ChatResponse> lastRespRef = new AtomicReference<>();
            //方言已自行发过 ERROR 时不重复发（但仍要占用终止门閃）
            final AtomicBoolean errorEmitted = new AtomicBoolean(false);

            Flux<ChatEvent> head = Flux.defer(() -> {
                if (streamSession.markResponseStarted()) {
                    return Flux.just((ChatEvent) ChatEventDefault.of(ChatEventType.RESPONSE_START)
                            .responseId(streamSession.getResponseId())
                            .build());
                }
                return Flux.empty();
            });

            Flux<ChatEvent> body = head.concatWith(internalStream(streamSession, lastRespRef))
                    .concatMapIterable(event -> {
                        List<ChatEvent> buf = new ArrayList<>(2);
                        normalizer.apply(event, buf::add);

                        for (ChatEvent e : buf) {
                            if (e.getType() == ChatEventType.ERROR) {
                                errorEmitted.set(true);
                            }
                        }
                        return buf;
                    });

            //异常终止时也要跑收尾：Flux.concat 在 onError 时不会订阅第二个 publisher，
            //单靠 concatWith(tail) 会让失败路径上的块补齐、STEP 配平与终止事件全部丢失。
            return body.onErrorResume(err ->
                            tail(streamSession, normalizer, lastRespRef, errorEmitted, err)
                                    .concatWith(Flux.error(err)))
                    .concatWith(tail(streamSession, normalizer, lastRespRef, errorEmitted, null))
                    .filter(filter::test);
        });
    }

    /**
     * 流收尾（正常完成与异常终止共用）
     *
     * @param err 终止异常；为 null 表示正常完成
     */
    private Flux<ChatEvent> tail(ChatStreamSession streamSession, ChatEventNormalizer normalizer,
                                 AtomicReference<ChatResponse> lastRespRef,
                                 AtomicBoolean errorEmitted, Throwable err) {
        return Flux.defer(() -> {
            List<ChatEvent> buf = new ArrayList<>(4);

            //归一化收尾：补齐未闭合的内容块、工具调用与步骤
            normalizer.complete(buf::add);

            //终止事件全流恰好一个：正常为 RESPONSE_END，失败为 ERROR
            if (streamSession.markResponseEnded()) {
                if (err == null) {
                    buf.add(ChatEventDefault.of(ChatEventType.RESPONSE_END)
                            .responseId(streamSession.getResponseId())
                            .step(streamSession.getStep())
                            .response(lastRespRef.get())
                            .usage(streamSession.getTotalUsage())
                            .build());
                } else if (errorEmitted.compareAndSet(false, true)) {
                    //response 携带已完成部分，便于订阅方打捞部分结果
                    buf.add(ChatEventDefault.of(ChatEventType.ERROR)
                            .responseId(streamSession.getResponseId())
                            .step(streamSession.getStep())
                            .error(err instanceof ChatException
                                    ? (ChatException) err : new ChatException(err))
                            .response(lastRespRef.get())
                            .usage(streamSession.getTotalUsage())
                            .build());
                }
            }

            return Flux.fromIterable(buf);
        });
    }

    /**
     * 校验流响应的内容类型
     *
     * <p>不做白名单（各网关会给出各种合法变体），只拦「绝不可能是模型流」的几种：
     * HTML/XML 页面。这类响应几乎必定是 apiUrl 指错后命中了网关首页或错误页，
     * 而部分网关对未知路径返回的是 200，不能靠状态码发现。</p>
     *
     * @return 错误描述；为 null 表示通过
     */
    private static String checkStreamMimeType(String contentType) {
        if (Assert.isEmpty(contentType)) {
            return null;
        }

        String mime = contentType.toLowerCase().trim();
        if (mime.startsWith("text/html") || mime.startsWith("text/xml")
                || mime.startsWith("application/xml") || mime.startsWith("application/xhtml")) {
            return "LLM stream response content-type is unexpected: " + contentType
                    + " (expect event-stream or json). Check the apiUrl config.";
        }

        return null;
    }

    /**
     * 是否形似模型帧
     *
     * <p>纯语法形状判定，不涉及方言语义：模型流的数据帧要么是 JSON，要么是
     * {@code [DONE]} 这类终止标记。SSE 的 {@code event:}/{@code id:}/注释行合法但不计入——
     * 只要整个响应体里至少有一帧形似模型帧，就不会误判。</p>
     */
    private static boolean isModelFrameShape(String data) {
        String s = data.trim();

        if (s.startsWith("data:")) {
            s = s.substring(5).trim();
        }

        if (s.isEmpty()) {
            return false;
        }

        return s.charAt(0) == '{' || s.charAt(0) == '[';
    }

    /**
     * 截断过长文本（用于错误消息，避免把整个 HTML 页面带进异常）
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }

        String s = text.trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private Flux<ChatEvent> internalStream(ChatStreamSession streamSession,
                                           AtomicReference<ChatResponse> lastRespRef) {
        //构建请求数据（每次请求重新构建 finalPrompt）
        ChatRequest req = new ChatRequest(config, dialect, options, session, systemMessage, originalPrompt, true);

        StreamChain chain = new StreamChain(options.interceptors(),
                r -> doStream(r, streamSession, lastRespRef));

        return chain.doIntercept(req)
                .timeout(config.getTimeout())
                .doOnError(e -> {
                    if (e instanceof TimeoutException) {
                        log.error("LLM stream request timeout!");
                    }
                });
    }

    /**
     * 创建方言解析上下文
     */
    private ChatStreamContext newContext(ChatRequest req, ChatAccumulator acc,
                                         ChatStreamSession streamSession, int step, ChatEventEmitter emitter) {
        return new ChatStreamContextDefault(config, req, acc, streamSession, step, emitter);
    }

    /**
     * 流响应
     */
    private Flux<ChatEvent> doStream(ChatRequest req, ChatStreamSession streamSession,
                                     AtomicReference<ChatResponse> lastRespRef) {
        HttpUtils httpUtils = dialect.createHttpUtils(config, req.isStream());
        if(req.getOptions().httpCustomize() != null){
            req.getOptions().httpCustomize().accept(httpUtils);
        }

        String reqJson = req.toRequestData();

        if (log.isDebugEnabled()) {
            log.debug("llm-request[{}]: {}", req.getAgentAndModel(), reqJson);
        }

        return Mono.fromFuture(httpUtils.bodyOfJson(reqJson).execAsync("POST"))
                .flatMapMany(resp -> {
                    try {
                        if (resp.code() < 400) {
                            return parseResp(req, resp, streamSession, lastRespRef);
                        } else {
                            return Flux.error(resp.createError());
                        }
                    } catch (Throwable e) {
                        return Flux.error(e);
                    }
                });

    }

    private Flux<ChatEvent> parseResp(ChatRequest req, HttpResponse httpResp, ChatStreamSession streamSession,
                                      AtomicReference<ChatResponse> lastRespRef) throws IOException {
        ChatAccumulator acc = new ChatAccumulator(req, true);
        String contentType = httpResp.header("Content-Type");

        //守卫一（HTTP 边界）：内容类型根本不可能是模型流，立即失败
        //典型场景：apiUrl 指错，命中网关首页/错误页，而网关以 200 + text/html 返回
        String mimeErr = checkStreamMimeType(contentType);
        if (mimeErr != null) {
            return Flux.error(new ChatException(mimeErr));
        }

        return Flux.<ChatEvent>create(sink -> {
            final int step = streamSession.nextStep();

            // 方言 SPI 契约（4.1 起）：内容主干（正文 / 思考 / 工具调用）一律由方言写入内容项，
            // 核心统一转换为 TEXT_*/THINKING_*/TOOL_CALL_* 事件；方言直接发射的事件仅限
            // 旁路与元数据（ERROR / HEARTBEAT / CITATION / SERVER_TOOL_* / THINKING_SIGNATURE 等）。
            // 内容因此只有一条转换路径，不存在第二套「方言自产主干事件」的并行真源。
            final ChatStreamContext ctx = newContext(req, acc, streamSession, step, sink::next);

            //本步收到的非空帧数，以及其中「形似模型帧」的帧数（守卫二用，见 onComplete）
            final AtomicInteger frameCount = new AtomicInteger();
            final AtomicInteger modelFrameCount = new AtomicInteger();

            sink.next(ChatEventDefault.of(ChatEventType.STEP_START)
                    .responseId(streamSession.getResponseId())
                    .step(step)
                    .build());

            Flux<?> source = (contentType != null && contentType.startsWith(MimeType.TEXT_EVENT_STREAM_VALUE))
                    ? TextStreamUtil.parseSseStream(httpResp)
                    : TextStreamUtil.parseLineStream(httpResp);

            // 用 CompositeDisposable 统一管理本轮 SSE 订阅与 tool 递归流订阅。
            // FluxSink.onDispose 只能注册一次；第二次会立刻 dispose 新订阅，
            // 导致第二次 internalStream 的 Mono.fromFuture 在 future.complete 后因 cancelled 丢弃回调。
            final Disposable.Composite resources = Disposables.composite();
            final AtomicReference<Disposable> sourceRef = new AtomicReference<>();

            Disposable sourceDisposable = source.subscribe(
                    data -> {
                        // [对接点]：检查 sink 状态，如果已经完成或取消，不再处理
                        if (sink.isCancelled() == false) {
                            try {
                                ServerSentEvent sse = (data instanceof ServerSentEvent)
                                        ? (ServerSentEvent) data : new ServerSentEvent(null, (String) data);

                                // [对接点]：利用 onEventStream 的返回值
                                if (!onEventStream(ctx, sse, sink, frameCount, modelFrameCount)) {
                                    // 返回 false 说明内部要求终止（如报错或逻辑中断）
                                    Disposable d = sourceRef.get();
                                    if (d != null) {
                                        d.dispose();
                                    }
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
                                //守卫二（响应体边界）：收到了内容，但没有一帧形似模型帧
                                //→ 响应体不是模型流，不能当成「正常的空流」静默完成
                                if (frameCount.get() > 0 && modelFrameCount.get() == 0) {
                                    sink.error(new ChatException("LLM stream response is unrecognizable:"
                                            + " no model frame in " + frameCount.get() + " frame(s)."
                                            + " Check the apiUrl and standard/provider config. last frame: "
                                            + abbreviate(acc.getFrameRaw())));
                                    return;
                                }

                                onEventEnd(ctx, sink, resources, streamSession, lastRespRef);
                            } catch (Throwable e) {
                                sink.error(e);
                            }
                        }
                    }
            );

            sourceRef.set(sourceDisposable);
            resources.add(sourceDisposable);
            sink.onDispose(resources);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private void onEventEnd(ChatStreamContext ctx, FluxSink<ChatEvent> sink, Disposable.Composite resources,
                            ChatStreamSession streamSession, AtomicReference<ChatResponse> lastRespRef) {
        ChatAccumulator acc = ctx.getAccumulator();

        // 流结束时思考仍未闭合（模型整轮只吐 reasoning，既无正文也无 tool_calls）：
        // 补一帧 `</think>`，保证聚合文本良构（事件边界另由 ChatEventNormalizer 保证）。
        if (acc.in_thinking) {
            acc.in_thinking = false;
            acc.reset();
            AssistantMessage item = new AssistantMessage("", "", true)
                    .reasoningFieldName(acc.reasoning_field_name);
            acc.addContentItem(item);
            publishItem(sink, ctx, item);
        }

        boolean memoryWritten = false;

        if (acc.getToolCallBuilders().size() > 0) {
            ToolCallOutcome outcome = buildStreamToolCallMessage(ctx, sink, resources, streamSession, lastRespRef);

            if (outcome == ToolCallOutcome.RECURSED) {
                return; // 进入了内部递归流处理，不执行 complete
            }

            memoryWritten = (outcome == ToolCallOutcome.COMPLETE_MEMORY_WRITTEN);
        }

        //添加到记忆（最后的聚合消息）
        if (memoryWritten == false) {
            AssistantMessage aggregationMessage = acc.snapshotTerminal().getMessage();
            if (aggregationMessage != null) {
                session.addMessage(aggregationMessage);
            }
        }

        emitStepEnd(ctx, sink, streamSession, lastRespRef);

        sink.complete();
    }

    /**
     * 发射本步结束事件（携带本步的不可变分步聚合）
     *
     * <p>用终态形态：{@code STEP_END} 与 {@code RESPONSE_END} 的 {@code getResponse().getMessage()}
     * 直接就是完整聚合，与非流式 {@code call()} 一致。</p>
     */
    private void emitStepEnd(ChatStreamContext ctx, FluxSink<ChatEvent> sink,
                             ChatStreamSession streamSession,
                             AtomicReference<ChatResponse> lastRespRef) {
        ChatAccumulator acc = ctx.getAccumulator();
        ChatResponse stepSnapshot = acc.snapshotTerminal();

        lastRespRef.set(stepSnapshot);
        streamSession.accumulateUsage(acc.getUsage());

        sink.next(ctx.event(ChatEventType.STEP_END)
                .response(stepSnapshot)
                .usage(acc.getUsage())
                .build());
    }

    /**
     * @return 是否结束流
     */
    private boolean onEventStream(ChatStreamContext ctx, ServerSentEvent event, FluxSink<ChatEvent> sink,
                                  AtomicInteger frameCount,
                                  AtomicInteger modelFrameCount) {
        ChatAccumulator acc = ctx.getAccumulator();

        if (log.isDebugEnabled()) {
            log.debug("llm-response[{}]: {}", acc.getRequest().getAgentAndModel(), event.getData());
        }

        acc.setFrameRaw(event.getData());

        if (Assert.isEmpty(event.getData())) {
            return true;
        }

        frameCount.incrementAndGet();
        if (isModelFrameShape(event.getData())) {
            modelFrameCount.incrementAndGet();
        }

        acc.reset();

        dialect.parseResponseJson(ctx, event.getData());

        if (acc.getError() != null) {
            sink.error(acc.getError());
            return false;
        }

        if (acc.hasContentItems()) {
            AssistantMessage itemMessage = acc.lastItem();
            if (itemMessage != null && Assert.isNotEmpty(itemMessage.getToolCalls())) {
                buildToolCallBuilder(acc, itemMessage);
            }

            // 拆分内容项并在当前 Sink 中发射
            List<AssistantMessage> items = new ArrayList<>(acc.getContentItems());
            for (AssistantMessage item : items) {
                acc.reset();
                acc.addContentItem(item);
                publishItem(sink, ctx, item);
            }
        } else if (Utils.isNotEmpty(acc.getMediaBlocks())) {
            // 流式仅 media（如 image_generation_call.done）：无内容项也要推一帧
            sink.next(ctx.event(ChatEventType.MEDIA_DONE)
                    .block(acc.getMediaBlocks().isEmpty() ? null : acc.getMediaBlocks().get(acc.getMediaBlocks().size() - 1))
                    .build());
        } else if (acc.getUsage() != null) {
            acc.addContentItem(new AssistantMessage(""));
            sink.next(ctx.event(ChatEventType.USAGE)
                    .usage(acc.getUsage())
                    .response(acc.snapshotFrame())
                    .build());
        }

        return true;
    }

    /**
     * 工具调用组装的处理结果
     *
     * <p>取代原来的 {@code boolean}：它只能表达「是否继续外层收尾」，无法表达「本方法已经
     * 把工具调用消息写进记忆了」，于是关闭自动工具调用时同一条 assistant 会被写两次：
     * 下一轮带两条 {@code tool_calls} 且无对应 tool 消息，OpenAI 端点直接 400。</p>
     */
    private enum ToolCallOutcome {
        /**
         * 本方法未写入记忆的终态消息：外层正常收尾（含写入聚合消息）
         */
        COMPLETE,
        /**
         * 已进入递归流：外层不收尾
         */
        RECURSED,
        /**
         * 终态消息已由本方法写入记忆：外层收尾但不要重复写
         */
        COMPLETE_MEMORY_WRITTEN
    }

    private ToolCallOutcome buildStreamToolCallMessage(ChatStreamContext ctx, FluxSink<ChatEvent> sink,
                                                       Disposable.Composite resources, ChatStreamSession streamSession,
                                                       AtomicReference<ChatResponse> lastRespRef) {
        ChatAccumulator acc = ctx.getAccumulator();

        try {
            ONode oNode = dialect.buildAssistantToolCallMessageNode(acc, acc.getToolCallBuilders());
            List<AssistantMessage> assistantMessages = dialect.parseAssistantMessage(acc, oNode);

            // 如果没有消息，说明工具调用解析失败或没有工具需要处理，直接完成
            if (assistantMessages.isEmpty()) {
                log.debug("The tool call resolution result is empty, ending the streaming response");
                return ToolCallOutcome.COMPLETE; //触发外层的完成事件
            }

            session.addMessage(assistantMessages);

            //参数拼接已完成：每个真实工具调用发一个完成信号（在执行之前）
            emitToolCallEnd(ctx, sink, assistantMessages.get(0));

            if (options.isAutoToolCall()) {
                AssistantMessage itemMessage = assistantMessages.get(0);
                //工具执行结果对流可见。
                //注意：递归分支与 returnDirect 分支都要发，且必须在 STEP_END 之前（工具结果属于本步）
                List<ToolMessage> returnDirectMessages = buildToolMessage(acc, itemMessage,
                        (call, tm) -> sink.next(ctx.event(ChatEventType.TOOL_RESULT)
                                .toolCallId(tm.getToolCallId())
                                .toolCall(call)
                                .text(tm.getContent())
                                .build()));

                if (Assert.isEmpty(returnDirectMessages)) {
                    //本步结束（必须在递归产生新的 STEP_START 之前发，以保 STEP 配平）。
                    //先把组装好的完整工具调用装回累积器：分步聚合取 lastItem().getToolCalls()，
                    //若仍停在参数分片状态，从 STEP_END 只能读到最后一个参数分片。
                    acc.reset();
                    acc.lastFinishReason = "tool";
                    acc.addContentItem(itemMessage);

                    emitStepEnd(ctx, sink, streamSession, lastRespRef);

                    // 加入同一个 CompositeDisposable，避免再次 sink.onDispose 导致立即 dispose
                    Disposable disposable = internalStream(streamSession, lastRespRef).subscribe(
                            sink::next,
                            sink::error,
                            sink::complete
                    );
                    resources.add(disposable);

                    return ToolCallOutcome.RECURSED; //不触发外层的完成事件
                } else {
                    //要求直接返回（转为新的响应消息）
                    AssistantMessage message = dialect.buildAssistantMessageByToolMessages(itemMessage, returnDirectMessages);

                    acc.reset();
                    acc.lastFinishReason = "tool";
                    acc.addContentItem(message);
                    publishItem(sink, ctx, message);
                    //这条 returnDirect 合成消息尚未入记忆，交由外层收尾写入
                    return ToolCallOutcome.COMPLETE;
                }
            } else {
                AssistantMessage message = assistantMessages.get(0);
                acc.reset();
                acc.lastFinishReason = "tool";
                acc.addContentItem(message);

                // 关闭自动工具调用时，工具调用交回调用方：此处只更新聚合状态，不再发射事件。
                // 这条组装出来的消息与前面各分片是同一批工具调用，若再走 publishItem 会
                // 重复发一次 TOOL_CALL_START，并把已流式发送过的完整参数再发一次 ARGS_DELTA。
                // 完成信号已由 emitToolCallEnd 发过；工具调用本身通过 STEP_END / RESPONSE_END 交付。
                //
                // 记忆已在上方 session.addMessage(assistantMessages) 写过：外层不能再写，
                // 否则历史里会出现两条同批 tool_calls 的 assistant 消息。
                return ToolCallOutcome.COMPLETE_MEMORY_WRITTEN;
            }

        } finally {
            //用完清掉
            acc.getToolCallBuilders().clear();
        }
    }

    private void publishItem(FluxSink<ChatEvent> sink, ChatStreamContext ctx, AssistantMessage acm) {
        publishItem(sink, ctx, acm, true);
    }

    /**
     * 把一个内容项分片发射为事件
     *
     * <p>过渡期路径：尚未迁移到事件形态的方言仍用内容项表达内容，由此处统一转成
     * TEXT_DELTA / THINKING_DELTA / TOOL_CALL_ARGS_DELTA。增量事件只携带增量负载；
     * 完整响应只出现在 STEP_END / RESPONSE_END。</p>
     *
     * @param aggregateText 是否把该消息的文本/思考计入流式聚合（消息文本本身来自聚合结果时必须传 false）
     */
    private void publishItem(FluxSink<ChatEvent> sink, ChatStreamContext ctx, AssistantMessage acm,
                             boolean aggregateText) {
        ChatAccumulator acc = ctx.getAccumulator();

        if (acm != null) {
            if (aggregateText) {
                acc.appendText(acm.getTextRaw());
                acc.appendThinking(acm.getThinkingRaw());
            }

            // 流式聚合媒体块（文本已走 textBuilder）
            if (acm.hasMedia()) {
                acc.addMediaBlocks(acm.getBlocks());
            }
        }

        emitItemEvents(sink, ctx, acm);
    }

    /**
     * 发射一个内容项分片对应的事件
     *
     * <p>工具调用分片会展开为「首次 START + 每片 ARGS_DELTA」；完成信号（TOOL_CALL_END）
     * 由 {@link #emitToolCallEnd} 在参数拼接完成处发射，因此订阅方看到的「完成」数量
     * 等于真实工具调用数，而不是 SSE 分片数。</p>
     */
    private void emitItemEvents(FluxSink<ChatEvent> sink, ChatStreamContext ctx, AssistantMessage acm) {
        if (acm != null && Assert.isNotEmpty(acm.getToolCalls())) {
            Set<String> started = startedToolCalls(ctx);

            for (ToolCall call : acm.getToolCalls()) {
                String key = (call.getIndex() == null ? call.getId() : call.getIndex());

                if (key == null || started.add(key)) {
                    //该工具调用的首个分片：开始信号（不带快照，不进旧帧投影）
                    sink.next(ctx.event(ChatEventType.TOOL_CALL_START)
                            .toolCall(call)
                            .toolCallId(call.getId())
                            .build());
                }

                sink.next(ctx.event(ChatEventType.TOOL_CALL_ARGS_DELTA)
                        .toolCall(call)
                        .toolCallId(call.getId())
                        .text(call.getArgumentsStr())
                        .build());
            }
            return;
        }

        //媒体：旧路径只把它们归入聚合 blocks，流式订阅方全程看不到图片/音频的到达
        if (acm != null && acm.hasMedia()) {
            for (ContentBlock block : acm.getBlocks()) {
                if (block instanceof TextBlock == false) {
                    sink.next(ctx.event(ChatEventType.MEDIA_DONE)
                            .block(block)
                            .build());
                }
            }
        }

        ChatEvent itemEvent = buildItemEvent(ctx, acm);
        if (itemEvent != null) {
            sink.next(itemEvent);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> startedToolCalls(ChatStreamContext ctx) {
        return (Set<String>) ctx.attrIfAbsent("__startedToolCalls", k -> new LinkedHashSet<String>());
    }

    /**
     * 参数拼接完成：每个真实工具调用发一个完成事件
     *
     * <p>不携带快照：完成信号是事件模型新增的表达，不对应旧帧。</p>
     */
    private void emitToolCallEnd(ChatStreamContext ctx, FluxSink<ChatEvent> sink, AssistantMessage acm) {
        if (acm == null || Assert.isEmpty(acm.getToolCalls())) {
            return;
        }

        for (ToolCall call : acm.getToolCalls()) {
            sink.next(ctx.event(ChatEventType.TOOL_CALL_END)
                    .toolCall(call)
                    .toolCallId(call.getId())
                    .text(call.getArgumentsStr())
                    .build());
        }
    }

    /**
     * 把内容项映射为事件
     *
     * <p><b>纯信号项不产生内容事件</b>（返回 null）：方言经常用一个空
     * {@code AssistantMessage} 当信号载体（思考闭合、finishReason 透传、签名携带、空流补位）。
     * 无条件映射会让这些帧变成 {@code text=""} 的幻影 {@code TEXT_DELTA}，轻则污染正文流，
     * 重则在仅思考的流末尾凭空开出一个正文块（START/DELTA/END 三联）。
     * 这里只判「有无内容」，不判方言意图，因此对所有方言一致生效；
     * 该项仍会正常参与聚合（见 {@link #publishItem}），不影响终态消息。</p>
     */
    private ChatEvent buildItemEvent(ChatStreamContext ctx, AssistantMessage acm) {
        if (acm == null) {
            return null;
        }

        if (acm.isThinking()) {
            if (Assert.isEmpty(acm.getThinkingRaw())) {
                return null;
            }

            return ctx.event(ChatEventType.THINKING_DELTA)
                    .text(acm.getThinkingRaw())
                    .build();
        }

        if (Assert.isEmpty(acm.getTextRaw())) {
            return null;
        }

        return ctx.event(ChatEventType.TEXT_DELTA)
                .text(acm.getTextRaw())
                .build();
    }

    private void buildToolCallBuilder(ChatAccumulator acc, AssistantMessage acm) {
        if (Assert.isEmpty(acm.getToolCalls())) {
            return;
        }

        for (ToolCall call : acm.getToolCalls()) {
            ToolCallBuilder callBuilder = acc.getToolCallBuilders().computeIfAbsent(call.getIndex(), k -> new ToolCallBuilder());

            // id 按官方流式协议仅首分片携带（首片胜出）；
            // 若网关每帧重复下发完整 id，拼接会得到 'call_xxcall_xx' 这类脏值，导致 tool_call_id 对不上
            if (call.getId() != null && callBuilder.idBuilder.length() == 0) {
                callBuilder.idBuilder.append(call.getId());
            }

            // name 保留累积：部分网关/自研端点会把函数名也分片下发，仅跳过完全重复的重发
            if (call.getName() != null) {
                if (callBuilder.nameBuilder.length() == 0) {
                    callBuilder.nameBuilder.append(call.getName());
                } else if (!call.getName().contentEquals(callBuilder.nameBuilder)) {
                    callBuilder.nameBuilder.append(call.getName());
                }
            }

            // arguments 分片只做字符串累积，不在此处校验 JSON
            if (call.getArgumentsStr() != null) {
                callBuilder.argumentsBuilder.append(call.getArgumentsStr());
            }
        }
    }

    /**
     * @return returnDirect
     */
    private List<ToolMessage> buildToolMessage(ChatAccumulator acc, AssistantMessage acm) throws ChatException {
        return buildToolMessage(acc, acm, null);
    }

    /**
     * 执行工具调用并构建工具消息
     *
     * @param observer 每个工具执行完成后的观察者（流式路径用于发射 TOOL_RESULT 事件；非流式传 null）
     * @return returnDirect
     */
    private List<ToolMessage> buildToolMessage(ChatAccumulator acc, AssistantMessage acm,
                                               BiConsumer<ToolCall, ToolMessage> observer) throws ChatException {
        if (Assert.isEmpty(acm.getToolCalls())) {
            return null;
        }

        List<ToolMessage> toolMessages = new ArrayList<>();
        for (ToolCall call : acm.getToolCalls()) {
            FunctionTool tool = options.tool(call.getName());

            if (tool != null) {
                try {
                    ToolResult toolResult = doToolCall(acc, tool, call.getArguments());
                    ToolMessage toolMessage = ChatMessage.ofTool(toolResult, call.getName(), call.getId(), tool.returnDirect());
                    toolMessage.addMetadata(tool.meta());
                    toolMessage.addMetadata("__tool", tool.name());

                    session.addMessage(toolMessage);
                    toolMessages.add(toolMessage);

                    if (observer != null) {
                        observer.accept(call, toolMessage);
                    }
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
    private ToolResult doToolCall(ChatAccumulator acc, FunctionTool func, Map<String, Object> args) throws Throwable {
        //收集拦截器
        ToolRequest req = new ToolRequest(acc.getRequest(), options.toolContext(), args);

        //构建请求数据
        ToolChain chain = new ToolChain(options.interceptors(), func);

        return chain.doIntercept(req);
    }
}
