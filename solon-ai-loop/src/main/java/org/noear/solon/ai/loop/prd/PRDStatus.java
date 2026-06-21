package org.noear.solon.ai.loop.prd;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PRD 状态模型 —— 表示 PRD 文档的当前完成状态。
 *
 * <p>对标 oh-my-claudecode 的 getPrdStatus 函数返回的状态信息。</p>
 *
 * @since 4.0.3
 */
public class PRDStatus {
    private final int total;
    private final int completed;
    private final int pending;
    private final int verified;
    private final boolean allComplete;
    private final boolean allFullyComplete;
    private final UserStory nextStory;
    private final List<String> incompleteIds;

    public PRDStatus(int total, int completed, int pending, int verified,
                     boolean allComplete, boolean allFullyComplete,
                     UserStory nextStory, List<String> incompleteIds) {
        this.total = total;
        this.completed = completed;
        this.pending = pending;
        this.verified = verified;
        this.allComplete = allComplete;
        this.allFullyComplete = allFullyComplete;
        this.nextStory = nextStory;
        this.incompleteIds = incompleteIds;
    }

    public int getTotal() { return total; }
    public int getCompleted() { return completed; }
    public int getPending() { return pending; }
    public int getVerified() { return verified; }
    public boolean isAllComplete() { return allComplete; }
    public boolean isAllFullyComplete() { return allFullyComplete; }
    public UserStory getNextStory() { return nextStory; }
    public List<String> getIncompleteIds() { return incompleteIds; }

    /**
     * 获取完成百分比。
     */
    public double getCompletionPercentage() {
        if (total == 0) return 100.0;
        return (double) completed / total * 100.0;
    }

    /**
     * 获取格式化的进度摘要。
     */
    public String getSummary() {
        return String.format("PRD进度: %d/%d 已完成 (%.1f%%), %d 待办, %d 已验证",
                completed, total, getCompletionPercentage(), pending, verified);
    }

    @Override
    public String toString() {
        return getSummary();
    }
}
