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

import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 黑板协作协议 (Blackboard Protocol)
 *
 * <p>黑板模式是一种经典的协作模式，所有 Agent 共享一个公共的"黑板"（协作历史），
 * 每个 Agent 都可以读取黑板上的信息，并在自己有能力解决问题时写入新的信息。</p>
 *
 * <p><b>核心机制：</b></p>
 * <ul>
 * <li><b>共享状态</b>：所有协作历史作为公共黑板，Agent 可以看到完整上下文</li>
 * <li><b>机会主义协作</b>：Agent 主动识别自己能解决的问题，而不是被动分配</li>
 * <li><b>渐进求精</b>：通过多轮迭代逐步完善解决方案</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class BlackboardProtocol_H extends HierarchicalProtocol_H {
    private static final Logger LOG = LoggerFactory.getLogger(BlackboardProtocol_H.class);

    /** 黑板状态摘要的最大长度 */
    private int blackboardSummaryMaxLength = 1000;

    /** 是否启用智能摘要 */
    private boolean enableSmartSummary = true;

    public BlackboardProtocol_H(TeamConfig config) {
        super(config);
    }

    /**
     * 设置黑板摘要的最大长度
     */
    public BlackboardProtocol_H withSummaryMaxLength(int maxLength) {
        this.blackboardSummaryMaxLength = Math.max(100, maxLength);
        return this;
    }

    /**
     * 启用或禁用智能摘要
     */
    public BlackboardProtocol_H withSmartSummary(boolean enabled) {
        this.enableSmartSummary = enabled;
        return this;
    }

    @Override
    public String name() {
        return "BLACKBOARD";
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        super.prepareSupervisorInstruction(context, trace, sb);

        // 为黑板协议提供特定的历史分析摘要
        String blackboardSummary = generateBlackboardSummary(trace);
        if (blackboardSummary != null && !blackboardSummary.isEmpty()) {
            sb.append("\n\n### 当前黑板状态摘要\n");
            sb.append(blackboardSummary);
        }
    }

    /**
     * 生成黑板状态摘要
     */
    @Nullable
    protected String generateBlackboardSummary(TeamTrace trace) {
        if (trace == null || trace.getSteps().isEmpty()) {
            return null;
        }

        StringBuilder summary = new StringBuilder();
        Locale locale = trace.getConfig().getLocale();
        boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        if (isChinese) {
            summary.append("当前黑板上已有 ").append(trace.getStepCount()).append(" 条记录：\n");
        } else {
            summary.append("Current blackboard has ").append(trace.getStepCount()).append(" entries:\n");
        }

        // 提取关键信息
        int recentSteps = Math.min(5, trace.getStepCount());
        for (int i = Math.max(0, trace.getStepCount() - recentSteps); i < trace.getStepCount(); i++) {
            TeamTrace.TeamStep step = trace.getSteps().get(i);
            String content = extractKeyInfo(step.getContent(), isChinese);

            if (isChinese) {
                summary.append("- **").append(step.getAgentName()).append("**: ")
                        .append(content).append("\n");
            } else {
                summary.append("- **").append(step.getAgentName()).append("**: ")
                        .append(content).append("\n");
            }
        }

        // 分析可能的缺失或问题
        String gapAnalysis = analyzeGaps(trace, isChinese);
        if (gapAnalysis != null && !gapAnalysis.isEmpty()) {
            summary.append("\n").append(gapAnalysis);
        }

        // 限制摘要长度
        if (summary.length() > blackboardSummaryMaxLength && enableSmartSummary) {
            return summary.substring(0, blackboardSummaryMaxLength) + "...";
        }

        return summary.toString();
    }

    /**
     * 从内容中提取关键信息
     */
    private String extractKeyInfo(String content, boolean isChinese) {
        if (content == null || content.isEmpty()) {
            return isChinese ? "无内容" : "No content";
        }

        // 简化内容，保留关键信息
        String simplified = content.replace("\n", " ").trim();

        // 截取合理长度
        int maxLength = 80;
        if (simplified.length() > maxLength) {
            simplified = simplified.substring(0, maxLength) + "...";
        }

        return simplified;
    }

    /**
     * 分析黑板上的信息缺口
     */
    @Nullable
    private String analyzeGaps(TeamTrace trace, boolean isChinese) {
        // 这里可以添加更复杂的缺口分析逻辑
        // 例如：检查是否有设计但无实现，有数据但无分析等

        // 简单的实现：检查最近的步骤类型
        if (trace.getStepCount() >= 2) {
            String lastAgent = trace.getSteps().get(trace.getStepCount() - 1).getAgentName();
            String secondLastAgent = trace.getSteps().get(trace.getStepCount() - 2).getAgentName();

            // 如果连续两个步骤是同一个Agent，可能存在问题
            if (lastAgent.equals(secondLastAgent)) {
                return isChinese ?
                        "注意：同一专家连续执行，可能需要其他专家介入检查。" :
                        "Note: Same expert executed consecutively, may need other expert review.";
            }
        }

        return null;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n## 协作协议：").append(name()).append("\n");
            sb.append("1. **黑板机制**：历史记录即公共黑板，所有专家都可以看到完整的协作历史。\n");
            sb.append("2. **缺口驱动**：主动识别黑板上的信息缺口、矛盾或需要完善的地方。\n");
            sb.append("3. **机会主义协作**：指派最能解决当前最紧迫问题的专家执行。\n");
            sb.append("4. **渐进求精**：通过多轮迭代逐步完善解决方案，每次解决一个具体问题。");
        } else {
            sb.append("\n## Collaboration Protocol: ").append(name()).append("\n");
            sb.append("1. **Blackboard Mechanism**: All experts see the complete collaboration history as a shared blackboard.\n");
            sb.append("2. **Gap-Driven**: Actively identify gaps, contradictions, or areas needing improvement on the blackboard.\n");
            sb.append("3. **Opportunistic Collaboration**: Assign the expert best suited to solve the most pressing current issue.\n");
            sb.append("4. **Progressive Refinement**: Refine the solution through iterations, solving one specific problem at a time.");
        }
    }

    @Override
    public boolean shouldSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        // 黑板模式下，Supervisor 应该总是执行，因为需要动态分析黑板状态
        return true;
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        super.onTeamFinished(context, trace);

        // 黑板协议特定的清理或记录
        if (LOG.isDebugEnabled()) {
            LOG.debug("Blackboard Protocol - Final blackboard state had {} entries", trace.getStepCount());
        }
    }
}