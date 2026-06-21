package org.noear.solon.ai.loop.pipeline;

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
}
