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
package org.noear.solon.ai.llm.dialect.openai;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI Chat Completions 流式增量归一测试
 *
 * <p>覆盖「少数兼容网关把累计快照放进 delta」的纠偏，以及合规增量不被误改写的边界。</p>
 */
public class OpenaiChatDialectStreamTest {
    private final ChatConfig config = new ChatConfig();
    private final OpenaiChatDialect dialect = OpenaiChatDialect.getInstance();

    private ChatResponseDefault newStreamResponse() {
        ChatOptions options = ChatOptions.of();
        ChatRequest req = new ChatRequest(config, dialect, options,
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        return new ChatResponseDefault(req, true);
    }

    private String chunk(String content) {
        return "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private String reasoningChunk(String reasoning) {
        return "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"" + reasoning + "\"},\"finish_reason\":null}]}";
    }

    /**
     * n=2 的交错帧（一个 chunk 同时携带两路 choice）
     */
    private String twoChoiceChunk(String content0, String content1) {
        return "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + content0 + "\"},\"finish_reason\":null},"
                + "{\"index\":1,\"delta\":{\"role\":\"assistant\",\"content\":\"" + content1 + "\"},\"finish_reason\":null}]}";
    }

    /**
     * 逐帧文本拼接（模拟核心层聚合：把每个 choice 的 textRaw 依次追加）
     */
    private String joinText(ChatResponseDefault resp) {
        return joinText(resp, -1);
    }

    private String joinText(ChatResponseDefault resp, int index) {
        StringBuilder buf = new StringBuilder();
        for (ChatChoice choice : resp.getChoices()) {
            if (index >= 0 && choice.index() != index) {
                continue;
            }
            if (choice.getMessage() != null && choice.getMessage().getTextRaw() != null) {
                buf.append(choice.getMessage().getTextRaw());
            }
        }
        return buf.toString();
    }

    private String joinThinking(ChatResponseDefault resp) {
        StringBuilder buf = new StringBuilder();
        for (ChatChoice choice : resp.getChoices()) {
            if (choice.getMessage() != null && choice.getMessage().getThinkingRaw() != null) {
                buf.append(choice.getMessage().getThinkingRaw());
            }
        }
        return buf.toString();
    }

    @Test
    public void cumulativeContentSnapshot_isNormalizedToSuffix() {
        ChatResponseDefault resp = newStreamResponse();

        dialect.parseResponseJson(config, resp, chunk("所有代码修改完成。"));
        dialect.parseResponseJson(config, resp, chunk("所有代码修改完成。更新任务进度并运行验证"));

        assertEquals("所有代码修改完成。更新任务进度并运行验证", joinText(resp));
    }

    @Test
    public void cumulativeReasoningSnapshot_isNormalizedToSuffix() {
        ChatResponseDefault resp = newStreamResponse();

        dialect.parseResponseJson(config, resp, reasoningChunk("先确认累计快照的判定门槛"));
        dialect.parseResponseJson(config, resp, reasoningChunk("先确认累计快照的判定门槛，再决定是否截断"));

        assertEquals("先确认累计快照的判定门槛，再决定是否截断", joinThinking(resp));
    }

    @Test
    public void duplicatedSnapshotFrame_isDroppedWithoutEmptyChoice() {
        ChatResponseDefault resp = newStreamResponse();

        dialect.parseResponseJson(config, resp, chunk("所有代码修改完成。"));
        dialect.parseResponseJson(config, resp, chunk("所有代码修改完成。更新任务进度"));
        int choicesBefore = resp.getChoices().size();

        // 完全重复的快照帧：整帧丢弃，不能给订阅侧多推一条空 delta
        assertTrue(dialect.parseResponseJson(config, resp, chunk("所有代码修改完成。更新任务进度")));

        assertEquals(choicesBefore, resp.getChoices().size(), "重复快照帧不应产生新的 choice");
        assertEquals("所有代码修改完成。更新任务进度", joinText(resp));
    }

    @Test
    public void shortLegitDeltas_areNotTreatedAsSnapshot() {
        ChatResponseDefault resp = newStreamResponse();

        // 合规流的短增量极易偶然构成前缀关系（"好" / "好的"、"**" / "**"），
        // 必须原样保留，否则会静默篡改正常输出
        dialect.parseResponseJson(config, resp, chunk("好"));
        dialect.parseResponseJson(config, resp, chunk("好的"));
        dialect.parseResponseJson(config, resp, chunk("**"));
        dialect.parseResponseJson(config, resp, chunk("**"));

        assertEquals("好好的****", joinText(resp));
    }

    @Test
    public void repeatedLongDelta_isKeptWhenNotPrefixOfAggregation() {
        ChatResponseDefault resp = newStreamResponse();

        // 长增量但不构成前缀关系：不得改写
        dialect.parseResponseJson(config, resp, chunk("第一段较长的输出内容"));
        dialect.parseResponseJson(config, resp, chunk("第二段完全不同的内容"));

        assertEquals("第一段较长的输出内容第二段完全不同的内容", joinText(resp));
    }

    /**
     * n&gt;1 时各路 choice 的累计基准必须隔离（与官方 SDK 按 choice.index 累积一致）：
     * 若共用一份基准，交错下发会把基准搅成 c0f1+c1f1，快照判定随即失效。
     */
    @Test
    public void multiChoiceSnapshot_isNormalizedPerIndex() {
        ChatResponseDefault resp = newStreamResponse();

        dialect.parseResponseJson(config, resp, twoChoiceChunk("第一路的较长输出内容", "第二路的较长输出内容"));
        dialect.parseResponseJson(config, resp,
                twoChoiceChunk("第一路的较长输出内容-续一", "第二路的较长输出内容-续二"));

        assertEquals("第一路的较长输出内容-续一", joinText(resp, 0));
        assertEquals("第二路的较长输出内容-续二", joinText(resp, 1));
    }

    /**
     * 核心层会在正文为空时把 delta.refusal 投影进文本，故拒答文本也要记入累计基准，
     * 否则后续正文快照帧会因基准偏移而漏判。
     */
    @Test
    public void refusalText_isCountedIntoSnapshotBaseline() {
        ChatResponseDefault resp = newStreamResponse();

        dialect.parseResponseJson(config, resp,
                "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\",\"refusal\":\"抱歉，我不能协助该请求。\"},\"finish_reason\":null}]}");
        dialect.parseResponseJson(config, resp, chunk("抱歉，我不能协助该请求。可以换个问法"));

        assertEquals("抱歉，我不能协助该请求。可以换个问法", joinText(resp));
    }
}
