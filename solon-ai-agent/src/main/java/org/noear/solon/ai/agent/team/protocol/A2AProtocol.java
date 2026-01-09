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
package org.noear.solon.ai.agent.team.protocol;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A2A (Agent to Agent) 协作协议
 * 实现智能体之间的任务移交与上下文状态衔接
 *
 * @author noear
 * @since 3.8.1
 */
public class A2AProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(A2AProtocol.class);

    private static final String TOOL_TRANSFER = "__transfer_to__";
    private static final String KEY_LAST_MEMO = "last_memo";
    private static final String KEY_TRANSFER_HISTORY = "transfer_history";
    private static final String KEY_LAST_VALID_TARGET = "last_valid_target";

    // 协议配置选项
    private boolean enableLoopDetection = true;
    private int maxTransfersBetweenAgents = 2;
    private boolean injectRoleSpecificGuidance = true;
    private boolean enableTargetValidation = true;

    public A2AProtocol(TeamConfig config) {
        super(config);
    }

    /**
     * 启用或禁用循环检测
     */
    public A2AProtocol withLoopDetection(boolean enabled) {
        this.enableLoopDetection = enabled;
        return this;
    }

    /**
     * 设置同一对Agent之间的最大转移次数
     */
    public A2AProtocol withMaxTransfers(int max) {
        this.maxTransfersBetweenAgents = Math.max(1, max);
        return this;
    }

    /**
     * 启用或禁用角色特定的指导
     */
    public A2AProtocol withRoleSpecificGuidance(boolean enabled) {
        this.injectRoleSpecificGuidance = enabled;
        return this;
    }

    /**
     * 启用或禁用目标验证
     */
    public A2AProtocol withTargetValidation(boolean enabled) {
        this.enableTargetValidation = enabled;
        return this;
    }

    @Override
    public String name() {
        return "A2A";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        // [阶段：构建期] 默认从第一个智能体开始执行
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 所有专家节点执行完后，统一上报给主管（Supervisor）
        config.getAgentMap().values().forEach(a -> {
            spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR);
        });

        // 路由器配置
        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentTools(Agent agent, ReActTrace trace) {
        Locale locale = trace.getConfig().getPromptProvider().getLocale();

        // 排除当前 Agent 自身，生成备选专家列表
        String expertList = config.getAgentMap().values().stream()
                .filter(a -> !a.name().equals(agent.name()))
                .map(a -> {
                    String desc = a.descriptionFor(trace.getContext());
                    return a.name() + (Utils.isNotEmpty(desc) ? "(" + desc + ")" : "");
                })
                .collect(Collectors.joining(", "));

        FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_TRANSFER);

        // 注入系统级移交工具，提供更清晰的使用指南
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            toolDesc.title("移交任务")
                    .description("重要：只有在以下情况才使用此工具：\n" +
                            "1. 当前任务超出你的专业范围\n" +
                            "2. 你需要特定专家的专业技能\n" +
                            "3. 任务明确要求移交给其他专家\n\n" +
                            "不要使用此工具：\n" +
                            "1. 你已经收到具体的要求并可以完成\n" +
                            "2. 只是为了确认或反馈进度\n" +
                            "3. 任务即将完成时")
                    .stringParamAdd("target", "目标专家名称，可选范围: [" + expertList + "]")
                    .stringParamAdd("memo", "接棒说明：清晰说明已完成的工作和下一步重点")
                    .doHandle(args -> "系统：移交指令已记录，正在切换执行者...");
        } else {
            toolDesc.title("Transfer Task")
                    .description("IMPORTANT: Only use this tool when:\n" +
                            "1. The task is outside your expertise\n" +
                            "2. You need specific expert skills\n" +
                            "3. The task explicitly requires handoff\n\n" +
                            "DO NOT use this tool for:\n" +
                            "1. Confirming receipt of specific requirements\n" +
                            "2. Progress updates\n" +
                            "3. When the task is nearly complete")
                    .stringParamAdd("target", "Target expert name, candidates: [" + expertList + "]")
                    .stringParamAdd("memo", "Handover memo: clearly state completed work and next steps")
                    .doHandle(args -> "System: Transfer command recorded. Switching agent...");
        }

        trace.addProtocolTool(toolDesc);
    }

    @Override
    public void injectAgentInstruction(Agent agent, Locale locale, StringBuilder sb) {
        sb.append("\n\n[Collaboration Rules]");
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n- 如需寻求协助，请使用工具 `").append(TOOL_TRANSFER).append("`。");
            sb.append("\n- 只有在任务完全结束时，才输出回复包含 \"").append(config.getFinishMarker()).append("\"。");
            sb.append("\n- 避免不必要的转移：确认你真的需要其他专家的帮助。");
        } else {
            sb.append("\n- Use tool `").append(TOOL_TRANSFER).append("` to delegate tasks.");
            sb.append("\n- Only output \"").append(config.getFinishMarker()).append("\" when the entire task is finalized.");
            sb.append("\n- Avoid unnecessary transfers: confirm you really need other expert's help.");
        }
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("A2A Protocol - Preparing prompt for agent: {}", agent.name());
            LOG.debug("Original prompt messages count: {}", originalPrompt.getMessages().size());
        }

        // [阶段：执行前] 注入前序 Agent 留下的备注（Memo）
        String memo = (String) trace.getProtocolContext().get(KEY_LAST_MEMO);

        if (Utils.isNotEmpty(memo)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Memo present, content preview: {}",
                        memo.substring(0, Math.min(100, memo.length())));
            }

            // 根据接收方的角色类型提供特定的指导
            String roleSpecificGuidance = injectRoleSpecificGuidance ?
                    getRoleSpecificGuidance(agent, locale) : "";

            // 完全重建消息结构，确保上下文完整性
            List<ChatMessage> messages = new ArrayList<>();

            // 1. 保留所有系统消息
            originalPrompt.getMessages().stream()
                    .filter(msg -> msg.getRole() == ChatRole.SYSTEM)
                    .forEach(messages::add);

            // 2. 构建完整的用户消息（包含转交代办事项和原始需求）
            String userContent = buildCompleteUserContent(originalPrompt, memo, roleSpecificGuidance, locale);
            messages.add(ChatMessage.ofUser(userContent));

            // 3. 如果有其他非系统、非用户消息（如助手消息），也保留
            originalPrompt.getMessages().stream()
                    .filter(msg -> msg.getRole() == ChatRole.ASSISTANT)
                    .map(msg -> (AssistantMessage) msg)
                    .map(msg -> {
                        // 如果是带工具调用的 Assistant 消息，只保留其文本内容，去掉 tool_calls
                        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                            return ChatMessage.ofAssistant(msg.getContent());
                        }
                        return msg;
                    })
                    .forEach(messages::add);

            // 使用后即从上下文清理，确保一次性消费
            trace.getProtocolContext().remove(KEY_LAST_MEMO);

            Prompt newPrompt = Prompt.of(messages);

            if (LOG.isDebugEnabled()) {
                LOG.debug("New prompt messages count: {}", newPrompt.getMessages().size());
                LOG.debug("New prompt user content preview: {}",
                        userContent.substring(0, Math.min(200, userContent.length())));
            }

            return newPrompt;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("No memo found, returning original prompt");
        }

        return originalPrompt;
    }

    /**
     * 构建完整的用户消息内容
     */
    private String buildCompleteUserContent(Prompt originalPrompt, String memo, String roleSpecificGuidance, Locale locale) {
        boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        StringBuilder sb = new StringBuilder();

        if (isChinese) {
            // 中文提示词
            sb.append("## 任务接棒通知\n");
            sb.append("前一个专家已完成部分工作，以下是交接说明：\n\n");
            sb.append(memo).append("\n\n");
            sb.append("---\n\n");
            sb.append("## 原始任务需求\n");

            // 提取原始用户消息
            String originalUserMsg = extractOriginalUserContent(originalPrompt);
            sb.append(originalUserMsg);

            // 添加角色特定的指导
            if (Utils.isNotEmpty(roleSpecificGuidance)) {
                sb.append("\n\n").append(roleSpecificGuidance);
            }

            // 添加明确的指示
            sb.append("\n\n---\n\n");
            sb.append("## 你的职责\n");
            sb.append("请基于以上交接说明和原始需求，继续完成任务。");
            sb.append("如果交接说明中已经包含了完整的设计方案或具体要求，请直接按照要求执行，不要要求重复提供信息。");
        } else {
            // 英文提示词
            sb.append("## Task Handover Context\n");
            sb.append("Previous expert has completed partial work. Handover notes:\n\n");
            sb.append(memo).append("\n\n");
            sb.append("---\n\n");
            sb.append("## Original Task\n");

            String originalUserMsg = extractOriginalUserContent(originalPrompt);
            sb.append(originalUserMsg);

            // 添加角色特定的指导
            if (Utils.isNotEmpty(roleSpecificGuidance)) {
                sb.append("\n\n").append(roleSpecificGuidance);
            }

            sb.append("\n\n---\n\n");
            sb.append("## Your Responsibility\n");
            sb.append("Please proceed with the task based on the handover notes and original requirements above.");
            sb.append("If the handover notes already contain complete specifications, implement them directly without asking for repetition.");
        }

        return sb.toString();
    }

    /**
     * 根据接收方的角色提供特定的指导
     */
    private String getRoleSpecificGuidance(Agent agent, Locale locale) {
        String agentName = agent.name().toLowerCase();
        String description = agent.descriptionFor(null);
        if (description != null) {
            description = description.toLowerCase();
        } else {
            description = "";
        }

        boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        // 检查是否是开发相关角色
        if (agentName.contains("developer") || agentName.contains("coder") ||
                agentName.contains("开发") || agentName.contains("代码") ||
                description.contains("html") || description.contains("css") ||
                description.contains("frontend") || description.contains("前端")) {

            return isChinese ?
                    "## 对开发者的特别提醒\n" +
                            "重要：如果你收到了完整的设计方案，这已经是具体需求。\n" +
                            "请直接实现代码，不要要求设计师提供更多细节。\n" +
                            "完成代码实现后，输出 " + config.getFinishMarker() + "。" :
                    "## Special Note for Developer\n" +
                            "IMPORTANT: Design specifications = concrete requirements.\n" +
                            "Implement directly without asking for more details.\n" +
                            "Output " + config.getFinishMarker() + " after completing the code.";
        }

        // 检查是否是设计相关角色
        if (agentName.contains("designer") || agentName.contains("ui") ||
                agentName.contains("ux") || agentName.contains("设计") ||
                description.contains("design") || description.contains("ui") ||
                description.contains("ux") || description.contains("视觉")) {

            return isChinese ?
                    "## 对设计师的特别提醒\n" +
                            "请确保设计方案足够详细和具体，包含颜色、字体、间距、交互状态等。\n" +
                            "这样开发者可以直接实现，不需要来回确认。" :
                    "## Special Note for Designer\n" +
                            "Ensure designs are detailed and specific, including colors, fonts, spacing, interactions.\n" +
                            "This allows developers to implement directly without back-and-forth.";
        }

        // 检查是否是审核/编辑角色
        if (agentName.contains("editor") || agentName.contains("reviewer") ||
                agentName.contains("审核") || agentName.contains("校对") ||
                description.contains("edit") || description.contains("review") ||
                description.contains("校对") || description.contains("审核")) {

            return isChinese ?
                    "## 对审核者的特别提醒\n" +
                            "请检查内容的质量和完整性，但避免微观管理。\n" +
                            "如果内容已经符合要求，可以直接批准。" :
                    "## Special Note for Editor/Reviewer\n" +
                            "Check content quality and completeness, but avoid micromanagement.\n" +
                            "Approve directly if content meets requirements.";
        }

        return "";
    }

    /**
     * 从原始提示词中提取用户消息内容
     */
    private String extractOriginalUserContent(Prompt originalPrompt) {
        return originalPrompt.getMessages().stream()
                .filter(msg -> msg.getRole() == ChatRole.USER)
                .map(ChatMessage::getContent)
                .collect(Collectors.joining("\n\n"));
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        String lastAgentName = context.getAs(Agent.KEY_LAST_AGENT_NAME);
        if (Utils.isEmpty(lastAgentName)) return null;

        // [调整点] 统一从 FlowContext 获取 Agent 自身的轨迹
        AgentTrace latestTrace = context.getAs("__" + lastAgentName);

        if (latestTrace instanceof ReActTrace) {
            ReActTrace rt = (ReActTrace) latestTrace;

            // 提取 Memo 并存入 ProtocolContext (用于下个节点的 prepareAgentPrompt)
            String memo = extractValueFromToolCalls(rt, "memo");
            if (Utils.isNotEmpty(memo)) {
                trace.getProtocolContext().put(KEY_LAST_MEMO, memo);
            }

            // 优先返回显式 target
            String target = extractValueFromToolCalls(rt, "target");
            if (Utils.isNotEmpty(target)) {
                // 检查是否形成循环
                if (enableLoopDetection && isTransferLoop(trace, lastAgentName, target)) {
                    LOG.warn("A2A Protocol - Transfer loop detected: {} -> {}, forcing termination",
                            lastAgentName, target);
                    trace.addStep(Agent.ID_SUPERVISOR,
                            String.format("检测到循环转移：%s -> %s，任务强制终止", lastAgentName, target), 0);
                    return Agent.ID_END;
                }

                // 记录转移历史
                recordTransfer(trace, lastAgentName, target);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("A2A Protocol - Resolved route from tool call: target={}, memo length={}",
                            target, memo != null ? memo.length() : 0);
                }
                return target;
            }
        }

        // 2. 兜底解析：如果 LLM 在 Decision 中提到了转交工具但没调用，或直接提到了名字
        if (decision.contains(TOOL_TRANSFER)) {
            for (String agentName : config.getAgentMap().keySet()) {
                if (decision.contains(agentName)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("A2A Protocol - Resolved route from decision text: {}", agentName);
                    }
                    return agentName;
                }
            }
        }

        return null; // 交给 Supervisor 继续匹配
    }

    /**
     * 检查是否形成转移循环
     */
    @SuppressWarnings("unchecked")
    private boolean isTransferLoop(TeamTrace trace, String fromAgent, String toAgent) {
        List<String> history = (List<String>) trace.getProtocolContext()
                .computeIfAbsent(KEY_TRANSFER_HISTORY, k -> new ArrayList<>());

        // 检查最近几次转移是否形成 A->B->A 的循环
        if (history.size() >= 2) {
            String lastFrom = history.get(history.size() - 2);
            String lastTo = history.get(history.size() - 1);

            // 如果上次是 B->A，这次是 A->B，就是循环
            if (lastFrom.equals(toAgent) && lastTo.equals(fromAgent)) {
                return true;
            }

            // 检查同一对Agent之间的转移次数是否超过限制
            long transfersBetween = history.stream()
                    .filter(name -> name.equals(fromAgent) || name.equals(toAgent))
                    .count();
            if (transfersBetween >= maxTransfersBetweenAgents * 2) {
                return true;
            }
        }

        return false;
    }

    /**
     * 记录转移历史
     */
    @SuppressWarnings("unchecked")
    private void recordTransfer(TeamTrace trace, String fromAgent, String toAgent) {
        List<String> history = (List<String>) trace.getProtocolContext()
                .computeIfAbsent(KEY_TRANSFER_HISTORY, k -> new ArrayList<>());

        history.add(fromAgent);
        history.add(toAgent);

        // 只保留最近20次转移记录
        if (history.size() > 20) {
            trace.getProtocolContext().put(KEY_TRANSFER_HISTORY,
                    new ArrayList<>(history.subList(history.size() - 20, history.size())));
        }
    }

    /**
     * 从轨迹中最后一次工具调用提取特定参数
     */
    private String extractValueFromToolCalls(ReActTrace reactTrace, String key) {
        List<ChatMessage> messages = reactTrace.getMessages();
        if (messages == null) return null;

        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                if (am.getToolCalls() != null) {
                    for (ToolCall tc : am.getToolCalls()) {
                        if (TOOL_TRANSFER.equals(tc.name())) {
                            String value = extractValue(tc.arguments(), key);
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("A2A Protocol - Extracted {} from tool call: {}", key,
                                        value != null ? value.substring(0, Math.min(50, value.length())) : "null");
                            }
                            return value;
                        }
                    }
                }
            }
        }
        return null;
    }

    private String extractValue(Object arguments, String key) {
        if (arguments instanceof java.util.Map) {
            Object val = ((java.util.Map<?, ?>) arguments).get(key);
            return val == null ? null : val.toString();
        } else if (arguments instanceof String) {
            String json = (String) arguments;
            if (json.trim().startsWith("{")) {
                try {
                    return ONode.ofJson(json).get(key).getString();
                } catch (Exception e) {
                    LOG.warn("A2A Protocol - Failed to parse JSON arguments: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 基础检查：决策不能为空
        if (Utils.isEmpty(decision)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("A2A Protocol - Empty decision, rejecting route");
            }
            return false;
        }

        // 检查目标是否存在
        if (enableTargetValidation) {
            String targetRoute = resolveSupervisorRoute(context, trace, decision);
            if (targetRoute != null && !Agent.ID_END.equals(targetRoute)) {
                if (!config.getAgentMap().containsKey(targetRoute)) {
                    LOG.warn("A2A Protocol - Invalid target agent: {}", targetRoute);
                    trace.addStep(Agent.ID_SUPERVISOR,
                            String.format("警告：目标专家 '%s' 不存在于团队中。任务终止。", targetRoute), 0);
                    trace.setRoute(Agent.ID_END);
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        if (LOG.isInfoEnabled()) {
            String memo = (String) trace.getProtocolContext().get(KEY_LAST_MEMO);
            int memoLength = memo != null ? memo.length() : 0;

            LOG.info("A2A Protocol - Routing: {} -> {}, memo length: {}, total steps: {}",
                    trace.getAgentName(), nextAgent, memoLength, trace.getStepCount());
        }

        // 记录最后一次有效的转移
        if (!Agent.ID_SUPERVISOR.equals(nextAgent) && !Agent.ID_END.equals(nextAgent)) {
            trace.getProtocolContext().put(KEY_LAST_VALID_TARGET, nextAgent);
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        // 清理协议相关的上下文数据
        trace.getProtocolContext().remove(KEY_LAST_MEMO);
        trace.getProtocolContext().remove(KEY_TRANSFER_HISTORY);
        trace.getProtocolContext().remove(KEY_LAST_VALID_TARGET);

        if (LOG.isDebugEnabled()) {
            LOG.debug("A2A Protocol - Team finished, cleaned up protocol context");
        }
    }
}