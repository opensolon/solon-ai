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
package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventGroup;
import org.noear.solon.lang.Preview;

/**
 * 智能体对话片段块（流式中间块）
 * <p>用于在流式生成过程中，实时传递底层的对话增量内容及其关联的轨迹信息</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SimpleDeltaEvent extends AbsAgentEvent {
    private final transient SimpleTrace trace;
    private final transient ChatEvent chatEvent;

    public SimpleDeltaEvent(SimpleTrace trace, ChatEvent event) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession());
        this.trace = trace;
        this.chatEvent = event;
    }

    public SimpleTrace getTrace() {
        return trace;
    }

    public ChatEvent getChatEvent() {
        return chatEvent;
    }

    /**
     * 是否为思考
     */
    public boolean isThinking() {
        return chatEvent.isGroup(ChatEventGroup.THINKING);
    }

    @Override
    public String getText() {
        return chatEvent.getText();
    }
}