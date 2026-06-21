package org.noear.solon.ai.loop.pipeline;

import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;

/**
 * Pipeline 阶段定义。
 *
 * <p>对标 oh-my-claudecode 的 Autopilot 多阶段编排中的阶段定义。</p>
 *
 * @since 4.0.3
 */
public enum PipelineStage {
    EXPANSION("需求扩展分析"),
    PLANNING("规划制定"),
    EXECUTION("执行实现"),
    QA("质量检查"),
    VALIDATION("最终验证"),
    COMPLETED("完成"),
    FAILED("失败");

    private final String description;

    PipelineStage(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }

    /**
     * 是否为终态。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * 是否可以取消。
     */
    public boolean isCancellable() {
        return this != COMPLETED && this != FAILED;
    }

    /**
     * 转换为 TeamPipelineStrategy.Phase。
     */
    public TeamPipelineStrategy.Phase toTeamPhase() {
        switch (this) {
            case EXPANSION:
            case PLANNING: return TeamPipelineStrategy.Phase.PLAN;
            case EXECUTION: return TeamPipelineStrategy.Phase.EXEC;
            case QA: return TeamPipelineStrategy.Phase.VERIFY;
            case VALIDATION: return TeamPipelineStrategy.Phase.VERIFY;
            case COMPLETED: return TeamPipelineStrategy.Phase.COMPLETED;
            case FAILED: return TeamPipelineStrategy.Phase.COMPLETED; // 映射为完成
            default: return TeamPipelineStrategy.Phase.PLAN;
        }
    }

    /**
     * 从 TeamPipelineStrategy.Phase 转换。
     */
    public static PipelineStage fromTeamPhase(TeamPipelineStrategy.Phase phase) {
        switch (phase) {
            case PLAN: return PLANNING;
            case PRD: return PLANNING;
            case EXEC: return EXECUTION;
            case VERIFY: return QA;
            case FIX: return EXECUTION;
            case COMPLETED: return COMPLETED;
            case CANCELLED: return FAILED;
            default: return PLANNING;
        }
    }
}
