/*
 * Copyright 2017-2025 noear.org and authors
 * ... (保持 License 不变)
 */
package org.noear.solon.ai.agent.team.protocol;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 优化版层级化协作协议 (Hierarchical Protocol)
 * * 优化点：
 * 1. 强化 absorb 逻辑：利用基类 sniffJson 提取非标准输出中的结构化数据。
 * 2. 状态分层：区分系统元数据（_meta）与业务数据，使看板对 LLM 更友好。
 * 3. 容错增强：自动清理过期的错误记录，并对长文本进行摘要化处理。
 */
@Preview("3.8.1")
public class HierarchicalProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalProtocol.class);
    private static final String KEY_HIERARCHY_STATE = "hierarchy_state_obj";
    private static final String KEY_AGENT_USAGE = "agent_usage_map";

    public static class HierarchicalState {
        private final ONode data = new ONode().asObject();

        public void markError(String agentName, String error) {
            data.getOrNew("_meta").getOrNew("errors").set(agentName, error);
        }

        public void clearError(String agentName) {
            if (data.hasKey("_meta")) {
                data.get("_meta").get("errors").remove(agentName);
            }
        }

        /**
         * 吸收 Agent 反馈，增强了对杂乱文本中 JSON 的提取能力
         */
        public void absorb(String content, TeamProtocolBase protocol) {
            if (Utils.isEmpty(content)) return;

            // 1. 尝试使用 sniffJson 嗅探结构化数据
            ONode report = protocol.sniffJson(content);
            if (report.isObject() && !report.isEmpty()) {
                // 排除一些常见的非业务字段
                report.remove("agent");
                report.remove("role");
                data.setAll(report.getObjectUnsafe());
            } else {
                // 2. 如果没有 JSON，则记录简要快照
                String memo = content.length() > 150 ? content.substring(0, 150) + "..." : content;
                data.set("_last_memo", memo);
            }
        }

        @Override
        public String toString() { return data.toJson(); }
    }

    public HierarchicalProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() { return "HIERARCHICAL"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // 强化汇报规范：不仅要求 JSON，还明确了汇报的深度
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        String hint = isZh
                ? "\n### 汇报要求：\n- 请在回复结尾使用 JSON 块反馈核心数据（如：{\"result\": \"...\", \"status\": \"done\"}）。"
                : "\n### Reporting Requirement:\n- End your response with a JSON block for key data (e.g., {\"result\": \"...\", \"status\": \"done\"}).";

        return originalPrompt.addMessage(hint);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext()
                .computeIfAbsent(KEY_HIERARCHY_STATE, k -> new HierarchicalState());

        String lastContent = trace.getLastAgentContent();

        // 1. 状态吸收与错误管理
        if (Utils.isEmpty(lastContent) || lastContent.contains("Error:") || lastContent.contains("Exception:")) {
            state.markError(agent.name(), "Execution failed or empty response.");
        } else {
            state.clearError(agent.name());
            state.absorb(lastContent, this); // 传入 this 以调用 sniffJson
        }

        // 2. 负载统计优化
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_AGENT_USAGE, k -> new HashMap<>());
        usage.put(agent.name(), usage.getOrDefault(agent.name(), 0) + 1);

        super.onAgentEnd(trace, agent);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext().get(KEY_AGENT_USAGE);

        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        sb.append(isZh ? "\n### 团队运行看板 (Hierarchical Dashboard)\n" : "\n### Hierarchical Team Dashboard\n");

        ONode dashboard = (state != null) ? ONode.ofJson(state.toString()) : new ONode().asObject();

        // 将元数据统一放入 _meta，保持业务字段的一级可见性
        if (usage != null && !usage.isEmpty()) {
            dashboard.getOrNew("_meta").set("agent_usage", usage);
        }

        if (dashboard.isEmpty()) {
            sb.append(isZh ? "> 暂无汇报数据，请下达初始指令。\n" : "> No data reported yet. Please issue initial instructions.\n");
        } else {
            sb.append("```json\n").append(dashboard.toJson()).append("\n```\n");
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 管理决策准则：\n");
            sb.append("1. **状态驱动**：根据看板中已有的数据决定下一步由谁补全缺失信息。\n");
            sb.append("2. **错误处理**：若 _meta.errors 存在记录，请尝试指派另一位专家复核或重试该任务。\n");
            sb.append("3. **负载均衡**：避免单一专家连续执行，除非其技能无可替代。");
        } else {
            sb.append("\n### Management Guidelines:\n");
            sb.append("1. **State-Driven**: Use dashboard data to decide who should complete missing information.\n");
            sb.append("2. **Error Recovery**: If _meta.errors exists, consider re-assigning the task to a different expert.\n");
            sb.append("3. **Load Management**: Distribute tasks unless a specific skill is unique to an agent.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_HIERARCHY_STATE);
        trace.getProtocolContext().remove(KEY_AGENT_USAGE);
        super.onTeamFinished(context, trace);
    }
}