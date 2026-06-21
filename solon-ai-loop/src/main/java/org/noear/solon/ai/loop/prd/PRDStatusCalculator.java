package org.noear.solon.ai.loop.prd;

import org.noear.solon.ai.loop.state.disk.DiskStateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PRD 状态计算器 —— 计算 PRD 文档的完成状态。
 *
 * <p>对标 oh-my-claudecode 的 getPrdStatus 函数。</p>
 *
 * @since 4.0.3
 */
public class PRDStatusCalculator {

    /**
     * 计算 PRD 文档的当前状态。
     *
     * @param prd PRD 文档
     * @return 状态对象
     */
    public PRDStatus calculate(PRDDocument prd) {
        if (prd == null || prd.getUserStories() == null) {
            return new PRDStatus(0, 0, 0, 0, true, true, null, new ArrayList<>());
        }

        List<UserStory> stories = prd.getUserStories();
        int total = stories.size();
        int completed = (int) stories.stream().filter(UserStory::isPasses).count();
        int verified = (int) stories.stream().filter(UserStory::isArchitectVerified).count();
        int pending = total - completed;
        boolean allComplete = completed == total;
        boolean allFullyComplete = stories.stream().allMatch(s -> s.isPasses() && s.isArchitectVerified());

        UserStory nextStory = prd.getNextIncompleteStory();
        List<String> incompleteIds = stories.stream()
                .filter(s -> !s.isPasses())
                .map(UserStory::getId)
                .collect(Collectors.toList());

        return new PRDStatus(total, completed, pending, verified,
                allComplete, allFullyComplete, nextStory, incompleteIds);
    }

    /**
     * 快速检查 PRD 是否已全部完成。
     */
    public boolean isAllComplete(PRDDocument prd) {
        return prd != null && prd.allStoriesCompleted();
    }

    /**
     * 获取下一个待实现的故事（按优先级排序）。
     */
    public UserStory getNextStory(PRDDocument prd) {
        if (prd == null) return null;
        return prd.getNextIncompleteStory();
    }

    /**
     * 获取格式化的 PRD 上下文文本（用于注入到 AI 提示中）。
     */
    public String formatForContext(PRDDocument prd) {
        if (prd == null) return "No PRD available.";

        StringBuilder sb = new StringBuilder();
        sb.append("# PRD: ").append(prd.getProject()).append("\n");
        sb.append("Branch: ").append(prd.getBranchName()).append("\n");
        if (prd.getDescription() != null && !prd.getDescription().trim().isEmpty()) {
            sb.append("Description: ").append(prd.getDescription()).append("\n");
        }
        sb.append("\n");

        PRDStatus status = calculate(prd);
        sb.append("## Status: ").append(status.getSummary()).append("\n\n");

        sb.append("## User Stories\n");
        for (UserStory story : prd.getUserStories()) {
            String statusIcon = story.isArchitectVerified() ? "[✓]" :
                    story.isPasses() ? "[~]" : "[ ]";
            sb.append(statusIcon).append(" ")
                    .append(story.getId()).append(": ")
                    .append(story.getTitle())
                    .append(" (priority=").append(story.getPriority()).append(")\n");

            if (story.getDescription() != null && !story.getDescription().trim().isEmpty()) {
                sb.append("  Description: ").append(story.getDescription()).append("\n");
            }
            if (!story.getAcceptanceCriteria().isEmpty()) {
                sb.append("  Acceptance Criteria:\n");
                for (String ac : story.getAcceptanceCriteria()) {
                    sb.append("    - ").append(ac).append("\n");
                }
            }
            if (story.getNotes() != null && !story.getNotes().trim().isEmpty()) {
                sb.append("  Notes: ").append(story.getNotes()).append("\n");
            }
        }

        return sb.toString();
    }
}
