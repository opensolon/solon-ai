/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.lang.Preview;

/**
 * 任务运行结束块
 *
 * @author noear
 * @since 4.0.4
 */
@Preview("4.0.4")
public class TeamEndEvent extends AbsAgentEvent {
    private final transient TeamResponse response;
    private final transient AssistantMessage message;

    public TeamEndEvent(TeamResponse resp) {
        super(resp.getTrace().getRunId(), resp.getTrace().getAgentName(), resp.getSession());
        this.response = resp;
        this.message = resp.getMessage();
    }

    public TeamResponse getResponse() {
        return response;
    }

    public AssistantMessage getMessage() {
        return message;
    }

    public TeamTrace getTrace() {
        return response.getTrace();
    }

    @Override
    public String getText() {
        return message.getContent();
    }
}
