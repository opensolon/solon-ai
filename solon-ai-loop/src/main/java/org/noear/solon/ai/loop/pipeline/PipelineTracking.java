package org.noear.solon.ai.loop.pipeline;

import java.time.Instant;
import java.util.*;

/**
 * Pipeline 状态跟踪 —— 对标 oh-my-claudecode 的 PipelineTracking。
 *
 * <p>追踪 Autopilot 管道的执行状态，包括当前阶段、阶段历史和各阶段指标。</p>
 *
 * @since 4.0.3
 */
public class PipelineTracking {

    private final String sessionId;
    private PipelineStage currentStage;
    private final List<StageRecord> stageHistory;
    private Instant startedAt;
    private Instant completedAt;
    private boolean failed;

    public PipelineTracking(String sessionId) {
        this.sessionId = sessionId;
        this.currentStage = PipelineStage.EXPANSION;
        this.stageHistory = new ArrayList<>();
        this.startedAt = Instant.now();
        this.failed = false;
    }

    public String getSessionId() { return sessionId; }
    public PipelineStage getCurrentStage() { return currentStage; }
    public List<StageRecord> getStageHistory() { return Collections.unmodifiableList(stageHistory); }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public boolean isFailed() { return failed; }
    public boolean isCompleted() { return completedAt != null && !failed; }

    /**
     * 记录阶段进入。
     */
    public void enterStage(PipelineStage stage) {
        this.currentStage = stage;
        stageHistory.add(new StageRecord(stage, Instant.now()));
    }

    /**
     * 标记阶段完成。
     */
    public void completeStage(PipelineStage stage, boolean success, String summary) {
        for (StageRecord record : stageHistory) {
            if (record.stage == stage && record.completedAt == null) {
                record.complete(success, summary);
                break;
            }
        }
    }

    /**
     * 标记管道完成。
     */
    public void complete(boolean failed) {
        this.completedAt = Instant.now();
        this.failed = failed;
    }

    /**
     * 跳过阶段。
     */
    public void skipStage(PipelineStage stage) {
        stageHistory.add(new StageRecord(stage, Instant.now(), true));
    }

    /**
     * 获取格式化的状态摘要。
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Pipeline [").append(sessionId).append("]\n");
        sb.append("Status: ").append(completedAt != null ? (failed ? "FAILED" : "COMPLETED") : "RUNNING").append("\n");
        sb.append("Current Stage: ").append(currentStage).append("\n");
        sb.append("Duration: ");
        if (completedAt != null) {
            sb.append(java.time.Duration.between(startedAt, completedAt).getSeconds()).append("s\n");
        } else {
            sb.append(java.time.Duration.between(startedAt, Instant.now()).getSeconds()).append("s (running)\n");
        }
        sb.append("\nStages:\n");
        for (StageRecord record : stageHistory) {
            sb.append("  ").append(record.stage).append(": ");
            if (record.skipped) {
                sb.append("SKIPPED\n");
            } else if (record.completedAt != null) {
                sb.append(record.success ? "PASSED" : "FAILED");
                if (record.summary != null) sb.append(" - ").append(record.summary);
                sb.append("\n");
            } else {
                sb.append("IN PROGRESS\n");
            }
        }
        return sb.toString();
    }

    /**
     * 阶段记录。
     */
    public static class StageRecord {
        private final PipelineStage stage;
        private final Instant enteredAt;
        private Instant completedAt;
        private boolean success;
        private String summary;
        private boolean skipped;

        public StageRecord(PipelineStage stage, Instant enteredAt) {
            this.stage = stage;
            this.enteredAt = enteredAt;
        }

        public StageRecord(PipelineStage stage, Instant enteredAt, boolean skipped) {
            this(stage, enteredAt);
            this.skipped = skipped;
        }

        public void complete(boolean success, String summary) {
            this.completedAt = Instant.now();
            this.success = success;
            this.summary = summary;
        }

        public PipelineStage getStage() { return stage; }
        public Instant getEnteredAt() { return enteredAt; }
        public Instant getCompletedAt() { return completedAt; }
        public boolean isSuccess() { return success; }
        public String getSummary() { return summary; }
        public boolean isSkipped() { return skipped; }
    }
}
