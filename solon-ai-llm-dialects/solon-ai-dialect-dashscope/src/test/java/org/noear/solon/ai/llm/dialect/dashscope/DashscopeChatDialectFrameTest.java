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
package org.noear.solon.ai.llm.dialect.dashscope;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.event.ChatStreamSession;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 原生流式帧的解析边界：错误帧 / 内容帧 / 结束帧 / 未建模帧 / 快照归一
 *
 * <p>原生帧形态为 {@code {"output":{"choices":[{"finish_reason":..,"message":{..}}]},"usage":{..},"request_id":".."}}，
 * 错误以同级 {@code code}/{@code message} 下发；联网搜索结果在 {@code output.search_info}。</p>
 *
 * @author noear
 */
public class DashscopeChatDialectFrameTest {
    /**
     * 正好达到快照判定阈值的基准正文
     */
    private static final String BASE = "杭州今天天气晴朗";

    private final DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    /**
     * 模拟核心的逐帧驱动：帧前 reset（分片状态每帧独立，finished 等响应级状态保留）
     */
    private ChatAccumulator feed(ChatStreamContext ctx, String data) {
        ChatAccumulator acc = ctx.getAccumulator();
        acc.reset();
        dialect.parseResponseJson(ctx, data);
        return acc;
    }

    private String frame(String messageBody, String finishReason) {
        return "{\"output\":{\"choices\":[{\"finish_reason\":" + finishReason + ","
                + "\"message\":{\"role\":\"assistant\"," + messageBody + "}}]},"
                + "\"request_id\":\"req-1\"}";
    }

    /// ////////////////////////// 结束标记

    /**
     * [DONE] 不是数据结构：补一个空内容项收口，并置完成
     */
    @Test
    public void doneMarkerClosesStep() {
        ChatAccumulator acc = feed(newCtx(), "[DONE]");

        assertTrue(acc.isFinished(), "[DONE] 必须置完成");
        assertEquals(1, acc.getContentItems().size(), "未完成的步需要补空内容项收口");
        assertEquals("", acc.lastItem().getTextRaw());
        assertNull(acc.getError());
        assertTrue(events.isEmpty(), "[DONE] 不产生事件");
    }

    /**
     * 已由 finish_reason 收口时，[DONE] 不再重复补内容项
     */
    @Test
    public void doneMarkerAfterFinishReasonAddsNothing() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"杭州今天晴\"", "\"stop\""));
        assertTrue(ctx.getAccumulator().isFinished());

        ChatAccumulator acc = feed(ctx, "[DONE]");
        assertTrue(acc.getContentItems().isEmpty(), "已完成的步不得再补空内容项");
    }

    /**
     * [DONE] 释放跨帧快照基准：同一上下文的下一步不得继承上一步的累积
     */
    @Test
    public void doneMarkerReleasesSnapshotState() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"" + BASE + "\"", "null"));
        assertEquals("，气温25度", feed(ctx, frame("\"content\":\"" + BASE + "，气温25度\"", "\"stop\""))
                .lastItem().getTextRaw(), "同一步内应按快照截断");

        feed(ctx, "[DONE]");

        //新一步重放同样的首帧：基准已释放，必须原样交付
        assertEquals(BASE, feed(ctx, frame("\"content\":\"" + BASE + "\"", "null"))
                .lastItem().getTextRaw(), "[DONE] 之后不得继承上一步的累积基准");
    }

    /// ////////////////////////// 非数据结构帧

    /**
     * 非对象 JSON（数组 / 标量）：直接忽略，不污染累积器
     */
    @Test
    public void nonObjectFramesAreIgnored() {
        for (String data : new String[]{"[]", "[1,2]", "\"text\"", "123", "true", "null"}) {
            ChatAccumulator acc = feed(newCtx(), data);

            assertFalse(acc.hasContentItems(), "非对象帧不得产出内容项：" + data);
            assertFalse(acc.isFinished(), "非对象帧不得置完成：" + data);
            assertNull(acc.getError(), "非对象帧不得产生错误：" + data);
            assertTrue(events.isEmpty(), "非对象帧不得产生事件：" + data);
        }
    }

    /// ////////////////////////// 错误帧

    /**
     * 错误帧：code + message 拼成异常消息，并带原始节点发 ERROR
     */
    @Test
    public void errorFrameCarriesCodeMessageAndRaw() {
        ChatStreamContext ctx = newCtx();

        ChatAccumulator acc = feed(ctx,
                "{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided.\",\"request_id\":\"req-9\"}");

        assertNotNull(acc.getError());
        assertEquals("InvalidApiKey: Invalid API-key provided.", acc.getError().getMessage());
        assertFalse(acc.hasContentItems(), "错误帧不得产出内容项");

        assertEquals(1, events.size());
        assertSame(ChatEventType.ERROR, events.get(0).getType());
        assertEquals("error", events.get(0).getRawType());
        assertNotNull(events.get(0).getRaw(), "错误事件需带原始帧便于排查");
    }

    /**
     * 空 code（部分端点每帧都带空 code）：不是错误，正文照常解析
     */
    @Test
    public void emptyCodeIsNotAnError() {
        ChatAccumulator acc = feed(newCtx(),
                "{\"code\":\"\",\"output\":{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"杭州今天晴\"}}]}}");

        assertNull(acc.getError(), "空 code 不得判定为错误");
        assertEquals("杭州今天晴", acc.lastItem().getTextRaw());
        assertTrue(acc.isFinished());
        assertTrue(events.isEmpty());
    }

    /// ////////////////////////// 内容帧的附属字段

    /**
     * 模型名与供应商响应标识按帧记录（req-xxx 用于事件预填）
     */
    @Test
    public void modelAndProviderResponseIdAreRecorded() {
        ChatStreamContext ctx = newCtx();

        ChatAccumulator acc = feed(ctx, frame("\"content\":\"杭州\"", "null"));

        assertEquals("qwen-plus", acc.getModel());
        assertEquals("req-1", ctx.getProviderResponseId());
    }

    /**
     * usage：原生字段名（input_tokens / think_tokens / output_tokens / total_tokens）
     */
    @Test
    public void usageIsParsedFromNativeFieldNames() {
        ChatAccumulator acc = feed(newCtx(),
                "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]},"
                        + "\"usage\":{\"input_tokens\":10,\"think_tokens\":3,\"output_tokens\":2,\"total_tokens\":15}}");

        assertNotNull(acc.getUsage());
        assertEquals(10L, acc.getUsage().promptTokens());
        assertEquals(3L, acc.getUsage().thinkTokens());
        assertEquals(2L, acc.getUsage().completionTokens());
        assertEquals(15L, acc.getUsage().totalTokens());
        assertNotNull(acc.getUsage().getSource(), "需保留原始 usage 节点");
    }

    /**
     * 无 usage 的中间帧：不得凭空造出 usage
     */
    @Test
    public void frameWithoutUsageKeepsUsageNull() {
        ChatAccumulator acc = feed(newCtx(), frame("\"content\":\"杭州\"", "null"));

        assertNull(acc.getUsage());
    }

    /**
     * 联网搜索：search_info 在 output 层级，需注入 message 后由核心解析
     */
    @Test
    public void searchResultsAreInjectedIntoMessage() {
        ChatAccumulator acc = feed(newCtx(),
                "{\"output\":{\"search_info\":{\"search_results\":["
                        + "{\"index\":1,\"title\":\"天气\",\"url\":\"https://example.com/1\"}]},"
                        + "\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"杭州今天晴\"}}]}}");

        assertNotNull(acc.lastItem().getSearchResultsRaw());
        assertEquals(1, acc.lastItem().getSearchResultsRaw().size());
        assertEquals("天气", acc.lastItem().getSearchResultsRaw().get(0).get("title"));
    }

    /**
     * search_info 存在但无 search_results：不注入（避免写出空字段）
     */
    @Test
    public void searchInfoWithoutResultsIsIgnored() {
        ChatAccumulator acc = feed(newCtx(),
                "{\"output\":{\"search_info\":{\"foo\":\"bar\"},"
                        + "\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"杭州今天晴\"}}]}}");

        assertNull(acc.lastItem().getSearchResultsRaw());
    }

    /**
     * 结束帧只有 finish_reason、连 message 都没有：仍要补空内容项收口
     */
    @Test
    public void finishFrameWithoutMessageStillProducesItem() {
        ChatAccumulator acc = feed(newCtx(),
                "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\"}]},\"request_id\":\"req-1\"}");

        assertTrue(acc.isFinished());
        assertEquals("stop", acc.getLastFinishReasonNormalized());
        assertEquals(1, acc.getContentItems().size(), "完成但无内容项时必须补空项");
        assertEquals("", acc.lastItem().getTextRaw());
    }

    /**
     * 空正文的结束帧：同样走「补空内容项」分支
     */
    @Test
    public void finishFrameWithEmptyContentStillProducesItem() {
        ChatAccumulator acc = feed(newCtx(), frame("\"content\":\"\"", "\"stop\""));

        assertTrue(acc.isFinished());
        assertEquals(1, acc.getContentItems().size());
        assertEquals("", acc.lastItem().getTextRaw());
    }

    /// ////////////////////////// 快照归一的字段覆盖面

    /**
     * 推理字段回退名 reasoning（无 reasoning_content 时）同样参与快照归一
     */
    @Test
    public void reasoningFallbackFieldIsNormalized() {
        ChatStreamContext ctx = newCtx();

        ChatAccumulator acc = feed(ctx, frame("\"content\":\"\",\"reasoning\":\"先查一下杭州天气\"", "null"));
        assertEquals("reasoning", acc.reasoning_field_name);
        assertEquals("先查一下杭州天气", acc.lastItem().getThinkingRaw());

        feed(ctx, frame("\"content\":\"\",\"reasoning\":\"先查一下杭州天气再回答用户\"", "null"));
        assertEquals("再回答用户", acc.lastItem().getThinkingRaw(), "reasoning 的全量快照也不得重复累加");
    }

    /**
     * 多模态 content 数组不做快照归一（不是文本追加语义），原样交给核心解析
     */
    @Test
    public void multiModalContentArrayIsNotNormalized() {
        ChatStreamContext ctx = newCtx();

        String body = "\"content\":[{\"text\":\"" + BASE + "\"}]";

        assertEquals(BASE, feed(ctx, frame(body, "null")).lastItem().getTextRaw());
        assertEquals(BASE, feed(ctx, frame(body, "null")).lastItem().getTextRaw(),
                "数组形态由 incremental_output 保证增量，方言不得截断");
    }

    /**
     * 纯 tool_calls 帧（无任何文本）不进入快照判定，因此不会污染正文基准
     */
    @Test
    public void toolCallOnlyFrameDoesNotDisturbSnapshotBaseline() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"" + BASE + "\"", "null"));
        feed(ctx, frame("\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{}\"}}]", "null"));

        assertEquals("，气温25度", feed(ctx, frame("\"content\":\"" + BASE + "，气温25度\"", "null"))
                .lastItem().getTextRaw(), "工具帧不得重置或推进正文基准");
    }

    /**
     * n&gt;1：各路 choice 的快照基准必须彼此隔离（否则会互相串话）
     */
    @Test
    public void snapshotBaselineIsIsolatedPerChoice() {
        ChatStreamContext ctx = newCtx();

        String other = "另一路的完整正文内容";

        ChatAccumulator acc = feed(ctx, twoChoiceFrame(BASE, other));
        assertEquals(2, acc.getContentItems().size());
        assertEquals(BASE, acc.getContentItems().get(0).getTextRaw());
        assertEquals(other, acc.getContentItems().get(1).getTextRaw());

        acc = feed(ctx, twoChoiceFrame(BASE + "甲", other + "乙"));
        assertEquals(2, acc.getContentItems().size());
        assertEquals("甲", acc.getContentItems().get(0).getTextRaw(), "choice 0 的增量");
        assertEquals("乙", acc.getContentItems().get(1).getTextRaw(), "choice 1 的增量");
    }

    private String twoChoiceFrame(String content0, String content1) {
        return "{\"output\":{\"choices\":["
                + "{\"finish_reason\":null,\"message\":{\"role\":\"assistant\",\"content\":\"" + content0 + "\"}},"
                + "{\"finish_reason\":null,\"message\":{\"role\":\"assistant\",\"content\":\"" + content1 + "\"}}"
                + "]},\"request_id\":\"req-1\"}";
    }

    /**
     * 非流式（一帧即全量）：即使复用同一累积器也不得截前缀
     */
    @Test
    public void nonStreamContextNeverNormalizes() {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, false);
        ChatAccumulator acc = new ChatAccumulator(req, false);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(config, acc);

        String full = frame("\"content\":\"" + BASE + "，气温25度\"", "\"stop\"");

        dialect.parseResponseJson(ctx, full);
        assertEquals(BASE + "，气温25度", acc.lastItem().getTextRaw());

        acc.reset();
        dialect.parseResponseJson(ctx, full);
        assertEquals(BASE + "，气温25度", acc.lastItem().getTextRaw());
        assertTrue(events.isEmpty(), "ofNoEmit 上下文不得产出事件");
    }
}
