package org.noear.solon.ai.loop.validator.verify;

import java.time.Instant;
import java.util.UUID;

/**
 * 验证状态对象 —— 对标 oh-my-claudecode 的 ralph/verifier.ts 中的 VerificationState。
 *
 * <p>记录单次验证的完整状态，包括尝试次数、反馈、UUID关联等。</p>
 *
 * @since 4.0.3
 */
public class VerificationState {

    // 验证状态常量
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_IMPLEMENTED = "IMPLEMENTED";
    public static final String STATE_AWAITING_REVIEW = "AWAITING_REVIEW";
    public static final String STATE_ARCHITECT_APPROVED = "ARCHITECT_APPROVED";
    public static final String STATE_CRITIC_APPROVED = "CRITIC_APPROVED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_SKIPPED = "SKIPPED";

    // 核心字段
    private boolean pending;
    private String completionClaim;
    private int verificationAttempts;
    private int maxVerificationAttempts;  // 默认 3
    private String architectFeedback;
    private Boolean architectApproved;
    private String requestedAt;
    private String originalTask;
    private String verificationScope;  // "story" 或 "completion"
    private String storyId;
    private String criticMode;         // "architect" / "critic" / "codex"
    private String requestId;          // UUID

    private String currentState;      // 当前状态常量

    public VerificationState() {
        this.maxVerificationAttempts = 3;
        this.pending = false;
        this.verificationAttempts = 0;
        this.currentState = STATE_PENDING;
    }

    /**
     * 启动验证流程。
     */
    public void startVerification(String originalTask, String storyId, String criticMode) {
        this.pending = true;
        this.verificationAttempts = 0;
        this.originalTask = originalTask;
        this.storyId = storyId;
        this.criticMode = criticMode != null ? criticMode : "architect";
        this.requestedAt = Instant.now().toString();
        this.requestId = UUID.randomUUID().toString();
        this.verificationScope = storyId != null ? "story" : "completion";
        this.currentState = STATE_AWAITING_REVIEW;
    }

    /**
     * 记录架构师反馈。
     *
     * @return true 表示验证完成（通过或达到最大尝试次数），false 表示需要继续验证
     */
    public boolean recordArchitectFeedback(boolean approved, String feedback) {
        this.verificationAttempts++;
        this.architectFeedback = feedback;
        this.architectApproved = approved;

        if (approved) {
            this.pending = false;
            this.currentState = STATE_ARCHITECT_APPROVED;
            return true;
        }

        if (verificationAttempts >= maxVerificationAttempts) {
            // 达到最大尝试次数，强制接受
            this.pending = false;
            this.currentState = STATE_FAILED;
            return true;
        }

        // 继续等待验证
        this.currentState = STATE_AWAITING_REVIEW;
        return false;
    }

    /**
     * 清除验证状态。
     */
    public void clear() {
        this.pending = false;
        this.completionClaim = null;
        this.verificationAttempts = 0;
        this.architectFeedback = null;
        this.architectApproved = null;
        this.requestedAt = null;
        this.originalTask = null;
        this.verificationScope = null;
        this.storyId = null;
        this.requestId = null;
        this.currentState = STATE_PENDING;
    }

    /**
     * 自愈：如果 requestId 缺失则自动生成。
     */
    public void ensureRequestId() {
        if (this.requestId == null) {
            this.requestId = UUID.randomUUID().toString();
        }
    }

    // ===== 状态检查方法 =====

    public boolean isPending() { return pending; }
    public boolean isTerminal() {
        return STATE_ARCHITECT_APPROVED.equals(currentState)
                || STATE_CRITIC_APPROVED.equals(currentState)
                || STATE_FAILED.equals(currentState)
                || STATE_SKIPPED.equals(currentState);
    }
    public boolean canTransitionToReview() {
        return STATE_IMPLEMENTED.equals(currentState);
    }
    public String getCurrentState() { return currentState; }

    // ===== Getter/Setter =====

    public String getCompletionClaim() { return completionClaim; }
    public void setCompletionClaim(String completionClaim) { this.completionClaim = completionClaim; }
    public int getVerificationAttempts() { return verificationAttempts; }
    public int getMaxVerificationAttempts() { return maxVerificationAttempts; }
    public void setMaxVerificationAttempts(int max) { this.maxVerificationAttempts = max; }
    public String getArchitectFeedback() { return architectFeedback; }
    public Boolean getArchitectApproved() { return architectApproved; }
    public String getRequestedAt() { return requestedAt; }
    public String getOriginalTask() { return originalTask; }
    public String getVerificationScope() { return verificationScope; }
    public String getStoryId() { return storyId; }
    public String getCriticMode() { return criticMode; }
    public String getRequestId() { return requestId; }
    public void setCurrentState(String state) { this.currentState = state; }
}
