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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatRequestDescDefault;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体到达事件（{@code MEDIA_DONE}）的流式发射契约
 *
 * <p>覆盖「无内容项的侧车媒体」路径（如 OpenAI Responses 的 {@code image_generation_call.done}、
 * Gemini / Ollama 的侧车图像）：这条路径不经内容项，直接由方言登记到累积器，因此发射逻辑在核心侧，
 * 单靠方言解析测试覆盖不到。</p>
 *
 * @author noear
 */
public class ChatStreamMediaEventTest {
    /**
     * 一帧多块要<b>逐块</b>发，且同一块<b>跨帧只发一次</b>
     *
     * <p>两个易错点：{@code acc.getMediaBlocks()} 是跨帧累积的、{@code reset()} 不清它——
     * 取「最后一块」会漏发同帧其余块；而后续任何「无内容项」的帧都会把同一块反复重发，
     * 前端会重复渲染同一张图。</p>
     */
    @Test
    public void mediaDoneIsEmittedPerBlockAndOncePerBlock() throws Exception {
        List<ChatEvent> events = streamOf(
                //同一帧解析出两块（多图返回）
                "{\"media\":[\"https://example.com/1.png\",\"https://example.com/2.png\"]}",
                //无内容项、无用量的帧：不得重发已发过的块
                "{\"keep_alive\":1}",
                "{\"text\":\"图好了\"}",
                //仍有累积媒体、但本帧无新块：用量事件不能被媒体分支吃掉
                "{\"usage\":1}");

        List<ChatEvent> mediaEvents = new ArrayList<>();
        for (ChatEvent e : events) {
            if (e.is(ChatEventType.MEDIA_DONE)) {
                mediaEvents.add(e);
            }
        }

        assertEquals(2, mediaEvents.size(), "两块媒体应各发一次: " + mediaEvents);
        assertEquals("https://example.com/1.png", mediaEvents.get(0).getBlock().getContent());
        assertEquals("https://example.com/2.png", mediaEvents.get(1).getBlock().getContent(),
                "同帧的其余块不能被丢掉");

        assertTrue(events.stream().anyMatch(e -> e.is(ChatEventType.USAGE)),
                "本帧无新媒体时，用量事件仍应发出: " + events);
    }

    /**
     * 增量帧取值与终态聚合一致：只拿增量帧的 {@code getMessage()} 拼起来，正好是终态，不多也不少
     *
     * <p>必须先按类型选帧：带响应的帧（{@code STEP_END} / {@code RESPONSE_END} / {@code USAGE}）
     * 给的是聚合，无条件追加会把正文加好几遍。本例同时锁住这一点。</p>
     */
    @Test
    public void deltaProjectionMatchesTerminalAggregation() throws Exception {
        List<ChatEvent> events = streamOf(
                "{\"media\":[\"https://example.com/1.png\",\"https://example.com/2.png\"]}",
                "{\"keep_alive\":1}",
                "{\"text\":\"图好了\"}");

        StringBuilder text = new StringBuilder();
        List<ContentBlock> blocks = new ArrayList<>();

        for (ChatEvent e : events) {
            if (e.is(ChatEventType.TEXT_DELTA, ChatEventType.THINKING_DELTA, ChatEventType.MEDIA_DONE) == false) {
                continue;
            }

            AssistantMessage m = e.getMessage();
            if (m == null) {
                continue;
            }

            text.append(m.getText());
            blocks.addAll(m.getBlocks());
        }

        ChatResponse terminal = null;
        for (ChatEvent e : events) {
            if (e.is(ChatEventType.RESPONSE_END)) {
                terminal = e.getResponse();
            }
        }

        assertNotNull(terminal, "应有终态事件: " + events);
        assertEquals(terminal.getMessage().getText(), text.toString(),
                "增量帧拼接应等于终态正文");
        assertEquals(2, blocks.size(), "媒体应从增量帧取到，且不重复");
        assertEquals(mediaOf(terminal.getMessage().getBlocks()), mediaOf(blocks),
                "增量帧的媒体块应与终态聚合一致（终态额外带一个聚合文本块，不参与比较）");

        //带响应的帧给聚合，不是当帧增量：与 ChatEvent#getMessage() 契约一致
        for (ChatEvent e : events) {
            if (e.is(ChatEventType.RESPONSE_END)) {
                assertSame(e.getResponse().getMessage(), e.getMessage(),
                        "终态帧应直取响应的消息");
            }
        }
    }

    /**
     * 取非文本内容块的内容（终态聚合会额外带一个汇总文本块）
     */
    private static List<String> mediaOf(List<ContentBlock> blocks) {
        List<String> contents = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock == false) {
                contents.add(block.getContent());
            }
        }
        return contents;
    }

    /// //////////////////////////

    /**
     * 起一个只吐给定 SSE 数据帧的本地端点，跑完整流式路径
     */
    private static List<ChatEvent> streamOf(String... frames) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/v1/chat/completions", exchange -> {
            StringBuilder buf = new StringBuilder();
            for (String frame : frames) {
                buf.append("data: ").append(frame).append("\n\n");
            }

            byte[] body = buf.toString().getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        server.start();

        try {
            ChatConfig config = new ChatConfig();
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            config.setModel("test-media");
            config.setTimeout(Duration.ofSeconds(30));

            List<ChatEvent> events = new ChatRequestDescDefault(config, new MediaDialect(),
                    InMemoryChatSession.builder().build(), Prompt.of("画两张图"))
                    .stream()
                    .collectList()
                    .block();

            assertNotNull(events, "流不应为空");
            return events;
        } finally {
            server.stop(0);
        }
    }

    /**
     * 测试方言：只认三种极简帧形状
     *
     * <p>{@code media} 走「侧车媒体」——只登记到累积器、不产出内容项，正是被测的那条路径。</p>
     */
    static class MediaDialect extends AbstractChatDialect {
        @Override
        public boolean matched(ChatConfig config) {
            //不参与全局方言选择（本测试直接注入），避免影响同 JVM 内其它用例
            return false;
        }

        @Override
        public void parseResponseJson(ChatStreamContext ctx, String data) {
            ChatAccumulator acc = ctx.getAccumulator();
            ONode node = ONode.ofJson(data);

            if (node.hasKey("media")) {
                List<ContentBlock> blocks = new ArrayList<>();
                for (ONode item : node.get("media").getArray()) {
                    blocks.add(ImageBlock.ofUrl(item.getString()));
                }
                acc.addMediaBlocks(blocks);
            }

            if (node.hasKey("text")) {
                acc.addContentItem(new AssistantMessage(node.get("text").getString()));
            }

            if (node.hasKey("usage")) {
                acc.setUsage(new AiUsage(1, 0, 2, 3, null));
            }
        }
    }
}
