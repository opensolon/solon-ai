package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 顺序协作协议 (Sequential Protocol)
 *
 * <p>采用线性流水线模式，任务按照预定义的顺序在 Agent 之间严格传递。</p>
 *
 * <p><b>核心机制：</b></p>
 * <ul>
 * <li><b>严格顺序</b>：任务必须按照预设的顺序执行，不能跳步或逆向</li>
 * <li><b>单向流动</b>：信息只能向前传递，每个阶段处理完成后传递给下一阶段</li>
 * <li><b>阶段隔离</b>：每个 Agent 只关注自己的专业领域，不干预其他阶段</li>
 * <li><b>进度跟踪</b>：清晰跟踪当前执行到哪个阶段，预计剩余步骤</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
public class SequentialProtocol_H extends HierarchicalProtocol_H {
    private static final Logger LOG = LoggerFactory.getLogger(SequentialProtocol_H.class);

    // 协议配置
    private boolean enableSkipOnFailure = false; // 失败时是否跳过当前步骤
    private boolean enableStageValidation = true; // 是否启用阶段结果验证
    private boolean enableProgressTracking = true; // 是否启用进度跟踪
    private boolean strictOrderEnforcement = true; // 是否严格执行顺序
    private boolean allowEarlyCompletion = false; // 是否允许提前完成
    private int maxRetriesPerStage = 1; // 每个阶段的最大重试次数

    // 上下文键
    private static final String KEY_CURRENT_STAGE = "current_stage";
    private static final String KEY_STAGE_RESULTS = "stage_results";
    private static final String KEY_STAGE_RETRIES = "stage_retries";
    private static final String KEY_SEQUENCE_ORDER = "sequence_order";

    public SequentialProtocol_H(TeamConfig config) {
        super(config);
    }

    /**
     * 设置失败时是否跳过当前步骤
     */
    public SequentialProtocol_H withSkipOnFailure(boolean enabled) {
        this.enableSkipOnFailure = enabled;
        return this;
    }

    /**
     * 设置是否启用阶段结果验证
     */
    public SequentialProtocol_H withStageValidation(boolean enabled) {
        this.enableStageValidation = enabled;
        return this;
    }

    /**
     * 设置是否启用进度跟踪
     */
    public SequentialProtocol_H withProgressTracking(boolean enabled) {
        this.enableProgressTracking = enabled;
        return this;
    }

    /**
     * 设置是否严格执行顺序
     */
    public SequentialProtocol_H withStrictOrder(boolean strict) {
        this.strictOrderEnforcement = strict;
        return this;
    }

    /**
     * 设置是否允许提前完成
     */
    public SequentialProtocol_H withEarlyCompletion(boolean allowed) {
        this.allowEarlyCompletion = allowed;
        return this;
    }

    /**
     * 设置每个阶段的最大重试次数
     */
    public SequentialProtocol_H withMaxRetriesPerStage(int maxRetries) {
        this.maxRetriesPerStage = Math.max(0, maxRetries);
        return this;
    }

    @Override
    public String name() {
        return "SEQUENTIAL";
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        super.prepareSupervisorInstruction(context, trace, sb);

        if (enableProgressTracking) {
            // 添加顺序协议特有的进度信息
            sb.append("\n=== 流水线进度控制台 ===\n");

            List<String> agentNames = getAgentSequence(trace);
            int currentStage = getCurrentStage(trace);
            int totalStages = agentNames.size();

            sb.append("总阶段数: ").append(totalStages).append("\n");
            sb.append("当前阶段: ").append(currentStage + 1).append(" / ").append(totalStages).append("\n");
            sb.append("已完成阶段: ").append(currentStage).append("\n");
            sb.append("剩余阶段: ").append(totalStages - currentStage).append("\n");

            // 显示阶段详情
            sb.append("\n阶段详情:\n");
            for (int i = 0; i < agentNames.size(); i++) {
                String agentName = agentNames.get(i);
                String status;

                if (i < currentStage) {
                    status = "✅ 已完成";
                } else if (i == currentStage) {
                    status = "▶️ 进行中";
                } else {
                    status = "⏳ 待执行";
                }

                sb.append(i + 1).append(". ").append(status).append(" - ")
                        .append(agentName).append("\n");
            }

            // 进度条可视化
            sb.append("\n进度: [");
            int progressWidth = 20;
            int completedWidth = (int) ((double) currentStage / totalStages * progressWidth);
            for (int i = 0; i < progressWidth; i++) {
                if (i < completedWidth) {
                    sb.append("█");
                } else {
                    sb.append("░");
                }
            }
            sb.append("] ").append((int)((double) currentStage / totalStages * 100)).append("%\n");
        }

        // 添加阶段验证信息（如果启用）
        if (enableStageValidation && trace.getStepCount() > 0) {
            String validationResult = validateLastStageResult(trace);
            if (Utils.isNotEmpty(validationResult)) {
                sb.append("\n阶段验证结果: ").append(validationResult);
            }
        }
    }

    /**
     * 获取Agent执行顺序
     */
    private List<String> getAgentSequence(TeamTrace trace) {
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) trace.getProtocolContext().get(KEY_SEQUENCE_ORDER);

        if (order == null) {
            // 初始化顺序（按添加到配置的顺序）
            order = new ArrayList<>(trace.getConfig().getAgentMap().keySet());
            trace.getProtocolContext().put(KEY_SEQUENCE_ORDER, order);
        }

        return order;
    }

    /**
     * 获取当前阶段索引
     */
    private int getCurrentStage(TeamTrace trace) {
        Integer stage = (Integer) trace.getProtocolContext().get(KEY_CURRENT_STAGE);
        return stage != null ? stage : 0;
    }

    /**
     * 设置当前阶段索引
     */
    private void setCurrentStage(TeamTrace trace, int stage) {
        trace.getProtocolContext().put(KEY_CURRENT_STAGE, stage);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Sequential Protocol - Current stage set to: {}", stage);
        }
    }

    /**
     * 验证上一阶段结果
     */
    private String validateLastStageResult(TeamTrace trace) {
        if (trace.getSteps().isEmpty()) {
            return null;
        }

        TeamTrace.TeamStep lastStep = trace.getSteps().get(trace.getStepCount() - 1);
        String agentName = lastStep.getAgentName();
        String content = lastStep.getContent();

        if (Utils.isEmpty(content)) {
            return "⚠️ 上一阶段(" + agentName + ")输出为空，可能需要重试";
        }

        // 简单的验证逻辑
        if (content.contains("ERROR") || content.contains("错误") ||
                content.contains("FAIL") || content.contains("失败")) {
            return "❌ 上一阶段(" + agentName + ")可能执行失败";
        }

        if (content.contains("FINISH") || content.contains("完成") ||
                content.contains("DONE")) {
            return "✅ 上一阶段(" + agentName + ")已明确完成";
        }

        return "⏳ 上一阶段(" + agentName + ")已执行，等待下一阶段";
    }

    /**
     * 记录阶段结果
     */
    @SuppressWarnings("unchecked")
    private void recordStageResult(TeamTrace trace, String agentName, String result) {
        Map<String, String> stageResults = (Map<String, String>) trace.getProtocolContext()
                .computeIfAbsent(KEY_STAGE_RESULTS, k -> new HashMap<>());

        stageResults.put(agentName, result);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Sequential Protocol - Stage result recorded for {}: {} chars",
                    agentName, result.length());
        }
    }

    /**
     * 增加阶段重试计数
     */
    @SuppressWarnings("unchecked")
    private int incrementStageRetry(TeamTrace trace, String agentName) {
        Map<String, Integer> retries = (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_STAGE_RETRIES, k -> new HashMap<>());

        int count = retries.getOrDefault(agentName, 0) + 1;
        retries.put(agentName, count);

        LOG.warn("Sequential Protocol - Stage {} retry count: {}", agentName, count);
        return count;
    }

    /**
     * 获取阶段重试计数
     */
    @SuppressWarnings("unchecked")
    private int getStageRetryCount(TeamTrace trace, String agentName) {
        Map<String, Integer> retries = (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_STAGE_RETRIES, k -> new HashMap<>());

        return retries.getOrDefault(agentName, 0);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        sb.append("\n## 协作协议：").append(name()).append("\n");

        if (isChinese) {
            sb.append("1. **严格顺序**：任务必须按预设的专家顺序执行");
            if (strictOrderEnforcement) {
                sb.append("，严禁跳步或逆向指派。\n");
            } else {
                sb.append("，但在特殊情况下可以调整。\n");
            }
            sb.append("2. **单向推进**：每个专家完成自己的阶段后，必须传递给下一阶段的专家。\n");
            sb.append("3. **阶段专注**：每个专家只处理自己专业领域的工作，不干预其他阶段。\n");

            if (enableStageValidation) {
                sb.append("4. **质量检查**：每个阶段完成后会进行简单验证，确保可以进入下一阶段。\n");
            }

            if (enableSkipOnFailure) {
                sb.append("5. **容错机制**：如果某个阶段反复失败，可以跳过继续下一阶段。\n");
            } else {
                sb.append("5. **严格质量**：每个阶段必须成功完成才能进入下一阶段。\n");
            }

            if (allowEarlyCompletion) {
                sb.append("6. **提前完成**：如果任务在中间阶段已经完成，可以提前结束流水线。");
            } else {
                sb.append("6. **完整流程**：必须完成所有阶段，即使中间结果已经足够。");
            }

            // 添加重试信息
            if (maxRetriesPerStage > 0) {
                sb.append("\n7. **重试机制**：每个阶段最多可以重试 ").append(maxRetriesPerStage).append(" 次。");
            }
        } else {
            sb.append("1. **Strict Sequence**: Tasks MUST follow the predefined expert order");
            if (strictOrderEnforcement) {
                sb.append("; no skipping or backtracking allowed.\n");
            } else {
                sb.append(", but adjustments are allowed in special cases.\n");
            }
            sb.append("2. **Forward Progression**: Each expert must pass the task to the next stage after completing their work.\n");
            sb.append("3. **Stage Focus**: Each expert only handles their specialized area; no intervention in other stages.\n");

            if (enableStageValidation) {
                sb.append("4. **Quality Check**: Simple validation after each stage to ensure readiness for the next stage.\n");
            }

            if (enableSkipOnFailure) {
                sb.append("5. **Fault Tolerance**: If a stage repeatedly fails, it can be skipped to continue to the next stage.\n");
            } else {
                sb.append("5. **Strict Quality**: Each stage must be completed successfully before proceeding to the next.\n");
            }

            if (allowEarlyCompletion) {
                sb.append("6. **Early Completion**: If the task is completed at an intermediate stage, the pipeline can end early.");
            } else {
                sb.append("6. **Complete Process**: All stages must be completed, even if intermediate results are sufficient.");
            }

            // Add retry information
            if (maxRetriesPerStage > 0) {
                sb.append("\n7. **Retry Mechanism**: Each stage can be retried up to ").append(maxRetriesPerStage).append(" times.");
            }
        }
    }

    @Override
    public boolean shouldSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        List<String> agentNames = getAgentSequence(trace);
        int currentStage = getCurrentStage(trace);

        // 检查是否应该提前完成
        if (allowEarlyCompletion && shouldCompleteEarly(trace, currentStage)) {
            trace.setRoute(Agent.ID_END);
            LOG.info("Sequential Protocol - Early completion triggered at stage {}", currentStage + 1);
            return false; // Supervisor 不需要执行
        }

        // 检查当前阶段是否需要重试或跳过
        if (currentStage < agentNames.size()) {
            String currentAgent = agentNames.get(currentStage);

            // 检查重试次数
            int retryCount = getStageRetryCount(trace, currentAgent);
            if (retryCount >= maxRetriesPerStage) {
                if (enableSkipOnFailure) {
                    LOG.warn("Sequential Protocol - Stage {} failed {} times, skipping",
                            currentAgent, retryCount);
                    setCurrentStage(trace, currentStage + 1);
                    return shouldSupervisorExecute(context, trace); // 递归处理下一阶段
                } else {
                    LOG.error("Sequential Protocol - Stage {} failed {} times, terminating",
                            currentAgent, retryCount);
                    trace.setRoute(Agent.ID_END);
                    trace.addStep(Agent.ID_SUPERVISOR,
                            "流水线在阶段 " + currentAgent + " 失败，已达到最大重试次数", 0);
                    return false;
                }
            }

            // 设置下一阶段路由
            trace.setRoute(currentAgent);

            // 记录这是第几次尝试此阶段
            if (trace.getStepCount() > 0) {
                TeamTrace.TeamStep lastStep = trace.getSteps().get(trace.getStepCount() - 1);
                if (lastStep.getAgentName().equals(currentAgent)) {
                    incrementStageRetry(trace, currentAgent);
                }
            }
        } else {
            // 所有阶段完成
            trace.setRoute(Agent.ID_END);
            LOG.info("Sequential Protocol - All {} stages completed", agentNames.size());
        }

        return false; // Sequential 协议完全控制路由，Supervisor 不需要执行
    }

    /**
     * 检查是否应该提前完成
     */
    private boolean shouldCompleteEarly(TeamTrace trace, int currentStage) {
        if (currentStage == 0) {
            return false; // 第一阶段不能提前完成
        }

        // 检查最近一步是否包含完成信号
        if (trace.getStepCount() > 0) {
            TeamTrace.TeamStep lastStep = trace.getSteps().get(trace.getStepCount() - 1);
            String content = lastStep.getContent();

            if (content != null) {
                // 检查明确的完成信号
                if (content.contains("FINISH") || content.contains("完成") ||
                        content.contains("DONE") || content.contains("全部完成")) {
                    return true;
                }

                // 检查是否已经提供了完整的解决方案
                if ((content.contains("<html>") && content.contains("</html>")) ||
                        (content.contains("完整代码") && content.contains("实现")) ||
                        (content.contains("complete solution") && content.contains("implemented"))) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 顺序协议下，当Agent执行完成时，推进到下一阶段
        if (!Agent.ID_SUPERVISOR.equals(nextAgent) && !Agent.ID_END.equals(nextAgent)) {
            List<String> agentNames = getAgentSequence(trace);
            int currentStage = getCurrentStage(trace);

            // 找到当前Agent在顺序中的位置
            int agentIndex = agentNames.indexOf(nextAgent);
            if (agentIndex >= 0 && agentIndex == currentStage) {
                // 正常执行当前阶段
                LOG.debug("Sequential Protocol - Executing stage {}: {}", currentStage + 1, nextAgent);
            }
        } else if (Agent.ID_END.equals(nextAgent)) {
            LOG.info("Sequential Protocol - Pipeline completed. Total stages: {}",
                    getAgentSequence(trace).size());
        }

        super.onSupervisorRouting(context, trace, nextAgent);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        // 记录阶段结果
        if (trace.getSteps().size() > 0) {
            TeamTrace.TeamStep lastStep = trace.getSteps().get(trace.getSteps().size() - 1);
            if (agent.name().equals(lastStep.getAgentName())) {
                recordStageResult(trace, agent.name(), lastStep.getContent());
            }
        }

        // 推进到下一阶段（如果当前阶段成功完成）
        if (!shouldRetryCurrentStage(trace, agent.name())) {
            int currentStage = getCurrentStage(trace);
            setCurrentStage(trace, currentStage + 1);

            LOG.info("Sequential Protocol - Stage {} ({}) completed, moving to next stage",
                    currentStage + 1, agent.name());
        }

        super.onAgentEnd(trace, agent);
    }

    /**
     * 检查当前阶段是否需要重试
     */
    private boolean shouldRetryCurrentStage(TeamTrace trace, String agentName) {
        if (trace.getSteps().isEmpty()) {
            return false;
        }

        TeamTrace.TeamStep lastStep = trace.getSteps().get(trace.getStepCount() - 1);
        if (!agentName.equals(lastStep.getAgentName())) {
            return false;
        }

        String content = lastStep.getContent();
        if (Utils.isEmpty(content)) {
            return true; // 空输出需要重试
        }

        // 检查是否包含错误或失败信号
        if (content.contains("ERROR") || content.contains("错误") ||
                content.contains("FAIL") || content.contains("失败") ||
                content.contains("无法") || content.contains("不能")) {
            return true;
        }

        // 检查输出是否过于简单（可能未完成）
        if (content.length() < 50 && !content.contains("FINISH") && !content.contains("完成")) {
            return true;
        }

        return false;
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        super.onTeamFinished(context, trace);

        // 清理顺序协议特定的上下文
        trace.getProtocolContext().remove(KEY_CURRENT_STAGE);
        trace.getProtocolContext().remove(KEY_STAGE_RESULTS);
        trace.getProtocolContext().remove(KEY_STAGE_RETRIES);
        trace.getProtocolContext().remove(KEY_SEQUENCE_ORDER);

        if (LOG.isInfoEnabled()) {
            List<String> agentNames = getAgentSequence(trace);
            int completedStages = getCurrentStage(trace);
            LOG.info("Sequential Protocol - Pipeline finished. Completed {}/{} stages",
                    completedStages, agentNames.size());
        }
    }
}