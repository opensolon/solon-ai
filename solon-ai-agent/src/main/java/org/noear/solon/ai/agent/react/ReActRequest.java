/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentRequest;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * ReAct 模式推理请求
 *
 * <p>采用 Fluent API 风格，封装了单次 Agent 调用的完整参数。
 * 它是线程不安全的，每个请求应由 {@link ReActAgent#prompt(Prompt)} 独立创建。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActRequest implements AgentRequest<ReActRequest, ReActResponse> {
    private static final Logger log = LoggerFactory.getLogger(ReActRequest.class);

    private final ReActAgent agent;
    private final Prompt prompt;
    private AgentSession session;
    private ReActOptions options;
    private Consumer<ReActOptionsAmend> optionsAdjustor;

    public ReActRequest(ReActAgent agent, Prompt prompt) {
        this.agent = agent;
        this.prompt = prompt;
    }

    /**
     * 绑定持久化会话：用于维持长期记忆或多轮对话上下文
     */
    @Override
    public ReActRequest session(AgentSession session) {
        this.session = session;
        return this;
    }

    /**
     * 修改当前请求的运行选项
     */
    public ReActRequest options(Consumer<ReActOptionsAmend> adjustor) {
        if (optionsAdjustor == null) {
            optionsAdjustor = adjustor;
        } else {
            // andThen 返回新 Consumer，必须写回才能链式生效
            optionsAdjustor = optionsAdjustor.andThen(adjustor);
        }

        return this;
    }

    private void init() {
        if (options != null) {
            return; // 已经初始化过了，不再重复逻辑
        }

        if (session == null) {
            if (log.isDebugEnabled()) {
                log.debug("No session provided for ReActRequest, using temporary InMemoryAgentSession.");
            }
            session = InMemoryAgentSession.of();
        }

        ReActTrace trace = agent.getTrace(session);
        if (trace != null && trace.getOptions() != null) {
            // 续跑时从上次 options 拷贝，避免在同一实例上叠加 interceptor/streamSink
            options = trace.getOptions().copy();
        }

        if (options == null) {
            options = agent.getConfig().getDefaultOptions().copy();
        }

        if (optionsAdjustor != null) {
            optionsAdjustor.accept(new ReActOptionsAmend(options));
        }
    }

    /**
     * 执行同步调用：阻塞当前线程直至推理完成或超时
     *
     * <p><b>同样纳入 {@link SessionRuns} 管理</b>：执行期间登记一个「中断当前线程」的句柄，
     * 因此 {@link SessionRuns#cancelAll(AgentSession)} 对同步调用一也可生效。
     * 这里不改成 {@code Mono<ReActResponse>}：{@code call()} 是 {@link AgentRequest} 的同步契约，
     * 改成异步会把 {@code block()} 推回每个宿主，属于无收益的破坏性变更。</p>
     *
     * @return 包含最终答案、执行指标和过程轨迹的响应对象
     */
    @Override
    public ReActResponse call() throws Throwable {
        init();

        // 同步调用没有 Subscription，取消目标只能是执行线程本身。
        // 与 stream() 一致：只负责“让任务停下来”，不在这里做任何收尾（收尾归 agent.call）。
        final AtomicBoolean disposed = new AtomicBoolean(false);
        final Thread worker = Thread.currentThread();
        final Disposable run = new Disposable() {
            @Override
            public void dispose() {
                if (disposed.compareAndSet(false, true)) {
                    worker.interrupt();
                }
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        };

        SessionRuns.attach(session, run);
        try {
            AssistantMessage message = agent.call(prompt, session, options);
            ReActTrace trace = agent.getTrace(session);

            return new ReActResponse(session, trace, message);
        } finally {
            SessionRuns.detach(session, run);
            fireAfterRun();
        }
    }

    /**
     * 响应式流输出：统一推送推理过程中的 AgentEvent（如 ReasonDeltaEvent、ToolCallStartEvent）
     * 适用于 Web 端 SSE 或 WebSocket 实时展示思考过程
     *
     * <p><b>取消句柄由框架自行登记</b>：订阅时自动登记到 {@link SessionRuns}，结束时按值注销。
     * 宿主（CLI / Web / 桌面 / IM）不再需要往 {@code session.attrs()} 里写任何 key，
     * 只需调用 {@link SessionRuns#cancelAll(AgentSession)}（通常经 harness 的 interrupt）即可取消。</p>
     *
     * <p>每次订阅独立登记：同一请求被重复订阅（HITL 续流）时，各条流互不覆盖。</p>
     */
    public Flux<AgentEvent> stream() {
        init();

        // 取消代数基准：在 stream() 创建时取。宿主常把订阅调度到别的线程
        // （.subscribeOn(boundedElastic()).subscribe()），若该窗口内发生取消，
        // 入槽时代数已推进，本流会立即放弃，不会成为一条“取消不掉的幽灵流”。
        // 注意：若调度窗口更早（请求装配完 → 稍后才调度），宿主需自行在调度前
        // 取 SessionRuns.cancelCount() 并在订阅前比对，避免为一个已被取消的轮次开流。
        final int cancelGen = SessionRuns.cancelCount(session);

        // defer：每次订阅都产生独立的句柄持有者，避免同一 ReActRequest 二次订阅互相覆盖
        return Flux.defer(() -> {
            final AtomicReference<Disposable> runRef = new AtomicReference<>();
            final AtomicReference<Thread> workerRef = new AtomicReference<>();
            final AtomicBoolean cancelRequested = new AtomicBoolean(false);

            return Flux.<AgentEvent>create(sink -> {
                try {
                    if (cancelRequested.get()) {
                        // 调度窗口内已被取消：不开流，但必须给出终止信号，
                        // 否则下游 doFinally 不执行，句柄永久残留在登记表里
                        sink.complete();
                        return;
                    }

                    Thread currentThread = Thread.currentThread();
                    workerRef.set(currentThread);
                    sink.onCancel(() -> {
                        // 触发线程中断，强制让正在阻塞的 callWithRetry 抛出异常退出
                        currentThread.interrupt();
                    });


                    options.setStreamSink(sink);
                    AssistantMessage message = agent.call(prompt, session, options);

                    if (cancelRequested.get()) {
                        // 已取消：agent 可能捕获了 InterruptedException 后正常返回（如返回占位文本）。
                        // 此时不得推送 RunEndEvent —— 否则宿主会把占位文本当成“本轮正常答复”落库或外发。
                        // 终止信号仍必须给出，否则下游 doFinally 不执行，句柄永久残留在登记表里。
                        clearInterrupt();
                        sink.complete();
                        return;
                    }

                    ReActTrace trace = agent.getTrace(session);

                    ReActResponse resp = new ReActResponse(session, trace, message);

                    if (sink.isCancelled() == false) {
                        trace.pushAgentEvent(new RunEndEvent(trace, resp));
                        sink.complete();
                    }
                } catch (Throwable e) {
                    // 取消导致的异常不是失败：句柄只打断线程、不 cancel 上游，
                    // 因此 sink.isCancelled() 为 false，中断异常会一路传到这里。
                    // 若直接 sink.error(e)，宿主会把「用户主动停止」呈现成执行失败
                    // （CLI 打印 Error 段落、headless 退出码非 0、Web/桌面补发 error 帧）。
                    // 故取消一律以 complete 收尾，与旧实现「取消不产生 error」的语义对齐。
                    if (cancelRequested.get()) {
                        clearInterrupt();
                        sink.complete();
                    } else if (!sink.isCancelled()) {
                        sink.error(e);
                    }
                } finally {
                    // 兜底：本回合无论以何种方式结束，都不把中断位还给池化线程。
                    // boundedElastic 会复用线程，残留的中断位会让下一个不相干的任务
                    // 在首个阻塞点直接抛 InterruptedException，是最难定位的一类串扰。
                    clearInterrupt();
                }
            })
            .doOnSubscribe(subscription -> {
                // 句柄语义要与使用侧一致：幂等的 dispose + 可信的 isDisposed
                final AtomicBoolean disposed = new AtomicBoolean(false);
                Disposable run = new Disposable() {
                    @Override
                    public void dispose() {
                        if (disposed.compareAndSet(false, true)) {
                            cancelRequested.set(true);

                            // 只打断执行线程，**不** cancel 上游：
                            // call() 返回后 sink.complete() 仍会执行，下游 doFinally 才能收到终止信号。
                            // 若直接 subscription.cancel()，取消只向上传，下游永远等不到结束。
                            Thread worker = workerRef.get();
                            if (worker != null) {
                                worker.interrupt();
                            }
                            // worker 尚未就绪时不在此处取消上游（会断了终止信号），
                            // 由 create 回调入口的 cancelRequested 判定接管
                        }
                    }

                    @Override
                    public boolean isDisposed() {
                        return disposed.get();
                    }
                };
                runRef.set(run);

                if (SessionRuns.attach(session, run, cancelGen) == false) {
                    // 调度窗口期内已被取消：不再执行本次推理
                    run.dispose();
                }
            })
            .doFinally(signal -> {
                // 按值注销，只摘自己那条流
                SessionRuns.detach(session, runRef.get());
                // 注销之后才回调：下游（宿主）的 doFinally 先于这里执行，那时运行句柄仍在登记表里，
                // 会话仍算忙；「本轮结束后接着做」的动作（如续发积压）只能放在这里
                fireAfterRun();
            });
        });
    }

    /**
     * 一次运行结束、且运行句柄已从 {@link SessionRuns} 注销之后的回调点（默认空操作）。
     *
     * <p>{@link #stream()} 每次订阅终止（完成 / 出错 / 取消）回调一次，{@link #call()} 每次返回或抛出后回调一次。
     * 回调时本轮已不再计入会话的运行流，因此子类可在这里做「会话空闲后才允许」的事。</p>
     *
     * <p>为什么不能交给下游 {@code doFinally}：Reactor 正常终止时下游的 {@code doFinally} 先于上游执行，
     * 宿主在自己的 {@code doFinally} 里看到的会话一定还忙。</p>
     *
     * <p>回调抛出的异常只记日志，不影响终止信号与句柄注销。</p>
     *
     * @since 4.2.0
     */
    protected void afterRun() {
    }

    private void fireAfterRun() {
        try {
            afterRun();
        } catch (Throwable e) {
            log.warn("ReActRequest afterRun failed", e);
        }
    }

    /**
     * 清除当前线程的中断位。
     *
     * <p>取消的实现是「中断 worker 线程」，而池化线程（boundedElastic）会复用：
     * 中断位一旦残留，下一个毫不相干的任务会在首个阻塞点（IO、sleep、锁）直接抛
     * {@code InterruptedException}，表现为随机、不可复现的失败。
     * 因此本回合结束前必须把它清掉。</p>
     *
     * <p>调用点都在 worker 线程上（create 回调与 sink 终止信号同一线程），
     * 所以这里不需要再比对线程身份。</p>
     */
    private static void clearInterrupt() {
        // Thread.interrupted() 读取并清除当前线程的中断标志（返回旧值，此处不需要）
        Thread.interrupted();
    }
}