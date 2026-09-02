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
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventFilter;
import org.noear.solon.ai.chat.event.ChatEventType;

import static org.junit.jupiter.api.Assertions.*;
import static org.noear.solon.ai.chat.event.ChatEventType.*;

/**
 * 事件投递过滤器
 *
 * <p>核心契约：HEARTBEAT / RAW 默认不投递（无语义、逐帧产生、高并发下放大缓冲），
 * 但可通过 {@code all()} 或组合子显式开启。</p>
 *
 * @author noear
 * @since 4.1
 */
public class ChatEventFilterTest {
    private static ChatEventDefault.Builder of(ChatEventType type) {
        return ChatEventDefault.of(type);
    }

    /**
     * 默认策略挡掉 HEARTBEAT 与 RAW，其余放行
     */
    @Test
    public void defaultBlocksHeartbeatAndRaw() {
        assertFalse(ChatEventFilter.DEFAULT.test(of(HEARTBEAT).build()));
        assertFalse(ChatEventFilter.DEFAULT.test(of(RAW).build()));

        assertTrue(ChatEventFilter.DEFAULT.test(of(TEXT_DELTA).build()));
        assertTrue(ChatEventFilter.DEFAULT.test(of(RESPONSE_END).build()));
        assertTrue(ChatEventFilter.DEFAULT.test(of(TOOL_RESULT).build()));
    }

    /**
     * all() 全部放行（网关透传、协议诊断场景）
     */
    @Test
    public void allDeliversEverything() {
        for (ChatEventType type : ChatEventType.values()) {
            assertTrue(ChatEventFilter.all().test(of(type).build()), "all() 应放行: " + type);
        }
    }

    /**
     * 自定义过滤器不得破坏生命周期和步骤不变量。
     */
    @Test
    public void guardedAlwaysKeepsLifecycleAndStep() {
        ChatEventFilter textOnly = ChatEventFilter.guarded(ChatEventFilter.of(TEXT_DELTA));

        assertTrue(textOnly.test(of(RESPONSE_START).build()));
        assertTrue(textOnly.test(of(STEP_START).build()));
        assertTrue(textOnly.test(of(STEP_END).build()));
        assertTrue(textOnly.test(of(RESPONSE_END).build()));
        assertTrue(textOnly.test(of(TEXT_DELTA).build()));
        assertFalse(textOnly.test(of(RAW).build()));
        assertFalse(textOnly.test(of(THINKING_DELTA).build()));
    }

    @Test
    public void ofTypeSelectsExactly() {
        ChatEventFilter f = ChatEventFilter.of(TEXT_START, TEXT_DELTA, TEXT_END);

        assertTrue(f.test(of(TEXT_DELTA).build()));
        assertFalse(f.test(of(THINKING_DELTA).build()));
        assertFalse(f.test(of(RESPONSE_END).build()));
    }

    /**
     * or：默认策略之上追加放行（如只要正文增量 + 心跳保活）
     */
    @Test
    public void orAddsAllowance() {
        ChatEventFilter f = ChatEventFilter.of(TEXT_DELTA).or(ChatEventFilter.of(HEARTBEAT));

        assertTrue(f.test(of(TEXT_DELTA).build()));
        assertTrue(f.test(of(HEARTBEAT).build()));
        assertFalse(f.test(of(THINKING_DELTA).build()));
    }

    /**
     * 组合惯用法：默认策略放行 RAW 的诊断场景
     */
    @Test
    public void defaultPlusRawForDiagnosis() {
        ChatEventFilter f = ChatEventFilter.DEFAULT.or(ChatEventFilter.of(RAW));

        assertTrue(f.test(of(RAW).build()));
        assertFalse(f.test(of(HEARTBEAT).build()));
        assertTrue(f.test(of(TEXT_DELTA).build()));
    }
}
