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
package features.ai.ui.aisdk;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.ui.aisdk.AiSdkStreamWrapper;
import org.noear.solon.web.sse.SseEvent;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件流到 AI SDK 协议的映射
 *
 * <p>重点锁定工具调用通道：核心把「分片」表达为 TOOL_CALL_START + ARGS_DELTA*，
 * 把「参数已完整」表达为一个 TOOL_CALL_END；映射结果必须是
 * tool-input-start 一次 + tool-input-delta 逐片 + tool-input-available 一次，
 * 不允许每个分片各发一遍 start/available。</p>
 *
 * @author noear
 */
public class AiSdkEventMappingTest {
    private static List<String> typesOf(Flux<SseEvent> flux) {
        List<String> types = new ArrayList<>();

        for (SseEvent e : flux.collectList().block()) {
            //SseEvent 只暴露 toString（完整 SSE 报文），从中取 {"type":"xxx"
            String data = e.toString();
            if (data == null) {
                continue;
            }
            int at = data.indexOf("\"type\":\"");
            if (at < 0) {
                continue;
            }
            int from = at + 8;
            int to = data.indexOf('"', from);
            types.add(data.substring(from, to));
        }

        return types;
    }

    private static ChatEvent argsDelta(String id, String text) {
        return ChatEventDefault.of(ChatEventType.TOOL_CALL_ARGS_DELTA)
                .toolCallId(id)
                .toolCall(newCall(id, "get_weather", text))
                .text(text)
                .build();
    }

    private static ToolCall newCall(String id, String name, String args) {
        return new ToolCall("0", id, name, args, new LinkedHashMap<>());
    }

    /**
     * 分片流：start 一次、delta 逐片、available 一次
     */
    @Test
    public void toolCallDeltasMapToSingleStartAndAvailable() {
        Flux<ChatEvent> events = Flux.just(
                ChatEventDefault.of(ChatEventType.TOOL_CALL_START)
                        .toolCallId("call_1")
                        .toolCall(newCall("call_1", "get_weather", null))
                        .build(),
                argsDelta("call_1", "{\"city\""),
                argsDelta("call_1", ":\"杭州\"}"),
                ChatEventDefault.of(ChatEventType.TOOL_CALL_END)
                        .toolCallId("call_1")
                        .toolCall(newCall("call_1", "get_weather", "{\"city\":\"杭州\"}"))
                        .build(),
                ChatEventDefault.of(ChatEventType.TOOL_RESULT)
                        .toolCallId("call_1")
                        .toolCall(newCall("call_1", "get_weather", "{\"city\":\"杭州\"}"))
                        .text("晴，24度")
                        .build());

        List<String> types = typesOf(AiSdkStreamWrapper.of().toAiSdkStream(events));

        assertEquals(1, count(types, "tool-input-start"), types.toString());
        assertEquals(2, count(types, "tool-input-delta"), types.toString());
        assertEquals(1, count(types, "tool-input-available"), types.toString());
        assertEquals(1, count(types, "tool-output-available"), types.toString());

        //顺序：start 先于 delta，delta 先于 available，available 先于 output
        assertTrue(types.indexOf("tool-input-start") < types.indexOf("tool-input-delta"), types.toString());
        assertTrue(types.lastIndexOf("tool-input-delta") < types.indexOf("tool-input-available"), types.toString());
        assertTrue(types.indexOf("tool-input-available") < types.indexOf("tool-output-available"), types.toString());
    }

    /**
     * 整块方言（只给完整调用，无增量）：仍补齐 start + delta + available
     */
    @Test
    public void wholeToolCallStillGetsStartAndDelta() {
        Flux<ChatEvent> events = Flux.just(
                ChatEventDefault.of(ChatEventType.TOOL_CALL_CHUNK)
                        .toolCallId("call_9")
                        .toolCall(newCall("call_9", "get_rainfall", "{\"city\":\"北京\"}"))
                        .build());

        List<String> types = typesOf(AiSdkStreamWrapper.of().toAiSdkStream(events));

        assertEquals(1, count(types, "tool-input-start"), types.toString());
        assertEquals(1, count(types, "tool-input-delta"), types.toString());
        assertEquals(1, count(types, "tool-input-available"), types.toString());
    }

    /**
     * 生命周期与正文：start / text / finish 的基本映射不被工具通道改动影响
     */
    @Test
    public void lifecycleAndTextMapping() {
        Flux<ChatEvent> events = Flux.just(
                ChatEventDefault.of(ChatEventType.RESPONSE_START).build(),
                ChatEventDefault.of(ChatEventType.STEP_START).build(),
                ChatEventDefault.of(ChatEventType.TEXT_START).build(),
                ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("你好").build(),
                ChatEventDefault.of(ChatEventType.TEXT_END).build(),
                ChatEventDefault.of(ChatEventType.STEP_END).build(),
                ChatEventDefault.of(ChatEventType.RESPONSE_END).build());

        List<String> types = typesOf(AiSdkStreamWrapper.of().toAiSdkStream(events));

        assertEquals(1, count(types, "start-step"), types.toString());
        assertEquals(1, count(types, "finish-step"), types.toString());
        assertEquals(1, count(types, "text-start"), types.toString());
        assertEquals(1, count(types, "text-delta"), types.toString());
        assertEquals(1, count(types, "text-end"), types.toString());
        assertEquals(1, count(types, "finish"), types.toString());
    }

    private static long count(List<String> list, String value) {
        return list.stream().filter(value::equals).count();
    }

    private static String joinAll(Flux<org.noear.solon.web.sse.SseEvent> flux) {
        StringBuilder buf = new StringBuilder();
        for (org.noear.solon.web.sse.SseEvent e : flux.collectList().block()) {
            buf.append(e.toString()).append('\n');
        }
        return buf.toString();
    }

    /**
     * 流失败（无 ERROR 事件，直接 Flux.error）：所有已开启的 start 都必须有对应 end，
     * 且序列以 error + finish 收尾，不能出现悬空 part
     */
    @Test
    public void failedStreamStillClosesOpenParts() {
        Flux<ChatEvent> events = Flux.just(
                        ChatEventDefault.of(ChatEventType.STEP_START).build(),
                        ChatEventDefault.of(ChatEventType.THINKING_START).build(),
                        ChatEventDefault.of(ChatEventType.THINKING_DELTA).text("思考中").build(),
                        ChatEventDefault.of(ChatEventType.TEXT_START).build(),
                        ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("部分输出").build())
                .concatWith(Flux.error(new RuntimeException("upstream broke")));

        List<String> types = typesOf(AiSdkStreamWrapper.of().toAiSdkStream(events));

        //错误后仍要收口：start 与 end 一一配对，不允许悬挂
        assertEquals(count(types, "text-start"), count(types, "text-end"), types.toString());
        assertEquals(count(types, "reasoning-start"), count(types, "reasoning-end"), types.toString());
        assertEquals(1, count(types, "error"), types.toString());
        assertEquals(1, count(types, "finish"), types.toString());

        //顺序：end 在 error 之前，finish 是最后一个协议 part
        assertTrue(types.lastIndexOf("text-end") < types.indexOf("error"), types.toString());
        assertTrue(types.lastIndexOf("reasoning-end") < types.indexOf("error"), types.toString());
        assertEquals("finish", types.get(types.size() - 1), types.toString());

        //错误文案与终止原因落地
        String all = joinAll(AiSdkStreamWrapper.of().toAiSdkStream(events));
        assertTrue(all.contains("upstream broke"), all);
        assertTrue(all.contains("\"finishReason\":\"error\""), all);
    }

    /**
     * RAW 只有在调用方显式提供事件时才映射为 data-*，且原始事件名与载荷均保留。
     */
    @Test
    public void rawEventMapsToDataPart() {
        Flux<ChatEvent> events = Flux.just(
                ChatEventDefault.of(ChatEventType.RAW)
                        .rawType("provider.new_event")
                        .raw(ONode.ofJson("{\"value\":1}"))
                        .build());

        String all = joinAll(AiSdkStreamWrapper.of().toAiSdkStream(events));

        assertTrue(all.contains("data-provider.new_event"), all);
        assertTrue(all.contains("\\\"value\\\":1"), all);
    }

    /**
     * 核心失败路径（先发 ERROR 事件再 Flux.error）：error part 只发一次，
     * 且优先用事件携带的 error 文案而非裸 Throwable
     */
    @Test
    public void errorEventPreferredOverThrowable() {
        Flux<ChatEvent> events = Flux.just(
                        ChatEventDefault.of(ChatEventType.TEXT_START).build(),
                        ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("前半段").build(),
                        ChatEventDefault.of(ChatEventType.ERROR)
                                .error(new ChatException("模型侧超时"))
                                .build())
                .concatWith(Flux.error(new RuntimeException("upstream broke")));

        List<String> types = typesOf(AiSdkStreamWrapper.of().toAiSdkStream(events));

        assertEquals(1, count(types, "error"), types.toString());
        assertEquals(1, count(types, "text-start"), types.toString());
        assertEquals(1, count(types, "text-end"), types.toString());
        assertEquals("finish", types.get(types.size() - 1), types.toString());

        String all = joinAll(AiSdkStreamWrapper.of().toAiSdkStream(events));
        assertTrue(all.contains("模型侧超时"), all);
        //裸 Throwable 文案不应再重复出现
        assertTrue(!all.contains("upstream broke"), all);
    }
}
