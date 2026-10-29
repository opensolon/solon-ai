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

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.util.TraceUtil;
import org.noear.solon.core.util.Assert;

import java.util.List;

/**
 * /continue 命令
 *
 * <pre>
 * 用法:
 *   /continue                   → 继续当前会话最后一个未完成的任务
 *   /continue &lt;sessionId&gt;       → 继续指定会话中最后一个未完成的任务（IM 跨会话控制）
 * </pre>
 *
 * @author noear
 * @since 2026.4.28
 */
public class ContinueCommand implements Command {
    @Override
    public String name() {
        return "continue";
    }

    @Override
    public String description() {
        return "继续运行最后一个未完成的任务";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/continue"
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx) throws Exception {
        AgentSession session = ctx.getSession();

        if (session == null) {
            return;
        }

        ReActTrace trace = TraceUtil.getCurrentTrace(session);

        // 前置拦截：无可继续的任务时，以命令回执形式提示并直接返回（不声明任务意图）。
        // trace 为 null：rewind 后 __main 被移除、会话重启/刷新后轨迹未还原；
        // originalPrompt 为空：__main 是从未跑过任务的空壳轨迹（如恢复会话时被惰性创建）。
        // 此两种情况下声明「续流意图」只会触发空转结束，前端表现为「点了没反应、马上结束」。
        if (trace == null || Prompt.isEmpty(trace.getOriginalPrompt())) {
            ctx.println("没有可继续的任务");
            return;
        }

        if (Agent.ID_END.equals(trace.getRoute())) {
            // 说明有结束节点，重新回到思考点
            trace.setRoute(ReActAgent.ID_REASON);
            trace.setFinalAnswer(null, false);

            ChatMessage workMessage = trace.getWorkingMemory().getLastMessage();
            if (workMessage instanceof AssistantMessage) {
                trace.getWorkingMemory().removeLastMessage();
            }

            // 回退一条 ai 消息（要重新生成）
            // 注意：这里直接用内部列表引用，使 size 在 removeLatestMessage 后实时更新
            List<ChatMessage> messageList = session.getMessages();
            while (!messageList.isEmpty() && messageList.get(messageList.size() - 1) instanceof AssistantMessage) {
                session.removeLatestMessage(1);
            }
        }

        // 声明续流意图（prompt 为空 = 续流；装配走引擎 promptDo 的续流分支，不受空输入判空影响）
        ctx.requestAgentTask(null, null);
    }
}
