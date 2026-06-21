package org.noear.solon.ai.loop.validator.verify;

/**
 * 验证状态枚举 —— 对标 oh-my-claudecode 的 ralph/prd.ts 中的验证状态机。
 *
 * <p>描述用户故事的验证生命周期。</p>
 *
 * @since 4.0.3
 */
public enum VerificationState {
    PENDING("待实现"),
    IMPLEMENTED("已实现"),
    AWAITING_REVIEW("等待 Architect 审核"),
    ARCHITECT_APPROVED("Architect 通过"),
    CRITIC_APPROVED("Critic 通过"),
    FAILED("失败"),
    SKIPPED("跳过");

    private final String description;

    VerificationState(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }

    /**
     * 是否可以进入 Architect 审核。
     */
    public boolean canTransitionToReview() {
        return this == IMPLEMENTED;
    }

    /**
     * 是否为终态。
     */
    public boolean isTerminal() {
        return this == ARCHITECT_APPROVED || this == CRITIC_APPROVED
                || this == FAILED || this == SKIPPED;
    }
}
