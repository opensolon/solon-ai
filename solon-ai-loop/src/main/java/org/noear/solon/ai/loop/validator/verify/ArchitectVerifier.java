package org.noear.solon.ai.loop.validator.verify;

import org.noear.solon.ai.loop.prd.PRDDocument;
import org.noear.solon.ai.loop.prd.UserStory;
import org.noear.solon.ai.loop.strategy.LoopContext;

/**
 * Architect 验证器 —— 对标 oh-my-claudecode 的 ralph/prd.ts 中的 architectVerified 机制。
 *
 * <p>Architect 角色负责审核用户故事的实现质量，确保代码层面通过后，
 * 再由 Architect 做架构层面的确认。</p>
 *
 * @since 4.0.3
 */
public class ArchitectVerifier {

    /**
     * Architect 验证一个用户故事。
     *
     * @param story         待验证的故事
     * @param implementation 实现结果
     * @param context        循环上下文
     * @return 验证结果
     */
    public VerificationResult verify(UserStory story, Object implementation, LoopContext context) {
        if (story == null) {
            return VerificationResult.failed("Story is null");
        }
        if (!story.isPasses()) {
            return VerificationResult.failed("Story has not been implemented yet");
        }

        // 基础架构验证
        String storyTitle = story.getTitle();
        Object info = context.get("implementation_result");

        // 默认：检查实现是否包含故事标题的关键信息
        boolean architectPassed = checkArchitectCriteria(story, implementation);

        if (architectPassed) {
            return VerificationResult.passed("Architect verification passed for: " + story.getId());
        } else {
            return VerificationResult.failed("Architect verification failed for: " + story.getId()
                    + ". Implementation does not meet architectural standards.");
        }
    }

    /**
     * 判断故事是否完全通过（passes + architectVerified）。
     */
    public boolean isStoryComplete(UserStory story) {
        return story != null && story.isPasses() && story.isArchitectVerified();
    }

    /**
     * 检查架构标准。
     */
    private boolean checkArchitectCriteria(UserStory story, Object implementation) {
        if (implementation == null) return false;
        String impl = implementation.toString().toLowerCase();

        // 检查验收条件是否被实现覆盖
        for (String ac : story.getAcceptanceCriteria()) {
            if (!containsIgnoreCase(impl, ac)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        if (text == null || keyword == null) return false;
        return text.contains(keyword.toLowerCase());
    }

    /**
     * 验证结果。
     */
    public static class VerificationResult {
        private final boolean passed;
        private final String message;

        private VerificationResult(boolean passed, String message) {
            this.passed = passed;
            this.message = message;
        }

        public static VerificationResult passed(String message) {
            return new VerificationResult(true, message);
        }

        public static VerificationResult failed(String message) {
            return new VerificationResult(false, message);
        }

        public boolean isPassed() { return passed; }
        public String getMessage() { return message; }
    }
}
