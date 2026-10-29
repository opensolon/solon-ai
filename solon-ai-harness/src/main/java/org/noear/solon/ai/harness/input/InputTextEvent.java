/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.input;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.AgentSession;

/**
 * 文本事件
 * <p>
 * 用于把「命令回执」之类的纯文本结果也放进 AgentEvent 流里，
 * 让 {@link InputOutcome#stream()} 的调用方只面对一种数据形态。
 *
 * @author noear
 * @since 4.2
 */
public class InputTextEvent extends AbsAgentEvent {
    private final String text;

    public InputTextEvent(AgentSession session, String text) {
        super("input", "harness", session);
        this.text = text;
    }

    @Override
    public String getText() {
        return text;
    }
}
