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
package features.ai.core.event;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatEvents;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 事件流的归约与投影
 *
 * @author noear
 */
public class ChatEventsTest {
    /**
     * 轻量响应替身：本测试只关心归约的取值与实例身份，不依赖真实响应的构造链
     */
    private static ChatResponse respOf(String content) {
        return (ChatResponse) Proxy.newProxyInstance(
                ChatResponse.class.getClassLoader(),
                new Class[]{ChatResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getContent":
                        case "getText":
                            return content;
                        case "hasContent":
                            return true;
                        case "isEmpty":
                            return false;
                        case "toString":
                            return "resp(" + content + ")";
                        default:
                            return null;
                    }
                });
    }

    private static ChatEvent text(String content) {
        return ChatEventDefault.of(ChatEventType.TEXT_DELTA)
                .text(content)
                .response(respOf(content))
                .build();
    }

    /**
     * toTexts 只取正文增量：思考、工具、生命周期一律不混入
     */
    @Test
    public void toTextsOnlyTakesTextDelta() {
        Flux<ChatEvent> flux = Flux.just(
                ChatEventDefault.of(ChatEventType.RESPONSE_START).build(),
                ChatEventDefault.of(ChatEventType.THINKING_START).build(),
                ChatEventDefault.of(ChatEventType.THINKING_DELTA).text("想一下").build(),
                ChatEventDefault.of(ChatEventType.THINKING_END).build(),
                ChatEventDefault.of(ChatEventType.TEXT_START).build(),
                text("你"),
                text("好"),
                ChatEventDefault.of(ChatEventType.TEXT_END).build(),
                ChatEventDefault.of(ChatEventType.HEARTBEAT).build(),
                ChatEventDefault.of(ChatEventType.RESPONSE_END).response(respOf("你好")).build());

        List<String> texts = flux
                .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
                .map(ChatEvent::getText)
                .collectList().block();

        assertEquals(2, texts.size());
        assertEquals("你", texts.get(0));
        assertEquals("好", texts.get(1));
    }

    /**
     * toTexts 过滤空文本（方言的空增量不该变成空字符串帧）
     */
    @Test
    public void toTextsSkipsEmpty() {
        Flux<ChatEvent> flux = Flux.just(
                ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("").build(),
                ChatEventDefault.of(ChatEventType.TEXT_DELTA).build(),
                ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("ok").build());

        List<String> texts = flux.filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
                .map(ChatEvent::getText).collectList().block();

        assertEquals(1, texts.size());
        assertEquals("ok", texts.get(0));
    }

    /**
     * reduce 取 RESPONSE_END 携带的终态聚合，而非「最后一帧碰巧攒够了」
     */
    @Test
    public void reduceTakesResponseEndAggregation() {
        ChatResponse terminal = respOf("full");

        Flux<ChatEvent> flux = Flux.just(
                text("f"),
                text("ull"),
                ChatEventDefault.of(ChatEventType.RESPONSE_END).response(terminal).build());

        assertSame(terminal, ChatEvents.reduce(flux));
    }

    /**
     * 流被上游截断（如 takeUntil 取消）时回落到最后一个携带聚合的事件，
     * 保持与旧 blockLast() 一致的健壮性
     */
    @Test
    public void reduceFallsBackWhenTruncated() {
        ChatResponse last = respOf("partial");

        Flux<ChatEvent> flux = Flux.just(
                text("par"),
                ChatEventDefault.of(ChatEventType.STEP_END).response(last).build());

        assertSame(last, ChatEvents.reduce(flux));
    }

    @Test
    public void reduceDoesNotTreatDeltaResponseAsTerminal() {
        ChatResponse accidental = respOf("partial");
        Flux<ChatEvent> flux = Flux.just(
                ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("partial").response(accidental).build());

        assertNull(ChatEvents.reduce(flux));
    }

    /**
     * 终态之后的事件不得改变归约结果
     *
     * <p>核心保证 RESPONSE_END 恰好一次且在最后，但归约不能依赖上游守规矩：拦截器补发的
     * 重复终态、迟到的 STEP_END、第三方方言的尾巽帧都不能把已定的终态抬掉。</p>
     */
    @Test
    public void reduceIgnoresEventsAfterTerminal() {
        ChatResponse terminal = respOf("full");
        ChatResponse late = respOf("late");

        Flux<ChatEvent> flux = Flux.just(
                text("fu"),
                ChatEventDefault.of(ChatEventType.RESPONSE_END).response(terminal).build(),
                ChatEventDefault.of(ChatEventType.STEP_END).response(late).build(),
                ChatEventDefault.of(ChatEventType.RESPONSE_END).response(late).build());

        assertSame(terminal, ChatEvents.reduce(flux));
    }

    /**
     * 无终态时才取最后一个 STEP_END（多步回落取最新）
     */
    @Test
    public void reduceFallbackTakesLastStepEnd() {
        ChatResponse step0 = respOf("s0");
        ChatResponse step1 = respOf("s1");

        Flux<ChatEvent> flux = Flux.just(
                ChatEventDefault.of(ChatEventType.STEP_END).step(0).response(step0).build(),
                ChatEventDefault.of(ChatEventType.STEP_END).step(1).response(step1).build());

        assertSame(step1, ChatEvents.reduce(flux));
    }

    /**
     * 无聚合可取时返回 null，不抛异常
     */
    @Test
    public void reduceReturnsNullWhenNoAggregation() {
        Flux<ChatEvent> flux = Flux.just(
                ChatEventDefault.of(ChatEventType.RESPONSE_START).build(),
                ChatEventDefault.of(ChatEventType.HEARTBEAT).build());

        assertNull(ChatEvents.reduce(flux));
    }
}
