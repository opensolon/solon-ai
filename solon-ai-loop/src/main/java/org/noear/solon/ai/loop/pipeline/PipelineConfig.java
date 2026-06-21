package org.noear.solon.ai.loop.pipeline;

import org.noear.solon.ai.loop.strategy.LoopStrategy;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;

import java.util.*;

/**
 * Pipeline 配置 —— 对标 oh-my-claudecode 的 PipelineConfig。
 *
 * <p>配置 Autopilot 管道中各个阶段的启用/禁用状态，
 * 以及各阶段应使用的循环策略。</p>
 *
 * @since 4.0.3
 */
public class PipelineConfig {

    private final boolean expansionEnabled;
    private final boolean planningEnabled;
    private final boolean executionEnabled;
    private final boolean qaEnabled;
    private final boolean validationEnabled;
    private final Map<PipelineStage, LoopStrategy> stageStrategyMap;

    private PipelineConfig(Builder builder) {
        this.expansionEnabled = builder.expansionEnabled;
        this.planningEnabled = builder.planningEnabled;
        this.executionEnabled = builder.executionEnabled;
        this.qaEnabled = builder.qaEnabled;
        this.validationEnabled = builder.validationEnabled;
        this.stageStrategyMap = new HashMap<>(builder.stageStrategyMap);
    }

    public boolean isExpansionEnabled() { return expansionEnabled; }
    public boolean isPlanningEnabled() { return planningEnabled; }
    public boolean isExecutionEnabled() { return executionEnabled; }
    public boolean isQaEnabled() { return qaEnabled; }
    public boolean isValidationEnabled() { return validationEnabled; }

    /**
     * 获取某个阶段是否启用。
     */
    public boolean isStageEnabled(PipelineStage stage) {
        switch (stage) {
            case EXPANSION: return expansionEnabled;
            case PLANNING: return planningEnabled;
            case EXECUTION: return executionEnabled;
            case QA: return qaEnabled;
            case VALIDATION: return validationEnabled;
            default: return true;
        }
    }

    /**
     * 获取某个阶段使用的策略。
     */
    public LoopStrategy getStrategyForStage(PipelineStage stage) {
        LoopStrategy strategy = stageStrategyMap.get(stage);
        if (strategy != null) return strategy;
        // 默认策略
        switch (stage) {
            case EXECUTION: return new RalphLoopStrategy();
            case QA: return new UltraQAStrategy();
            case EXPANSION:
            case PLANNING:
            case VALIDATION:
            default:
                return new TeamPipelineStrategy();
        }
    }

    /**
     * 获取启用的阶段列表（按执行顺序）。
     */
    public List<PipelineStage> getEnabledStages() {
        List<PipelineStage> stages = new ArrayList<>();
        if (expansionEnabled) stages.add(PipelineStage.EXPANSION);
        if (planningEnabled) stages.add(PipelineStage.PLANNING);
        if (executionEnabled) stages.add(PipelineStage.EXECUTION);
        if (qaEnabled) stages.add(PipelineStage.QA);
        if (validationEnabled) stages.add(PipelineStage.VALIDATION);
        return stages;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean expansionEnabled = true;
        private boolean planningEnabled = true;
        private boolean executionEnabled = true;
        private boolean qaEnabled = true;
        private boolean validationEnabled = true;
        private final Map<PipelineStage, LoopStrategy> stageStrategyMap = new HashMap<>();

        public Builder expansionEnabled(boolean v) { this.expansionEnabled = v; return this; }
        public Builder planningEnabled(boolean v) { this.planningEnabled = v; return this; }
        public Builder executionEnabled(boolean v) { this.executionEnabled = v; return this; }
        public Builder qaEnabled(boolean v) { this.qaEnabled = v; return this; }
        public Builder validationEnabled(boolean v) { this.validationEnabled = v; return this; }

        public Builder strategyForStage(PipelineStage stage, LoopStrategy strategy) {
            this.stageStrategyMap.put(stage, strategy);
            return this;
        }

        public PipelineConfig build() { return new PipelineConfig(this); }
    }
}
