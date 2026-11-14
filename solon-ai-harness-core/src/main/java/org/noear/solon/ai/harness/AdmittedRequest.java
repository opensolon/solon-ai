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
package org.noear.solon.ai.harness;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Nullable;
import reactor.core.publisher.Flux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 携带受理占位的请求（包内）：引擎装配的每个请求都是它。
 *
 * <p>占位跟着请求走，宿主不接触凭据：</p>
 * <ul>
 *     <li>{@link #stream()}：订阅信号自上游向下游传递，父类登记运行流（{@code SessionRuns.attach}）
 *         一定先于这里的释放——「已释放、未登记」的空档不存在，宿主把订阅调度到别的线程也安全；
 *         {@code doFinally} 再释放一次兜底（幂等）；</li>
 *     <li>{@link #call()}：执行结束释放（执行期间同时算受理中与忙，两者都是占用，无副作用）；</li>
 *     <li>宿主拿到 TASK 决定不执行时经 {@code InputOutcome#discard()} 释放；再兜底有中断清除与占位时限。</li>
 * </ul>
 *
 * <p>宿主经 {@code InputOutcome#afterRun(Runnable)} 登记的回调在父类注销运行句柄之后执行
 * （见 {@link ReActRequest#afterRun()}）：那时会话已不再因本轮而忙，续发积压此时才判得到空闲。</p>
 *
 * <p><b>引擎级活动</b>（{@code HarnessEngine#isQuiescent} 据此判断能不能回收）：从受理到本轮的回调执行完，
 * 引擎都不静默——受理之后、开流之前算占位（登记在引擎的受理表里），开流之后算运行计数；
 * 运行计数在回调执行完之后才减。否则引擎会在两个空档里被判为可回收：订阅之前（计数还没加），
 * 以及流结束之后、续发还没开出下一轮之前（外层 {@code doFinally} 先于父类注销运行句柄执行）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class AdmittedRequest extends ReActRequest {
    private static final Logger LOG = LoggerFactory.getLogger(AdmittedRequest.class);

    private final AtomicReference<AdmissionGate.Hold> hold = new AtomicReference<>();
    /** 运行句柄注销后的宿主回调（每次运行结束都执行；通常只有一次运行） */
    private final List<Runnable> afterRunHooks = new CopyOnWriteArrayList<>();
    /** 引擎级运行计数（供 {@code HarnessEngine#isQuiescent} 判定空闲回收） */
    private final AtomicInteger activeRuns;
    /** 引擎级受理表：已受理、尚未开流的请求所持的占位 */
    private final Set<AdmissionGate.Hold> admissions;
    /**
     * 本请求已计入运行计数、回调尚未执行完的流式运行数（每次订阅加一，afterRun 执行完减一）。
     *
     * <p>两条路径必须各自减、不能合并到 {@link #afterRun()}：同步调用时父类的 init 在 try 之外，
     * init 抛异常则 afterRun 不会执行，合并就会漏减（引擎永远不回收）；而 call() 的 finally 里那次减
     * 正好覆盖「占位已释放、call() 未返回」这段窗口，宿主的续发就发生在这里。</p>
     */
    private final AtomicInteger countedStreams = new AtomicInteger();

    AdmittedRequest(ReActAgent agent,
                    @Nullable Prompt prompt,
                    AtomicInteger activeRuns,
                    Set<AdmissionGate.Hold> admissions) {
        super(agent, prompt);
        this.activeRuns = activeRuns;
        this.admissions = admissions;
    }

    /** 挂上受理占位（仅 handle 第 0 步占到时），并登记到引擎的受理表。 */
    void bind(AdmissionGate.Hold hold) {
        // 顺带清掉已失效的登记（宿主拿到 TASK 既不订阅也不 discard、被中断清除或到时限的占位）：
        // 不走引擎池、没人调 isQuiescent 的单引擎部署里，受理表的规模也只等于在途受理数
        admissions.removeIf(h -> h.getAsBoolean() == false);
        admissions.add(hold);
        AdmissionGate.Hold previous = this.hold.getAndSet(hold);
        if (previous != null && previous != hold) {
            admissions.remove(previous);
            previous.close();
        }
    }

    /** 登记运行句柄注销后的回调（须在开流 / 调用前登记）。 */
    void addAfterRun(Runnable hook) {
        if (hook != null) {
            afterRunHooks.add(hook);
        }
    }

    @Override
    protected void afterRun() {
        // 占位此前已在订阅 / 调用结束时释放；再释放一次兜底（幂等），确保回调里会话可受理
        release();
        try {
            for (Runnable hook : afterRunHooks) {
                try {
                    hook.run();
                } catch (Throwable e) {
                    LOG.warn("[Harness] afterRun hook failed", e);
                }
            }
        } finally {
            // 流式运行的计数到这里才减（同步调用由 call() 自己减）：回调里续发出的下一轮已经计上，
            // 引擎不会在「本轮已结束、下一轮还没开出」的空档里被判为静默
            if (decrementIfPositive(countedStreams)) {
                activeRuns.decrementAndGet();
            }
        }
    }

    /** 释放受理占位（幂等）。 */
    void release() {
        AdmissionGate.Hold t = hold.getAndSet(null);
        if (t != null) {
            admissions.remove(t);
            t.close();
        }
    }

    /**
     * 流式执行：订阅时先计数、再释放占位（中间没有「两边都不算」的空档）；计数在 {@link #afterRun()} 里减。
     *
     * <p>父类每次订阅终止（完成 / 出错 / 取消）都回调一次 afterRun，与这里的每次订阅一一对应；
     * 父类的订阅工厂只分配对象，不会在订阅之前失败，所以不存在「计上了却等不到 afterRun」的订阅。</p>
     */
    @Override
    public Flux<AgentEvent> stream() {
        return super.stream()
                .doOnSubscribe(s -> {
                    countedStreams.incrementAndGet();
                    activeRuns.incrementAndGet();
                    release();
                })
                .doFinally(s -> release());
    }

    @Override
    public ReActResponse call() throws Throwable {
        activeRuns.incrementAndGet();
        try {
            return super.call();
        } finally {
            // 父类在返回前已执行 afterRun（回调在计数之内）；init 抛异常时走不到 afterRun，由这里减
            activeRuns.decrementAndGet();
            release();
        }
    }

    private static boolean decrementIfPositive(AtomicInteger counter) {
        while (true) {
            int n = counter.get();
            if (n <= 0) {
                return false;
            }
            if (counter.compareAndSet(n, n - 1)) {
                return true;
            }
        }
    }
}
