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
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 层级化协作协议 (Hierarchical Protocol)
 *
 * <p>核心特征：引入“运行看板”机制。各成员的产出被结构化提取并呈现在状态看板中，
 * Supervisor 依据看板进度、成员负载及错误记录进行全局调度。</p>
 */
@Preview("3.8.1")
public class HierarchicalProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalProtocol.class);
    private static final String KEY_HIERARCHY_STATE = "hierarchy_state_obj";
    private static final String KEY_AGENT_USAGE = "agent_usage_map";
    private static final int MAX_MEMO_LEN = 250;

    /**
     * 协作状态机：负责汇报数据的吸收、错误记录与状态持久化
     */
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
         * 吸收成员产出：若为 JSON 则结构化合并，若为文本则记录到报告区
         */
        public void absorb(String agentName, String content, TeamProtocolBase protocol) {
            if (Utils.isEmpty(content)) return;

            ONode report = protocol.sniffJson(content);
            if (report.isObject() && !report.isEmpty()) {
                report.remove("agent");
                report.remove("role");
                data.setAll(report.getObjectUnsafe());
            } else {
                String memo = content.trim().replace("\n", " ");
                if (memo.length() > MAX_MEMO_LEN) {
                    memo = memo.substring(0, MAX_MEMO_LEN) + "...";
                }
                data.getOrNew("_reports").set(agentName, memo);
            }
        }

        @Override
        public String toString() { return data.toJson(); }
    }

    public HierarchicalProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() { return "HIERARCHICAL"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        // 固定拓扑：Start -> Supervisor <-> Agents -> End
        spec.addStart(Agent.ID_START).linkAdd(TeamAgent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(TeamAgent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    /**
     * 增强专家指令：注入汇报规范与多模态提醒
     */
    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // 1. 获取基础 Prompt 包装
        Prompt finalPrompt = super.prepareAgentPrompt(trace, agent, originalPrompt, locale);

        // 2. 获取看板状态机
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        StringBuilder sb = new StringBuilder();

        // 注入 A：透传主管的决策分析逻辑
        // 在 SupervisorTask 分发决策时，应将原始决策文本存入 active_instruction
        String activeInstruction = (String) trace.getProtocolContext().get("active_instruction");
        if (Utils.isNotEmpty(activeInstruction)) {
            sb.append(isZh ? "\n\n### 来自主管的最新指令 (Supervisor Directive)\n"
                    : "\n\n### Supervisor Directive\n");
            // 使用引用格式展示主管的分析过程，引导专家理解上下文
            sb.append("> ").append(activeInstruction.trim().replace("\n", "\n> ")).append("\n");
            sb.append("---\n");
        }

        // 注入 B：针对性的错误看板反馈
        if (state != null) {
            ONode dashboardNode = ONode.ofJson(state.toString());
            ONode errors = dashboardNode.get("_meta").get("errors");

            // 如果当前专家在看板中有待处理的错误记录
            if (errors.hasKey(agent.name())) {
                String errorMsg = errors.get(agent.name()).getString();
                sb.append(isZh ? "\n### 错误修正请求：\n" : "\n### Error Correction Required:\n");
                sb.append(isZh ? "- 你之前的执行结果未通过验证：" : "- Your previous response failed validation: ")
                        .append("**").append(errorMsg).append("**\n");
                sb.append(isZh ? "- 请根据主管的最新指令进行修复，并务必包含正确的完成标记。\n"
                        : "- Please fix this based on the directive and ensure the correct finish marker.\n");
            }
        }

        // 3. 强制汇报规范
        sb.append(isZh
                ? "\n### 汇报要求：\n- 请在回复结尾使用 JSON 块反馈核心数据（例：{\"result\": \"...\", \"status\": \"done\"}）。"
                : "\n### Reporting Requirement:\n- End response with a JSON block for key data (e.g., {\"result\": \"...\", \"status\": \"done\"}).");

        // 4. 多模态提醒
        if (originalPrompt.getMessages().stream()
                .filter(m -> m.getRole() == ChatRole.USER)
                .map(m -> (UserMessage) m)
                .anyMatch(UserMessage::hasMedias)) {
            sb.append(isZh
                    ? "\n- [重要]：输入包含多媒体附件，请务必结合视觉或文件内容进行处理。"
                    : "\n- [IMPORTANT]: Multimodal content detected. Process based on the attachments.");
        }

        // 5. 动态追加完成标记提醒
        if (agent instanceof TeamAgent) {
            String marker = ((TeamAgent) agent).getConfig().getFinishMarker();
            if (Utils.isNotEmpty(marker)) {
                sb.append(isZh ? "\n- [注意]：任务完成时，必须在回复中包含标记: " : "\n- [NOTE]: Upon completion, must include marker: ")
                        .append("`").append(marker).append("`\n");
            }
        }

        return super.prepareAgentPrompt(trace, agent, finalPrompt.addMessage(sb.toString()), locale);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        // 1. 获取并初始化状态机（确保 context 中有该对象）
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext()
                .computeIfAbsent(KEY_HIERARCHY_STATE, k -> new HierarchicalState());

        String lastContent = trace.getLastAgentContent();

        // 2. 调用 HierarchicalState 方法进行数据处理
        if (Utils.isEmpty(lastContent)) {
            state.markError(agent.name(), "Response is empty.");
        } else {
            // 1. 动态获取当前 Agent 的完成标记
            // 如果是普通的 Agent，可能没有特定的 finishMarker（默认为空）
            // 如果是 TeamAgent，则可以拿到它配置的标记（如 [DEV_TEAM_FINISH]）
            String marker = null;
            if (agent instanceof TeamAgent) {
                marker = ((TeamAgent) agent).getConfig().getFinishMarker();
            }

            // 2. 判定逻辑：
            // 如果有特定标记，则必须包含标记才算过
            // 如果没有特定标记（普通专家），只要内容不为空且不含有“拒绝/失败”的语义特征即可
            boolean isSuccess = true;
            if (Utils.isNotEmpty(marker)) {
                isSuccess = lastContent.contains(marker);
            } else {
                // 这里可以预留一个简单的语义判定，或者默认普通专家回复即代表完成
                isSuccess = !lastContent.contains("FAILED");
            }

            if (!isSuccess) {
                state.markError(agent.name(), "Task not finished (missing marker: " + marker + ")");
            } else {
                state.clearError(agent.name());
            }

            state.absorb(agent.name(), lastContent, this);
        }

        // 3. 统计负载
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_AGENT_USAGE, k -> new HashMap<>());
        usage.put(agent.name(), usage.getOrDefault(agent.name(), 0) + 1);

        super.onAgentEnd(trace, agent);
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        trace.getProtocolContext().put("active_instruction", decision);
        return super.resolveSupervisorRoute(context, trace, decision);
    }

    /**
     * 实时构建运行看板：注入成员能力、负载与错误信息
     */
    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext().get(KEY_AGENT_USAGE);

        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        if (isZh) {
            sb.append("\n### 运行看板 (Hierarchical Dashboard)\n");
        } else {
            sb.append("\n### Hierarchical Dashboard\n");
        }

        ONode dashboard = (state != null) ? ONode.ofJson(state.toString()) : new ONode().asObject();
        ONode meta = dashboard.getOrNew("_meta");

        if (usage != null && !usage.isEmpty()) { meta.set("agent_usage", usage); }

        ONode capabilities = meta.getOrNew("capabilities");
        config.getAgentMap().forEach((name, ag) -> {
            if (ag.profile() != null) {
                capabilities.set(name, ONode.ofBean(ag.profile().getInputModes()));
            }
        });

        if (dashboard.isEmpty()) {
            if (isZh) {
                sb.append("> 暂无汇报，请下达初始指令。\n");
            } else {
                sb.append("> No reports yet. Issue instructions.\n");
            }
        } else {
            sb.append("```json\n").append(dashboard.toJson()).append("\n```\n");
        }

        if (isZh) {
            sb.append("\n> **决策指引**：对比看板进度。若 `_meta.errors` 存在或关键环节未产出 [FINISH]，请继续调度，严禁提前结束。\n");
        } else {
            sb.append("\n> **Decision Hint**: Check progress. Do not end if `_meta.errors` exist or [FINISH] is missing.\n");
        }

        // ----------

        if (usage != null) {
            List<String> overworked = usage.entrySet().stream()
                    .filter(e -> e.getValue() >= 3) // 针对同一专家的循环阈值
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            if (!overworked.isEmpty()) {
                if (isZh) {
                    sb.append("\n> **![风险预警]**：专家 ").append(overworked)
                            .append(" 已被重复指派多次且未达成目标。请停止无效循环，要求专家提供更详尽的错误说明，或尝试切换到其他专家。");
                } else {
                    sb.append("\n> **![Risk Alert]**: Agents ").append(overworked)
                            .append(" have been called repeatedly. Stop the loop, request detailed feedback, or try a different approach.");
                }
            }
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 管理决策准则：\n");
            sb.append("1. **模态适配**：优先指派支持对应模式（图片/文件）的专家。\n");
            sb.append("2. **错误恢复**：若 `_meta.errors` 存在，必须指派专家处理，直至错误清除。\n");
            sb.append("3. **负载均衡**：参考 `agent_usage` 避免过度使用单一专家。");
        } else {
            sb.append("\n### Management Guidelines:\n");
            sb.append("1. **Modality Match**: Assign experts based on capabilities.\n");
            sb.append("2. **Error Recovery**: Handle `_meta.errors` by re-assignment.\n");
            sb.append("3. **Load Balance**: Use `agent_usage` for efficient distribution.");
        }
    }
}