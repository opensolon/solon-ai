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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
 * @author noear
 * @since 4.2.0
 */
final class AdmittedRequest extends ReActRequest {
    private static final Logger LOG = LoggerFactory.getLogger(AdmittedRequest.class);

    private volatile AdmissionGate.Hold hold;
    /** 运行句柄注销后的宿主回调（每次运行结束都执行；通常只有一次运行） */
    private final List<Runnable> afterRunHooks = new CopyOnWriteArrayList<>();
    /** 引擎级运行计数（供 {@code HarnessEngine#isQuiescent} 判定空闲回收） */
    private final AtomicInteger activeRuns;

    AdmittedRequest(ReActAgent agent, @Nullable Prompt prompt, AtomicInteger activeRuns) {
        super(agent, prompt);
        this.activeRuns = activeRuns;
    }

    /** 挂上受理占位（仅 handle 第 0 步占到时）。 */
    void bind(AdmissionGate.Hold hold) {
        this.hold = hold;
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
        for (Runnable hook : afterRunHooks) {
            try {
                hook.run();
            } catch (Throwable e) {
                LOG.warn("[Harness] afterRun hook failed", e);
            }
        }
    }

    /** 释放受理占位（幂等）。 */
    void release() {
        AdmissionGate.Hold t = hold;
        if (t != null) {
            hold = null;
            t.close();
        }
    }

    @Override
    public Flux<AgentEvent> stream() {
        final AtomicBoolean counted = new AtomicBoolean();
        return super.stream()
                .doOnSubscribe(s -> {
                    if (counted.compareAndSet(false, true)) {
                        activeRuns.incrementAndGet();
                    }
                    release();
                })
                .doFinally(s -> {
                    if (counted.compareAndSet(true, false)) {
                        activeRuns.decrementAndGet();
                    }
                    release();
                });
    }

    @Override
    public ReActResponse call() throws Throwable {
        activeRuns.incrementAndGet();
        try {
            return super.call();
        } finally {
            activeRuns.decrementAndGet();
            release();
        }
    }
}
