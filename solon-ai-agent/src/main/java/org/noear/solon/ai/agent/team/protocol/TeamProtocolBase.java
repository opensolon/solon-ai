package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.NodeSpec;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Preview("3.8.1")
public abstract class TeamProtocolBase implements TeamProtocol {
    protected final TeamConfig config;

    public TeamProtocolBase(TeamConfig config) {
        this.config = config;
    }

    protected void linkAgents(NodeSpec ns) {
        for (String agentName : config.getAgentMap().keySet()) {
            ns.linkAdd(agentName, l -> l.title("route = " + agentName).when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return agentName.equalsIgnoreCase(trace.getRoute());
            }));
        }
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        if (trace != null && trace.getStepCount() > 0) {
            String history = trace.getFormattedHistory();
            String wrappedUser = "Collaboration Progress:\n" + history +
                    "\n---\nCurrent Task: " + originalPrompt.getUserContent() +
                    "\n\nPlease continue based on the progress above.";

            // 优化点：深度拷贝消息流，仅替换 USER 角色内容，保留 SYSTEM 等指令
            List<ChatMessage> messages = new ArrayList<>(originalPrompt.getMessages());
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getRole() == ChatRole.USER) {
                    messages.set(i, ChatMessage.ofUser(wrappedUser));
                    break;
                }
            }
            return Prompt.of(messages);
        }
        return originalPrompt;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n## 协作协议：").append(config.getProtocol().name()).append("\n");
            sb.append("- 作为团队主管，请根据任务需求和成员能力做出决策。");
        } else {
            sb.append("\n## Collaboration Protocol: ").append(config.getProtocol().name()).append("\n");
            sb.append("- As team supervisor, make decisions based on task requirements and member capabilities.");
        }
    }
}