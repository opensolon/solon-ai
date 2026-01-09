package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class A2AProtocol extends TeamProtocolBase {
    private static final String TOOL_TRANSFER = "transfer_to";

    public A2AProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "A2A";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        config.getAgentMap().values().forEach(a -> {
            spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR);
        });

        // 绑定路由器
        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns, "__" + config.getName());
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentOptions(Agent agent, ChatOptions options) {
        String expertList = config.getAgentMap().values().stream()
                .filter(a -> !a.name().equals(agent.name()))
                .map(a -> a.name() + "(" + a.description() + ")")
                .collect(Collectors.joining(", "));

        options.toolsAdd(new FunctionToolDesc(TOOL_TRANSFER)
                .title("移交任务")
                .description("当你无法独立完成当前任务，或需要其他专家协助时，调用此工具进行移交。")
                .stringParamAdd("target", "目标专家名称，必选值: [" + expertList + "]")
                .stringParamAdd("memo", "移交备注：说明当前进度和接棒专家需要关注的重点")
                .doHandle(args -> "Handover initiated. Please terminate output."));
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        String lastDecision = trace.getDecision();
        if (Utils.isNotEmpty(lastDecision) && lastDecision.contains(TOOL_TRANSFER)) {
            // 尝试提取 memo 内容: 匹配 memo="xxx" 或 "memo":"xxx"
            String memo = extractMemo(lastDecision);

            String prefix = Locale.CHINA.getLanguage().equals(locale.getLanguage()) ? "【接棒提示】： " : "[Handover Hint]: ";
            String content = prefix + (Utils.isNotEmpty(memo) ? memo : "Previous expert handed over this task to you.");

            // 注入为系统消息，置于首位
            originalPrompt.getMessages().add(0, ChatMessage.ofSystem(content));
        }
        return originalPrompt;
    }

    @Override
    public boolean interceptSupervisorRouting(FlowContext context, TeamTrace trace, String decision) {
        if (Utils.isEmpty(decision)) return false;

        // 1. 结束标识匹配
        if (decision.toLowerCase().contains(config.getFinishMarker().toLowerCase())) {
            trace.setRoute(Agent.ID_END);
            return true;
        }

        // 2. 移交工具匹配
        for (String agentName : config.getAgentMap().keySet()) {
            if (decision.contains(TOOL_TRANSFER) && decision.contains(agentName)) {
                trace.setRoute(agentName);
                return true;
            }
        }

        return false;
    }

    /**
     * 简单的正则提取器，用于从 ToolCall 文本中提取 memo 参数
     */
    private String extractMemo(String text) {
        // 匹配 json 格式或 key=value 格式的 memo 内容
        Pattern pattern = Pattern.compile("memo[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}