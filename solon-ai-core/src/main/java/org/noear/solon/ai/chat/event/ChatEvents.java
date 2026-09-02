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
package org.noear.solon.ai.chat.event;

import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天事件工具
 *
 * <p>只提供纯函数式的归约与投影，不提供回调容器：事件流的唯一订阅面是
 * {@code Flux<ChatEvent>} 本身，标准 Reactor 操作符（filter / buffer / window /
 * timeout / publishOn / retryWhen）全部可用。</p>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
public final class ChatEvents {
    private ChatEvents() {
    }

    /**
     * 归约为终态响应（阻塞）
     *
     * <p>取 {@link ChatEventType#RESPONSE_END} 携带的不可变聚合。相比旧的
     * {@code stream().blockLast()}，终态不再依赖「最后一帧碰巧攒够了内容」，而是契约保证。</p>
     *
     * @param flux 事件流
     * @return 终态响应；流中无 RESPONSE_END 时返回 null
     */
    public static ChatResponse reduce(Flux<ChatEvent> flux) {
        return reduceAsync(flux).block();
    }

    /**
     * 归约为终态响应（异步）
     *
     * <p>正常完成优先取 {@link ChatEventType#RESPONSE_END}；若被主动截断，则仅回落到最后一个
     * {@link ChatEventType#STEP_END}。普通 DELTA 即使第三方方言错误地携带 response，也不会被当成终态。</p>
     *
     * <p><b>终态之后不再改变归约结果</b>：核心保证 {@code RESPONSE_END} 全流恰好一次且为最后一个
     * 携带聚合的事件，但归约不依赖上游守规矩：一旦取到终态，后续事件（拦截器补发的重复
     * {@code RESPONSE_END}、迟到的 {@code STEP_END}、第三方方言的尾巽帧）一律只透传不参与归约。</p>
     *
     * @param flux 事件流
     */
    public static Mono<ChatResponse> reduceAsync(Flux<ChatEvent> flux) {
        return Mono.defer(() -> {
            AtomicReference<ChatResponse> terminal = new AtomicReference<>();
            AtomicReference<ChatResponse> stepFallback = new AtomicReference<>();

            return flux.doOnNext(event -> {
                        //终态已定：后续事件只透传，不再影响归约结果
                        if (terminal.get() != null) {
                            return;
                        }

                        if (event == null || event.getResponse() == null) {
                            return;
                        }

                        if (event.getType() == ChatEventType.RESPONSE_END) {
                            terminal.set(event.getResponse());
                        } else if (event.getType() == ChatEventType.STEP_END) {
                            stepFallback.set(event.getResponse());
                        }
                    })
                    .then(Mono.defer(() -> {
                        ChatResponse response = terminal.get();
                        if (response == null) {
                            response = stepFallback.get();
                        }
                        return Mono.justOrEmpty(response);
                    }));
        });
    }
}
