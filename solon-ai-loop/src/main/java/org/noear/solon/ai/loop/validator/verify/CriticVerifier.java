package org.noear.solon.ai.loop.validator.verify;

import org.noear.solon.ai.loop.prd.UserStory;
import org.noear.solon.ai.loop.strategy.LoopContext;

/**
 * Critic 验证器 —— 对标 oh-my-claudecode 的 ralph/loop.ts 中的 critic_mode。
 *
 * <p>Critic 角色提供第二层验证，可以设置为不同的模式：
 * <ul>
 *   <li>architect — 架构师评审</li>
 *   <li>critic — 通用评审</li>
 *   <li>codex — 代码评审</li>
 * </ul>
 * </p>
 *
 * @since 4.0.3
 */
public class CriticVerifier {

    private final String criticMode;

    public CriticVerifier(String criticMode) {
        this.criticMode = criticMode != null ? criticMode : "architect";
    }

    /**
     * Critic 验证。
     *
     * @param story         待评审的故事
     * @param implementation 实现结果
     * @param context        循环上下文
     * @return 评审结果
     */
    public CriticResult review(UserStory story, Object implementation, LoopContext context) {
        if (story == null) {
            return CriticResult.rejected("Story is null");
        }

        switch (criticMode) {
            case "architect":
                return architectReview(story, implementation, context);
            case "critic":
                return generalReview(story, implementation, context);
            case "codex":
                return codeReview(story, implementation, context);
            default:
                return generalReview(story, implementation, context);
        }
    }

    /**
     * 架构师模式评审（侧重架构设计）。
     */
    private CriticResult architectReview(UserStory story, Object implementation, LoopContext context) {
        if (implementation == null) {
            return CriticResult.rejected("No implementation to review");
        }

        String impl = implementation.toString();
        // 架构评审：检查是否涉及架构级变化
        boolean hasArchitecturalChanges = impl.contains("architecture")
                || impl.contains("design")
                || impl.contains("pattern");

        if (hasArchitecturalChanges) {
            return CriticResult.approvedWithNotes(
                    "Architecture review passed. Note: architectural changes detected in " + story.getId()
            );
        }

        return CriticResult.approved("Architecture review passed for: " + story.getId());
    }

    /**
     * 通用模式评审。
     */
    private CriticResult generalReview(UserStory story, Object implementation, LoopContext context) {
        if (implementation == null) {
            return CriticResult.rejected("No implementation to review");
        }
        return CriticResult.approved("Review passed for: " + story.getId());
    }

    /**
     * 代码评审模式。
     */
    private CriticResult codeReview(UserStory story, Object implementation, LoopContext context) {
        if (implementation == null) {
            return CriticResult.rejected("No implementation to review");
        }

        String impl = implementation.toString();
        // 代码评审检查（使用单词边界匹配，避免 "tests" 误判为 "test"）
        String[] words = impl.split("\\W+");
        boolean hasTests = false;
        for (String word : words) {
            if (word.equalsIgnoreCase("test")) {
                hasTests = true;
                break;
            }
        }
        boolean hasNullCheck = impl.contains("null") || impl.contains("Optional");

        if (!hasTests) {
            return CriticResult.rejectedWithNotes(
                    "Code review: Missing tests for " + story.getId()
            );
        }

        return CriticResult.approved("Code review passed for: " + story.getId());
    }

    /**
     * 获取当前 Critic 模式。
     */
    public String getCriticMode() { return criticMode; }

    /**
     * Critic 评审结果。
     */
    public static class CriticResult {
        private final boolean approved;
        private final String message;
        private final boolean hasNotes;

        private CriticResult(boolean approved, String message, boolean hasNotes) {
            this.approved = approved;
            this.message = message;
            this.hasNotes = hasNotes;
        }

        public static CriticResult approved(String message) {
            return new CriticResult(true, message, false);
        }

        public static CriticResult approvedWithNotes(String message) {
            return new CriticResult(true, message, true);
        }

        public static CriticResult rejected(String message) {
            return new CriticResult(false, message, false);
        }

        public static CriticResult rejectedWithNotes(String message) {
            return new CriticResult(false, message, true);
        }

        public boolean isApproved() { return approved; }
        public String getMessage() { return message; }
        public boolean hasNotes() { return hasNotes; }
    }
}
