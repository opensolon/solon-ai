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
import java.util.regex.Pattern;
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

    protected static final String KEY_HIERARCHY_STATE = "hierarchy_state_obj";
    protected static final String KEY_AGENT_USAGE = "agent_usage_map";
    /** 主管当前轮派发指令，供专家 prepareAgentPrompt 透传 */
    protected static final String KEY_ACTIVE_INSTRUCTION = "active_instruction";
    /** 同一专家被重复指派的过载告警阈值 */
    protected static final int OVERWORK_THRESHOLD = 3;
    protected static final int MAX_MEMO_LEN = 250;
    protected static final String STATUS_FAILED = "FAILED";
    protected static final Pattern STATUS_FAILED_PATTERN = Pattern.compile("\\b" + STATUS_FAILED + "\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 协作状态机：负责汇报数据的吸收、错误记录与状态持久化
     */
    public static class HierarchicalState {
        private final ONode data = new ONode().asObject();

        /**
         * 直接访问内存看板，避免反复 serialize/parse
         */
        public ONode data() {
            return data;
        }

        /**
         * 是否已有业务汇报（_reports 或 _results），与 meta 能力信息无关
         */
        public boolean hasReports() {
            return (data.hasKey("_reports") && !data.get("_reports").isEmpty())
                    || (data.hasKey("_results") && !data.get("_results").isEmpty());
        }

        public ONode errors() {
            ONode meta = data.get("_meta");
            if (!meta.isObject()) {
                return new ONode().asObject();
            }
            ONode errors = meta.get("errors");
            return errors.isObject() ? errors : new ONode().asObject();
        }

        public boolean hasErrors() {
            ONode errors = errors();
            return errors.isObject() && !errors.isEmpty();
        }

        public void markError(String agentName, String error) {
            data.getOrNew("_meta").getOrNew("errors").set(agentName, error);
        }

        public void clearError(String agentName) {
            ONode errors = errors();
            if (errors.isObject()) {
                errors.remove(agentName);
            }
        }

        /**
         * 吸收成员产出：若为 JSON 则结构化合并，若为文本则记录到报告区
         * <p>JSON 嗅探与基类 sniffJson 一致：取首个 '{' 到末个 '}'；
         * 多段 JSON 或正文夹杂花括号时可能误裁，属启发式限制。</p>
         */
        public void absorb(String agentName, String content, TeamProtocolBase protocol) {
            if (Utils.isEmpty(content)) return;
            ONode report = protocol.sniffJson(content);
            absorb(agentName, content, report);
        }

        /**
         * 吸收成员产出（预解析报告，避免重复嗅探）
         */
        public void absorb(String agentName, String content, ONode report) {
            if (Utils.isEmpty(content)) return;

            // 1. 提取结构化数据存入 _results (用于逻辑计算)
            if (report.isObject() && !report.isEmpty()) {
                data.getOrNew("_results").set(agentName, report);
            }

            // 2. 按 sniffJson 起止精确裁剪 JSON，保留前后正文
            String cleanContent = absorbCleanContent(content, report);

            if (cleanContent.length() > MAX_MEMO_LEN) {
                cleanContent = cleanContent.substring(0, MAX_MEMO_LEN) + "...";
            }
            data.getOrNew("_reports").set(agentName, cleanContent);
        }

        private String absorbCleanContent(String content, ONode report) {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start == -1 || end <= start) {
                return content;
            }
            String before = content.substring(0, start)
                    .replaceAll("```\\w*\\s*\\n?$", "")
                    .trim();
            String after = content.substring(end + 1)
                    .replaceAll("^\\s*```", "")
                    .trim();
            String result = (before + (before.isEmpty() || after.isEmpty() ? "" : " ") + after).trim();
            // 纯 JSON 回复时报告区给兜底摘要，避免看板空白
            if (Utils.isEmpty(result)) {
                result = (report.isObject() && !report.isEmpty()) ? report.toJson() : content;
            }
            return result;
        }

        @Override
        public String toString() {
            return data.toJson();
        }
    }

    public HierarchicalProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "HIERARCHICAL";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        // 固定拓扑：Start -> Supervisor <-> Agents -> End
        // Supervisor 无自环边；错误阻断等场景应 return null，交由 commitRoute 继续解析/下一轮决策
        spec.addStart(Agent.ID_START).linkAdd(TeamAgent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(TeamAgent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        // 保留基类身份 profile + 协作历史
        super.injectAgentInstruction(context, agent, locale, sb);

        boolean isZh = isChinese(locale);

        // 增加明显的区块分割
        sb.append("\n\n---"); // 视觉分割线，帮助模型切分注意力
        sb.append(isZh ? "\n## 协作与汇报规范\n" : "\n## Collaboration & Reporting\n");

        // 1. 强制汇报规范
        sb.append(isZh
                ? "\n1. 汇报要求：\n- 请在回复结尾使用 JSON 块反馈核心数据（例：{\"result\": \"...\", \"status\": \"done\"}）。"
                : "\n1. Reporting Requirement:\n- End response with a JSON block for key data (e.g., {\"result\": \"...\", \"status\": \"done\"}).");

        // 2. 动态追加完成标记提醒
        if (agent instanceof TeamAgent) {
            String marker = ((TeamAgent) agent).getConfig().getFinishMarker();
            if (Utils.isNotEmpty(marker)) {
                sb.append(isZh ? "\n2. **完成标记**：任务完成时，必须包含标记: " : "\n2. **Finish Marker**: Upon completion, must include: ")
                        .append("`").append(marker).append("`\n");
            }
        }
    }

    /**
     * 增强专家指令：注入 Pre-Context、主管指令与错误修正。
     * <p>基类已 {@link Prompt#copy()}；此处只在副本上追加，禁止原地改 workingMemory。</p>
     */
    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // 1. 基类已返回 Prompt.copy() 副本
        Prompt finalPrompt = super.prepareAgentPrompt(trace, agent, originalPrompt, locale);

        // 2. 获取看板状态机
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
        boolean isZh = isChinese(locale);
        StringBuilder sb = new StringBuilder(512);

        // 逻辑：专家不看全局看板，但能看到“上一个同事干了什么”，解决断层
        String lastAgent = trace.getLastAgentName();
        if (state != null && Utils.isNotEmpty(lastAgent)) {
            ONode reports = state.data().get("_reports");
            if (reports.hasKey(lastAgent)) {
                String lastSummary = reports.get(lastAgent).getString();

                sb.append(isZh ? "\n\n### 前置背景 (Pre-Context)\n" : "\n\n### Pre-Context\n");
                sb.append(isZh ? "- 来自 " : "- From ").append(lastAgent).append(": ")
                        .append("**").append(lastSummary).append("**\n");

                sb.append(isZh ? "> 请务必基于上述背景数据进行处理，不要修改原始关键信息。\n"
                        : "> Please process based on the data above without altering core information.\n");
            }
        }

        // 注入 A：透传主管的决策分析逻辑
        String activeInstruction = (String) trace.getProtocolContext().get(KEY_ACTIVE_INSTRUCTION);
        if (Utils.isNotEmpty(activeInstruction)) {
            sb.append(isZh ? "\n\n### 来自主管的最新指令 (Supervisor Directive)\n"
                    : "\n\n### Supervisor Directive\n");
            // 使用引用格式展示主管的分析过程，引导专家理解上下文
            sb.append("> ").append(activeInstruction.trim().replace("\n", "\n> ")).append("\n");
            sb.append("---\n");
        }

        // 注入 B：针对性的错误看板反馈
        if (state != null) {
            ONode errors = state.errors();
            if (errors.hasKey(agent.name())) {
                String errorMsg = errors.get(agent.name()).getString();
                sb.append(isZh ? "\n### 错误修正请求：\n" : "\n### Error Correction Required:\n");
                sb.append(isZh ? "- 你之前的执行结果未通过验证：" : "- Your previous response failed validation: ")
                        .append("**").append(errorMsg).append("**\n");
                sb.append(isZh ? "- 请根据主管的最新指令进行修复，并务必包含正确的完成标记。\n"
                        : "- Please fix this based on the directive and ensure the correct finish marker.\n");
            }
        }

        // 4. 多模态提醒（基于副本消息列表判断）
        if (finalPrompt.getMessages().stream()
                .filter(m -> m.getRole() == ChatRole.USER)
                .map(m -> (UserMessage) m)
                .anyMatch(UserMessage::isMultiModal)) {
            sb.append(isZh
                    ? "\n- [重要]：输入包含多媒体附件，请务必结合视觉或文件内容进行处理。"
                    : "\n- [IMPORTANT]: Multimodal content detected. Process based on the attachments.");
        }

        if (sb.length() > 0) {
            finalPrompt.addMessage(sb.toString());
        }

        return finalPrompt;
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
            // 完成判定：
            // - 嵌套 TeamAgent：子团队 call() 返回的是已收口的 finalAnswer，通常已剥离 [XXX_FINISH]；
            //   不应再强制要求正文含 finishMarker，否则父看板会误标记子团队未完成。
            // - 普通专家：内容不含 FAILED 语义即视为完成。
            boolean isSuccess;
            String failReason = null;
            ONode report = sniffJson(lastContent);
            boolean textFailed = STATUS_FAILED_PATTERN.matcher(lastContent).find();
            boolean statusFailed = false;
            if (report.isObject() && report.hasKey("status")) {
                statusFailed = STATUS_FAILED.equalsIgnoreCase(report.get("status").getString());
            }
            
            if (agent instanceof TeamAgent) {
                TeamAgent nested = (TeamAgent) agent;
                TeamTrace nestedTrace = (trace.getSession() != null)
                        ? nested.getTrace(trace.getSession())
                        : null;
                boolean nestedFinished = nestedTrace != null
                        && (Agent.ID_END.equals(nestedTrace.getRoute())
                        || Utils.isNotEmpty(nestedTrace.getFinalAnswer()));
                String marker = nested.getConfig().getFinishMarker();
                // 子团队已结束，或交付文本仍带 finishMarker，或仅以非 FAILED 产出为准
                isSuccess = nestedFinished
                        || (Utils.isNotEmpty(marker) && lastContent.contains(marker))
                        || (!textFailed && !statusFailed);
                if (!isSuccess) {
                    failReason = "Nested team task not finished (missing completion signal).";
                }
            } else {
                isSuccess = !textFailed && !statusFailed;
                if (!isSuccess) {
                    failReason = "Task reported as failed (content or status contains FAILED).";
                }
            }

            if (!isSuccess) {
                state.markError(agent.name(), failReason);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("HierarchicalProtocol: agent [{}] validation failed: {}", agent.name(), failReason);
                }
            } else {
                state.clearError(agent.name());
            }

            if (report != null) {
                state.absorb(agent.name(), lastContent, report);
            } else {
                state.absorb(agent.name(), lastContent, this);
            }
        }

        // 专家本轮执行完毕，清除主管指令，避免跨轮污染下一专家
        trace.getProtocolContext().remove(KEY_ACTIVE_INSTRUCTION);

        // 3. 统计负载
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_AGENT_USAGE, k -> new HashMap<>());
        usage.put(agent.name(), usage.getOrDefault(agent.name(), 0) + 1);

        super.onAgentEnd(trace, agent);
    }

    /**
     * 解析主管路由。
     * <ul>
     *   <li>finish + 看板有错误：注入系统反馈；优先回派出错专家（避免 commitRoute 在无法模糊匹配时误入 END）</li>
     *   <li>finish + 无错误：return null，交给 {@link SupervisorTask#commitRoute} 走
     *       {@link #shouldSupervisorRoute} SOP 守卫后再 END</li>
     *   <li>普通派发：写入 active_instruction 后委托基类匹配 Agent</li>
     * </ul>
     */
    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision != null && decision.contains(config.getFinishMarker())) {
            HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
            if (state != null && state.hasErrors()) {
                boolean isZh = isChinese(config.getLocale());
                String errorHint = isZh
                        ? "【系统干预】由于看板中仍有未解决的错误，任务不能结束。请指派相关专家修复。"
                        : "[System] Task cannot end because errors exist. Assign agents to fix them.";

                // 1. 注入系统反馈（使用 ID_SYSTEM，避免污染 bidding 语义）
                trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SYSTEM, errorHint, 0);
                // 2. 将干预说明写入 active_instruction，辅助下一轮专家 Prompt
                trace.getProtocolContext().put(KEY_ACTIVE_INSTRUCTION, errorHint);

                LOG.warn("HierarchicalProtocol: blocking FINISH due to unresolved errors on dashboard");

                // 优先回派第一个仍登记在册的出错专家。
                // 说明：commitRoute 在 resolve 返回非空时直接 routeTo，不再走 finish 分支；
                // 若此处 return null 且 decision 不含 agent 名，matchAgentRoute 失败会误入 END。
                String fixTarget = firstErrorAgent(state);
                if (Utils.isNotEmpty(fixTarget)) {
                    return fixTarget;
                }
                return null;
            }
        
            // 正常 finish：不在此直接 ID_END，交由 commitRoute + shouldSupervisorRoute 统一收口
            // （保留 SOP 完备性检查 isLogicFinished）
            return null;
        }
    
        if (Utils.isNotEmpty(decision)) {
            trace.getProtocolContext().put(KEY_ACTIVE_INSTRUCTION, decision);
        }
        return super.resolveSupervisorRoute(context, trace, decision);
    }
    
    /**
     * 终结审计：看板仍有未解决错误时拦截 FINISH（与 Blackboard 待办/FAILED 守卫同构）。
     * 到达 maxTurns 后放行，避免死循环；编排模式（graphAdjuster）由基类决定。
     */
    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision != null && decision.contains(config.getFinishMarker())) {
            HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
            if (state != null && state.hasErrors()
                    && trace.getTurnCount() < trace.getOptions().getMaxTurns()) {
                LOG.warn("HierarchicalProtocol: Physical Block! Dashboard still has errors. Turn: {}",
                        trace.getTurnCount());
                return false;
            }
        }
        return super.shouldSupervisorRoute(context, trace, decision);
    }
    
    /**
     * 从看板 errors 中取第一个仍在团队中的专家名，供 FINISH 阻断时强制回派。
     */
    protected String firstErrorAgent(HierarchicalState state) {
        if (state == null || !state.hasErrors()) {
            return null;
        }
        ONode errors = state.errors();
        if (!errors.isObject() || errors.isEmpty()) {
            return null;
        }
        for (String name : errors.getObjectUnsafe().keySet()) {
            if (config.getAgentMap().containsKey(name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 实时构建运行看板：注入成员能力、负载与错误信息
     */
    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        HierarchicalState state = (HierarchicalState) trace.getProtocolContext().get(KEY_HIERARCHY_STATE);
        Map<String, Integer> usage = (Map<String, Integer>) trace.getProtocolContext().get(KEY_AGENT_USAGE);

        boolean isZh = isChinese(config.getLocale());
        if (isZh) {
            sb.append("\n## 运行看板 (Hierarchical Dashboard)\n");
        } else {
            sb.append("\n## Hierarchical Dashboard\n");
        }

        // 增量构建渲染用看板，避免全量序列化深拷贝
        // 仅复制业务 keys（_reports, _results）和已有错误，再注入运行时 meta
        ONode view = new ONode().asObject();
        if (state != null) {
            ONode dashboard = state.data();
            ONode reports = dashboard.get("_reports");
            if (reports.isObject() && !reports.isEmpty()) {
                view.set("_reports", ONode.ofJson(reports.toJson()));
            }
            ONode results = dashboard.get("_results");
            if (results.isObject() && !results.isEmpty()) {
                view.set("_results", ONode.ofJson(results.toJson()));
            }
            ONode errors = dashboard.get("_meta").get("errors");
            if (errors.isObject() && !errors.isEmpty()) {
                view.getOrNew("_meta").set("errors", ONode.ofJson(errors.toJson()));
            }
        }
        ONode meta = view.getOrNew("_meta");

        if (usage != null && !usage.isEmpty()) {
            meta.set("agent_usage", usage);
        }

        ONode capabilities = meta.getOrNew("capabilities");
        config.getAgentMap().forEach((name, ag) -> {
            if (ag.profile() != null) {
                capabilities.set(name, ONode.ofBean(ag.profile().getInputModes()));
            }
        });

        // 以业务汇报是否存在为准，而非 view.isEmpty()（capabilities 总会写入）
        boolean hasReports = state != null && state.hasReports();
        if (!hasReports) {
            if (isZh) {
                sb.append("> 暂无汇报，请下达初始指令。\n");
            } else {
                sb.append("> No reports yet. Issue instructions.\n");
            }
            // 首轮仍展示能力与负载元信息，便于主管选人
            if (!capabilities.isEmpty() || (usage != null && !usage.isEmpty())) {
                sb.append("```json\n").append(view.toJson()).append("\n```\n");
            }
        } else {
            sb.append("```json\n").append(view.toJson()).append("\n```\n");
        }

        // 动态 SOP（与 injectSupervisorInstruction 静态准则分工：此处强调流程与交付）
        if (isZh) {
            sb.append("\n### 派发指令准则 (Standard Operating Procedure):\n");
            sb.append("> 1. **显式数据传递**：指派专家时，必须复述看板 `_results` 中的核心参数。\n");
            sb.append("\n### 管理与决策指引 (Management & Decision Hints):\n");
            sb.append("> **决策指引**：对比看板进度。若 `_meta.errors` 存在或关键环节未产出，请继续调度，严禁提前结束。\n");
            sb.append("> **流程完整性**：确保设计与执行角色（如 `implementer`）均已参与。严禁跳过关键执行环节直接结束。\n");
            sb.append("> **终结交付**：若认为任务已达成，请输出 `").append(config.getFinishMarker())
                    .append("` 并在此回复中**汇总最终代码或方案**给用户。\n");
            sb.append("> **注意**：输出标记后系统将立即停止，你将无法再次指派专家，请务必在这一步提供完整的交付内容。\n");
            sb.append("> **负载建议**：参考 `agent_usage` 避免过度指派。若专家多次失败，请尝试更换专家或调整指令。\n");
        } else {
            sb.append("\n### Directive Standards (SOP):\n");
            sb.append("> 1. **Explicit Data Transfer**: Always restate core parameters from `_results`.\n");
            sb.append("\n### Management & Decision Hints:\n");
            sb.append("> **Decision Hint**: Check progress. Keep scheduling if errors exist or key steps are missing.\n");
            sb.append("> **Workflow Integrity**: Ensure both design and implementation (e.g., `implementer`) roles have participated. Do not skip execution steps.\n");
            sb.append("> **Final Delivery**: If satisfied, output `").append(config.getFinishMarker())
                    .append("` and **consolidate the final code/result** in this response for the USER.\n");
            sb.append("> **Crucial**: Once the marker is output, the workflow ends. You cannot assign agents anymore. Ensure the final delivery is complete.\n");
            sb.append("> **Load Balance**: Refer to `agent_usage`. If an agent fails repeatedly, switch approach or adjust instructions.\n");
        }

        if (usage != null) {
            List<String> overworked = usage.entrySet().stream()
                    .filter(e -> e.getValue() >= OVERWORK_THRESHOLD)
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

        boolean isZh = isChinese(locale);
        // 静态管理准则（动态看板/流程提示放 prepareSupervisorInstruction，避免重复堆叠）
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

    /**
     * 判断是否为中文语言环境
     */
    protected static boolean isChinese(Locale locale) {
        return Locale.CHINA.getLanguage().equals(locale.getLanguage());
    }
}
