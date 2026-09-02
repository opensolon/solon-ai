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
import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.event.ChatStreamSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 用量的跨步累加契约
 *
 * <p>用量合并分两层，混用会造成静默的计费错误（无异常、无日志），因此必须由测试锁定：</p>
 * <ul>
 *   <li>步内覆盖：方言在一步内多次给出的 usage 是整条消息的累计快照，后到覆盖先到（不在本测试范围，
 *   由各方言的 event-sequence 测试覆盖）</li>
 *   <li>步间累加：自动工具调用的每一轮是独立的一次模型调用，各步相加才是本次 stream() 的真实消耗</li>
 * </ul>
 *
 * @author noear
 */
public class ChatStreamSessionUsageTest {
    private static AiUsage usageOf(long prompt, long think, long completion, long total) {
        return new AiUsage(prompt, think, completion, total, null);
    }

    /**
     * 无用量时返回 null，而不是零值对象——零值会让调用方误以为「确实消耗了 0 个 token」
     */
    @Test
    public void totalUsageIsNullWhenNeverAccumulated() {
        ChatStreamSession session = new ChatStreamSession();

        assertNull(session.getTotalUsage());
        assertNull(session.accumulateUsage(null));
        assertNull(session.getTotalUsage());
    }

    /**
     * 首个用量直接持有（不做无谓的对象拷贝）
     */
    @Test
    public void firstUsageIsHeldDirectly() {
        ChatStreamSession session = new ChatStreamSession();
        AiUsage first = usageOf(10, 2, 5, 15);

        assertSame(first, session.accumulateUsage(first));
        assertSame(first, session.getTotalUsage());
    }

    /**
     * 跨步逐字段累加：两轮工具调用后，用量等于两轮之和
     */
    @Test
    public void usageAccumulatesAcrossSteps() {
        ChatStreamSession session = new ChatStreamSession();

        session.accumulateUsage(usageOf(10, 2, 5, 15));
        session.accumulateUsage(usageOf(20, 3, 7, 27));
        AiUsage total = session.accumulateUsage(usageOf(30, 0, 9, 39));

        assertEquals(60, total.promptTokens());
        assertEquals(5, total.thinkTokens());
        assertEquals(21, total.completionTokens());
        assertEquals(81, total.totalTokens());
        assertSame(total, session.getTotalUsage());
    }

    /**
     * 缓存计费两项同样累加（Prompt Caching 的命中率按全流统计才有意义）
     */
    @Test
    public void cacheTokensAccumulateAcrossSteps() {
        ChatStreamSession session = new ChatStreamSession();

        session.accumulateUsage(new AiUsage(100, 0, 10, 110, 40, 50, null));
        AiUsage total = session.accumulateUsage(new AiUsage(200, 0, 20, 220, 60, 150, null));

        assertEquals(100, total.cacheCreationInputTokens());
        assertEquals(200, total.cacheReadInputTokens());
        assertEquals(300, total.promptTokens());
    }

    /**
     * 中间步没有用量时跳过，不清零已累计值
     */
    @Test
    public void nullStepUsageDoesNotResetTotal() {
        ChatStreamSession session = new ChatStreamSession();

        session.accumulateUsage(usageOf(10, 0, 5, 15));
        session.accumulateUsage(null);
        AiUsage total = session.accumulateUsage(usageOf(10, 0, 5, 15));

        assertEquals(20, total.promptTokens());
        assertEquals(30, total.totalTokens());
    }

    /**
     * 跨步用量的原始 source 不能只保留最后一轮；多轮时按 steps 完整保存 provider 扩展字段。
     */
    @Test
    public void usageSourceIsPreservedAcrossSteps() {
        ChatStreamSession session = new ChatStreamSession();
        ONode firstSource = ONode.ofJson("{\"input_tokens\":10,\"provider_first\":\"a\"}");
        ONode secondSource = ONode.ofJson("{\"output_tokens\":20,\"provider_second\":\"b\"}");

        session.accumulateUsage(new AiUsage(10, 0, 5, 15, firstSource));
        AiUsage total = session.accumulateUsage(new AiUsage(20, 0, 7, 27, secondSource));

        assertEquals(2, total.getSource().get("steps").getArray().size());
        assertEquals("a", total.getSource().get("steps").get(0).get("provider_first").getString());
        assertEquals("b", total.getSource().get("steps").get(1).get("provider_second").getString());
    }

    @Test
    public void stepIsMonotonic() {
        ChatStreamSession session = new ChatStreamSession();

        assertEquals(0, session.getStep());
        assertEquals(0, session.nextStep());
        assertEquals(1, session.nextStep());
        assertEquals(2, session.nextStep());
        assertEquals(2, session.getStep());
    }
}
