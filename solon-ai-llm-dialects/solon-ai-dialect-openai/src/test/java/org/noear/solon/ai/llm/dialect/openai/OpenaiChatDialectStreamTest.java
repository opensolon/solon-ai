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
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
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

    private ChatAccumulator newStreamResponse() {
        ChatOptions options = ChatOptions.of();
        ChatRequest req = new ChatRequest(config, dialect, options,
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        return new ChatAccumulator(req, true);
    }

    /**
     * 走方言的唯一解析入口；单测不关心事件，故用「不发事件」的上下文
     */
    private void parse(ChatAccumulator resp, String json) {
        dialect.parseResponseJson(ChatStreamContextDefault.ofNoEmit(config, resp), json);
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
    private String joinText(ChatAccumulator resp) {
        StringBuilder buf = new StringBuilder();
        for (AssistantMessage choice : resp.getContentItems()) {
            if (choice != null && choice.getTextRaw() != null) {
                buf.append(choice.getTextRaw());
            }
        }
        return buf.toString();
    }

    private String joinThinking(ChatAccumulator resp) {
        StringBuilder buf = new StringBuilder();
        for (AssistantMessage choice : resp.getContentItems()) {
            if (choice != null && choice.getThinkingRaw() != null) {
                buf.append(choice.getThinkingRaw());
            }
        }
        return buf.toString();
    }

    @Test
    public void cumulativeContentSnapshot_isNormalizedToSuffix() {
        ChatAccumulator resp = newStreamResponse();

        parse(resp, chunk("所有代码修改完成。"));
        parse(resp, chunk("所有代码修改完成。更新任务进度并运行验证"));

        assertEquals("所有代码修改完成。更新任务进度并运行验证", joinText(resp));
    }

    @Test
    public void cumulativeReasoningSnapshot_isNormalizedToSuffix() {
        ChatAccumulator resp = newStreamResponse();

        parse(resp, reasoningChunk("先确认累计快照的判定门槛"));
        parse(resp, reasoningChunk("先确认累计快照的判定门槛，再决定是否截断"));

        assertEquals("先确认累计快照的判定门槛，再决定是否截断", joinThinking(resp));
    }

    @Test
    public void duplicatedSnapshotFrame_isDroppedWithoutEmptyChoice() {
        ChatAccumulator resp = newStreamResponse();

        parse(resp, chunk("所有代码修改完成。"));
        parse(resp, chunk("所有代码修改完成。更新任务进度"));
        int choicesBefore = resp.getContentItems().size();

        // 完全重复的快照帧：整帧丢弃，不能给订阅侧多推一条空 delta。
        // 内容项数量不变即为丢弃生效的信号
        parse(resp, chunk("所有代码修改完成。更新任务进度"));

        assertEquals(choicesBefore, resp.getContentItems().size(), "重复快照帧不应产生新的内容项");
        assertEquals("所有代码修改完成。更新任务进度", joinText(resp));
    }

    @Test
    public void shortLegitDeltas_areNotTreatedAsSnapshot() {
        ChatAccumulator resp = newStreamResponse();

        // 合规流的短增量极易偶然构成前缀关系（"好" / "好的"、"**" / "**"），
        // 必须原样保留，否则会静默篡改正常输出
        parse(resp, chunk("好"));
        parse(resp, chunk("好的"));
        parse(resp, chunk("**"));
        parse(resp, chunk("**"));

        assertEquals("好好的****", joinText(resp));
    }

    @Test
    public void repeatedLongDelta_isKeptWhenNotPrefixOfAggregation() {
        ChatAccumulator resp = newStreamResponse();

        // 长增量但不构成前缀关系：不得改写
        parse(resp, chunk("第一段较长的输出内容"));
        parse(resp, chunk("第二段完全不同的内容"));

        assertEquals("第一段较长的输出内容第二段完全不同的内容", joinText(resp));
    }

    /**
     * n&gt;1 时各路 choice 的累计基准必须隔离（与官方 SDK 按 choice.index 累积一致）：
     * 若共用一份基准，交错下发会把基准搅成 c0f1+c1f1，快照判定随即失效。
     */
    @Test
    public void multiChoiceSnapshot_isNormalizedPerIndex() {
        ChatAccumulator resp = newStreamResponse();

        parse(resp, twoChoiceChunk("第一路的较长输出内容", "第二路的较长输出内容"));
        parse(resp, twoChoiceChunk("第一路的较长输出内容-续一", "第二路的较长输出内容-续二"));

        // 内容项已无 index（4.1 取消候选维度），改断合并后的到达序列：
        // 两路各自被正确归一成增量时，合并结果恰为 f1c0 + f1c1 + f2c0增量 + f2c1增量
        assertEquals("第一路的较长输出内容第二路的较长输出内容-续一-续二", joinText(resp));
    }

    /**
     * 核心层会在正文为空时把 delta.refusal 投影进文本，故拒答文本也要记入累计基准，
     * 否则后续正文快照帧会因基准偏移而漏判。
     */
    @Test
    public void refusalText_isCountedIntoSnapshotBaseline() {
        ChatAccumulator resp = newStreamResponse();

        parse(resp,
                "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\",\"refusal\":\"抱歉，我不能协助该请求。\"},\"finish_reason\":null}]}");
        parse(resp, chunk("抱歉，我不能协助该请求。可以换个问法"));

        assertEquals("抱歉，我不能协助该请求。可以换个问法", joinText(resp));
    }
}
