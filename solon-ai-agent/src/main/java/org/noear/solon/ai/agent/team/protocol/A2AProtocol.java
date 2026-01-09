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
package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A2A (Agent to Agent) 协作协议
 * 实现智能体之间的任务移交与上下文状态衔接
 *
 * @author noear
 * @since 3.8.1
 */
public class A2AProtocol extends TeamProtocolBase {
    private static final String TOOL_TRANSFER = "__transfer_to__";
    private static final String KEY_LAST_MEMO = "last_memo";

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

        // 路由器
        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns, "__" + config.getName());
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentTools(Agent agent, ReActTrace trace) {
        Locale locale = trace.getConfig().getPromptProvider().getLocale();

        String expertList = config.getAgentMap().values().stream()
                .filter(a -> !a.name().equals(agent.name()))
                .map(a -> a.name() + (Utils.isNotEmpty(a.description()) ? "(" + a.description() + ")" : ""))
                .collect(Collectors.joining(", "));

        FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_TRANSFER);

        // 分语言处理工具定义
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            toolDesc.title("移交任务 (Protocol)")
                    .description("这是系统级工具。当你无法独立完成当前任务时，调用此工具将控制权移交给其他专家。")
                    .stringParamAdd("target", "目标专家名称，必选值: [" + expertList + "]")
                    .stringParamAdd("memo", "移交备注：说明当前进度和接棒专家需要关注的重点")
                    .doHandle(args -> "系统：移交程序已启动，请停止当前输出。");
        } else {
            toolDesc.title("Transfer Task (Protocol)")
                    .description("System-level tool. Call this to transfer control to another expert when you cannot complete the task independently.")
                    .stringParamAdd("target", "Target expert name, must be: [" + expertList + "]")
                    .stringParamAdd("memo", "Handover memo: explain current progress and key points")
                    .doHandle(args -> "System: Handover process started. Please stop generation.");
        }

        trace.addProtocolTool(toolDesc);
    }

    @Override
    public void injectAgentInstruction(Agent agent, Locale locale, StringBuilder sb) {
        sb.append("\n\n[System Notification]");

        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n必要时，你可以使用特殊工具 `").append(TOOL_TRANSFER).append("` 将任务委托给团队中的其他成员。");
        } else {
            sb.append("\nIf necessary, you can use the special tool `").append(TOOL_TRANSFER).append("` to delegate tasks to other team members.");
        }
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // 从上下文中获取捕获到的备注
        String memo = (String) trace.getProtocolContext().get(KEY_LAST_MEMO);

        if (Utils.isNotEmpty(memo)) {
            boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());
            String hint = isChinese ? "【接棒提示】： " : "[Handover Hint]: ";

            // 注入到消息列表最前面
            originalPrompt.getMessages().add(0, ChatMessage.ofSystem(hint + memo));

            // 使用后清除，防止 Memo 干扰后续非关联的对话
            trace.getProtocolContext().remove(KEY_LAST_MEMO);
        }

        return originalPrompt;
    }

    @Override
    public boolean interceptSupervisorRouting(FlowContext context, TeamTrace trace, String decision) {
        if (Utils.isEmpty(decision)) return false;

        if (decision.toLowerCase().contains(config.getFinishMarker().toLowerCase())) {
            trace.setRoute(Agent.ID_END);
            return true;
        }

        // 检查是否是 transfer_to 操作
        if (decision.contains(TOOL_TRANSFER)) {
            for (String agentName : config.getAgentMap().keySet()) {
                if (decision.contains(agentName)) {
                    // 提取 memo 信息并保存到 protocolContext
                    String memo = extractMemo(decision);
                    if (memo != null) {
                        trace.getProtocolContext().put(KEY_LAST_MEMO, memo);

                        // 将 memo 信息附加到决策文本中，方便测试验证
                        // 注意：这里修改的是 trace 的 decision，不是传入的 decision 参数
                        String enhancedDecision = decision + " (Memo: " + memo + ")";
                        trace.setLastDecision(enhancedDecision);
                    }
                    trace.setRoute(agentName);
                    return true;
                }
            }
        }
        return false;
    }

    private String extractMemo(String text) {
        try {
            // 简单的正则匹配 JSON 里的 memo 字段
            Pattern pattern = Pattern.compile("\"memo\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}