/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.command.builtin;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.core.util.Assert;

import java.util.List;

/**
 * /rerun 命令
 *
 * @author noear
 * @since 2026.4.28
 */
public class RerunCommand implements Command {
    @Override
    public String name() {
        return "rerun";
    }

    @Override
    public String description() {
        return "重新运行最后一个任务";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/rerun"
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx) throws Exception {
        AgentSession session = ctx.getSession();

        if (session == null) {
            return;
        }

        List<ChatMessage> messageList = session.getMessages();
        String lastUserInput = null;

        while (!messageList.isEmpty()) {
            ChatMessage msg = messageList.get(messageList.size() - 1);
            if (msg instanceof UserMessage) {
                lastUserInput = msg.getContent();
                session.removeLatestMessage(1);
                break;
            }
            session.removeLatestMessage(1);
        }

        if (lastUserInput != null) {
            //声明意图（带目标会话，修正 /rerun <id> 的手术与开流会话错位）
            ctx.requestAgentTask(lastUserInput, null);
        }
    }
}
