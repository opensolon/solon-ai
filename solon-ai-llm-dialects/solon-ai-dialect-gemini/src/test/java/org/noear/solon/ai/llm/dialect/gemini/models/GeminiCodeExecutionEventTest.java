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
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gemini models（generateContent）代码执行事件
 *
 * <p>executableCode / codeExecutionResult 既不进内容项也不进事件时会被静默丢弃，
 * 订阅方看不到模型跑了什么代码、得到什么输出。本测试锁定其服务端工具事件通道。</p>
 *
 * @author noear
 */
public class GeminiCodeExecutionEventTest {
    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("gemini-2.5-flash");
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    private List<ChatEvent> allOf(ChatEventType type) {
        List<ChatEvent> list = new ArrayList<>();
        for (ChatEvent e : events) {
            if (e.getType() == type) {
                list.add(e);
            }
        }
        return list;
    }

    /**
     * 代码与执行输出成对透出为 SERVER_TOOL_START / SERVER_TOOL_RESULT
     */
    @Test
    public void codeExecutionPartsBecomeServerToolEvents() {
        ChatStreamContext ctx = newCtx();

        GeminiChatDialect.getInstance().parseResponseJson(ctx, "{\"candidates\":[{"
                + "\"content\":{\"parts\":["
                + "{\"executableCode\":{\"language\":\"PYTHON\",\"code\":\"print(1+1)\"}},"
                + "{\"codeExecutionResult\":{\"outcome\":\"OUTCOME_OK\",\"output\":\"2\\n\"}},"
                + "{\"text\":\"结果是 2\"}"
                + "],\"role\":\"model\"}}]}");

        List<ChatEvent> starts = allOf(ChatEventType.SERVER_TOOL_START);
        assertEquals(1, starts.size(), "executableCode should emit one SERVER_TOOL_START");
        assertEquals("code_execution", starts.get(0).getSubType());
        assertEquals("print(1+1)", starts.get(0).getText());
        assertEquals(0, starts.get(0).getIndex());
        assertSame(ChatEventGroup.SERVER_TOOL, starts.get(0).getGroup());

        List<ChatEvent> results = allOf(ChatEventType.SERVER_TOOL_RESULT);
        assertEquals(1, results.size(), "codeExecutionResult should emit one SERVER_TOOL_RESULT");
        assertEquals("code_execution", results.get(0).getSubType());
        assertEquals("2\n", results.get(0).getText());

        //正文仍走内容项，不受影响
        assertTrue(ctx.getAccumulator().hasContentItems());
    }

    /**
     * 反向锚点：无代码执行 part 时不产生多余事件
     */
    @Test
    public void noCodeExecutionNoServerToolEvent() {
        ChatStreamContext ctx = newCtx();

        GeminiChatDialect.getInstance().parseResponseJson(ctx, "{\"candidates\":[{"
                + "\"content\":{\"parts\":[{\"text\":\"hello\"}],\"role\":\"model\"}}]}");

        assertTrue(allOf(ChatEventType.SERVER_TOOL_START).isEmpty());
        assertTrue(allOf(ChatEventType.SERVER_TOOL_RESULT).isEmpty());
    }
}
