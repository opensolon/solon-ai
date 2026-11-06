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
 * @author noear
 * @since 4.2.0
 */
final class AdmittedRequest extends ReActRequest {
    private volatile InputGate.Ticket ticket;

    AdmittedRequest(ReActAgent agent, @Nullable Prompt prompt) {
        super(agent, prompt);
    }

    /** 挂上受理占位（仅 handle 第 0 步占到时）。 */
    void bind(InputGate.Ticket ticket) {
        this.ticket = ticket;
    }

    /** 释放受理占位（幂等）。 */
    void release() {
        InputGate.Ticket t = ticket;
        if (t != null) {
            ticket = null;
            t.close();
        }
    }

    @Override
    public Flux<AgentEvent> stream() {
        return super.stream()
                .doOnSubscribe(s -> release())
                .doFinally(s -> release());
    }

    @Override
    public ReActResponse call() throws Throwable {
        try {
            return super.call();
        } finally {
            release();
        }
    }
}
