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
package features.ai.agent.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.agent.react.RunEndEvent;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code ReActRequest#stream()} 的取消契约验收。
 *
 * <p><b>本次改造的核心验收标准</b>：宿主只做「订阅」这一个动作，
 * 不写 {@code session.attrs()} 里的任何 key，也必须能被
 * {@link SessionRuns#cancelAll(AgentSession)} 取消。</p>
 *
 * <p>用重写 {@code call(Prompt, AgentSession, ReActOptions)} 的 {@link ReActAgent} 子类来做确定性桩，
 * 而不是 mock {@code ChatModel}：后者的请求装配会绕开常规 mock 点，断言会耦合内部实现。</p>
 */
class ReActRequestCancelTest {

    /**
     * 取一份已补齐默认值（含 name）的配置：builder 的 build() 会补默认名，
     * 而直接 new 出来的配置不会，所以这里借用一次 build()。
     */
    private static org.noear.solon.ai.agent.react.ReActAgentConfig sampleConfig(String name) {
        ReActAgent sample = ReActAgent.of(mock(ChatModel.class)).name(name).build();
        return sample.getConfig();
    }

    /**
     * 恒阻塞的 agent：进入 call 后挂住，直到被中断。
     */
    private static ReActAgent blockingAgent(CountDownLatch entered) {
        return new ReActAgent(sampleConfig("cancel-blocking")) {
            @Override
            protected AssistantMessage call(Prompt prompt, AgentSession session, ReActOptions options) {
                entered.countDown();
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ChatMessage.ofAssistant("interrupted");
            }
        };
    }

    /**
     * 立即完成的 agent。
     */
    /**
     * 立即完成的 agent：走真实 call 流程（trace 需要正常 prepare），只把模型换成桩。
     */
    private static ReActAgent completingAgent() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc request =
                mock(ChatRequestDesc.class);
        ChatResponse response =
                mock(ChatResponse.class);

        when(chatModel.prompt(any(Prompt.class))).thenReturn(request);
        when(request.session(any(org.noear.solon.ai.chat.ChatSession.class))).thenReturn(request);
        when(request.options(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.isEmpty()).thenReturn(false);
        when(response.getMessage()).thenReturn(ChatMessage.ofAssistant("done"));

        return ReActAgent.of(chatModel).name("cancel-completing").build();
    }

    @Test
    @DisplayName("宿主只订阅、不碰 attrs：仍可被 SessionRuns.cancelAll 取消")
    void subscribedStreamIsCancellableWithoutHostAttrs() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch terminated = new CountDownLatch(1);
        ReActAgent agent = blockingAgent(entered);
        AgentSession session = InMemoryAgentSession.of("cancel-acceptance");

        // 宿主侧：只订阅。没有任何 attrs().put("disposable", ...)。
        // 与真实宿主（Web/桌面/CLI）一致：推理在别的线程上跑
        agent.prompt("do something")
                .session(session)
                .stream()
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(s -> terminated.countDown())
                .subscribe(event -> {
                }, error -> {
                });

        assertTrue(entered.await(5, TimeUnit.SECONDS), "推理应已开始");

        // 关键断言：框架已自行登记，宿主无需告知
        assertTrue(SessionRuns.isBusy(session), "订阅后运行句柄应由框架自动登记");
        assertTrue(SessionRuns.activeCount(session) >= 1);

        assertTrue(SessionRuns.cancelAll(session), "自动登记的句柄必须可被取消");
        assertTrue(terminated.await(5, TimeUnit.SECONDS), "取消后流必须终止");
        assertFalse(SessionRuns.isBusy(session), "取消后必须注销句柄");
    }

    @Test
    @DisplayName("流自然结束后自动注销：不残留忙碌状态")
    void runHandleIsReleasedAfterCompletion() throws Exception {
        ReActAgent agent = completingAgent();
        AgentSession session = InMemoryAgentSession.of("cancel-complete");

        agent.prompt("hi")
                .session(session)
                .stream()
                .doOnError(e -> {
                })
                .blockLast(Duration.ofSeconds(10));

        assertFalse(SessionRuns.isBusy(session), "流结束后必须自动注销句柄，否则会话永久误判为忙碌");
        assertFalse(SessionRuns.cancelAll(session), "已结束的会话不应再有可取消的运行流");
    }

    @Test
    @DisplayName("同步 call() 也纳入 SessionRuns：取消可打断阻塞中的执行")
    void syncCallIsCancellable() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        ReActAgent agent = blockingAgent(entered);
        final AgentSession session = InMemoryAgentSession.of("cancel-sync");

        Thread caller = new Thread(() -> {
            try {
                agent.prompt("go").session(session).call();
            } catch (Throwable ignored) {
                // 被取消后的异常是预期路径
            }
        }, "sync-caller");
        caller.start();

        assertTrue(entered.await(5, TimeUnit.SECONDS), "同步推理应已开始");
        assertTrue(SessionRuns.isBusy(session), "同步调用也必须登记运行句柄");

        assertTrue(SessionRuns.cancelAll(session), "同步调用的句柄必须可被取消");
        caller.join(5000);
        assertFalse(caller.isAlive(), "取消后同步调用必须退出");

        assertFalse(SessionRuns.isBusy(session), "同步调用结束后必须注销句柄");
    }

    @Test
    @DisplayName("同一请求二次订阅：各条流独立登记、互不覆盖")
    void repeatedSubscriptionRegistersIndependently() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch firstDone = new CountDownLatch(1);
        ReActAgent agent = blockingAgent(entered);
        AgentSession session = InMemoryAgentSession.of("cancel-repeat");

        org.noear.solon.ai.agent.react.ReActRequest req = agent.prompt("x").session(session);

        req.stream()
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(s -> firstDone.countDown())
                .subscribe(e -> {
                }, e -> {
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        // 同一请求再次订阅（HITL 续流形态）
        req.stream()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(e -> {
                }, e -> {
                });

        assertTrue(SessionRuns.activeCount(session) >= 1, "二次订阅不得让登记表变空");

        SessionRuns.cancelAll(session);
        assertTrue(firstDone.await(5, TimeUnit.SECONDS), "二次订阅后取消仍须生效");
    }

    /**
     * 取消后不向上游抛中断、直接往上抛的 agent（模拟框架内部未捕获 InterruptedException）。
     */
    private static ReActAgent propagatingAgent(CountDownLatch entered) {
        return new ReActAgent(sampleConfig("cancel-propagating")) {
            @Override
            protected AssistantMessage call(Prompt prompt, AgentSession session, ReActOptions options) {
                entered.countDown();
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException("interrupted-in-call", e);
                }
                return ChatMessage.ofAssistant("never");
            }
        };
    }

    /**
     * 抛出异常时<b>保留</b>中断位的 agent。
     *
     * <p>关键点：{@code Thread.sleep} 抛出 {@link InterruptedException} 时会自行清除中断状态，
     * 所以普通“抛异常”的桩测不出中断位残留。这里在 catch 里显式重置后再抛，
     * 才能模拟“agent 内部没清中断位就一路抛出”的真实情况。</p>
     */
    private static ReActAgent interruptPreservingAgent(CountDownLatch entered) {
        return new ReActAgent(sampleConfig("cancel-keep-flag")) {
            @Override
            protected AssistantMessage call(Prompt prompt, AgentSession session, ReActOptions options) {
                entered.countDown();
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted-with-flag", e);
                }
                return ChatMessage.ofAssistant("never");
            }
        };
    }

    @Test
    @DisplayName("取消以 complete 收尾而非 error：宿主不得把主动停止呈现为执行失败")
    void cancelCompletesInsteadOfErroring() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        ReActAgent agent = propagatingAgent(entered);
        AgentSession session = InMemoryAgentSession.of("cancel-signal");

        agent.prompt("go").session(session).stream()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnComplete(completed::countDown)
                .doOnError(error::set)
                .subscribe(e -> {
                }, e -> {
                });

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        SessionRuns.cancelAll(session);

        assertTrue(completed.await(5, TimeUnit.SECONDS), "取消必须给出终止信号（否则下游 doFinally 不执行）");
        assertNull(error.get(),
                "取消不得以 onError 终止：否则 CLI 打印 Error 段落、headless 退出码非 0、Web/桌面补发 error 帧");
    }

    @Test
    @DisplayName("取消后执行线程的中断位被清除：不污染被复用的池化线程")
    void cancelClearsInterruptFlagOnWorkerThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Boolean> flagAtTermination = new AtomicReference<>();
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        ReActAgent agent = blockingAgent(entered);
        AgentSession session = InMemoryAgentSession.of("cancel-interrupt-flag");

        agent.prompt("go").session(session).stream()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(e -> {
                })
                .doOnComplete(() -> {
                    // 终止信号到达时，框架必须已清除中断位：
                    // 宿主的收尾常在此处落库/写快照，带中断位的线程会让文件 IO 抛 ClosedByInterruptException
                    workerRef.set(Thread.currentThread());
                    flagAtTermination.set(Thread.currentThread().isInterrupted());
                    completed.countDown();
                })
                .subscribe(e -> {
                }, e -> {
                });

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        SessionRuns.cancelAll(session);
        assertTrue(completed.await(5, TimeUnit.SECONDS), "取消必须给出终止信号");

        assertFalse(flagAtTermination.get(),
                "取消后不得把中断位留给 worker 线程：boundedElastic 会复用线程，"
                        + "残留标志会让下一个不相干任务在首个阻塞点随机抛 InterruptedException");
        assertFalse(workerRef.get().isInterrupted(), "回合结束后 worker 线程的中断位必须已清除");
    }

    @Test
    @DisplayName("取消经异常路径收尾时也清除中断位：agent 未捕获 InterruptedException 的分支")
    void cancelViaErrorPathAlsoClearsInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Boolean> flagAtTermination = new AtomicReference<>();
        // 保留中断位再抛异常：普通抛异常的桩测不出残留（sleep 会自行清除中断状态）
        ReActAgent agent = interruptPreservingAgent(entered);
        AgentSession session = InMemoryAgentSession.of("cancel-interrupt-flag-error");

        agent.prompt("go").session(session).stream()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnComplete(() -> {
                    flagAtTermination.set(Thread.currentThread().isInterrupted());
                    completed.countDown();
                })
                .subscribe(e -> {
                }, e -> {
                });

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        SessionRuns.cancelAll(session);
        assertTrue(completed.await(5, TimeUnit.SECONDS), "取消必须给出终止信号");

        assertFalse(flagAtTermination.get(),
                "异常路径收尾时也必须清除中断位：宿主的收尾落库/快照写盘会因中断位抛 ClosedByInterruptException");
    }

    @Test
    @DisplayName("取消后不再推送 RunEndEvent：agent 吞掉中断并返回占位文本时不得当成正常答复")
    void cancelDoesNotPushRunEndEvent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger runEndCount = new AtomicInteger();
        ReActAgent agent = blockingAgent(entered);   // 内部吞掉中断并返回 "interrupted"
        AgentSession session = InMemoryAgentSession.of("cancel-no-run-end");

        agent.prompt("go").session(session).stream()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(event -> {
                    if (event instanceof RunEndEvent) {
                        runEndCount.incrementAndGet();
                    }
                })
                .doOnComplete(completed::countDown)
                .subscribe(e -> {
                }, e -> {
                });

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        SessionRuns.cancelAll(session);

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(0, runEndCount.get(),
                "已取消的轮次不得推送 RunEndEvent，否则占位文本会被落库或外发成最终答复");
    }
}
