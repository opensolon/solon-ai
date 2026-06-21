package org.noear.solon.ai.loop.prd;

import java.util.ArrayList;
import java.util.List;

/**
 * PRD 文档模型 —— 对标 oh-my-claudecode 的 ralph/prd.ts 中的 PRD 数据结构。
 *
 * <p>描述项目的 PRD（产品需求文档），包含项目名称、Git 分支、总体描述和用户故事列表。</p>
 *
 * @since 4.0.3
 */
public class PRDDocument {
    private String project;                    // 项目名
    private String branchName;                 // Git 分支名
    private String description;                // 总体描述
    private List<UserStory> userStories;       // 用户故事列表

    public PRDDocument() {
        this.userStories = new ArrayList<>();
    }

    public PRDDocument(String project, String branchName, String description) {
        this();
        this.project = project;
        this.branchName = branchName;
        this.description = description;
    }

    // ===== Getters & Setters =====

    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<UserStory> getUserStories() { return userStories; }
    public void setUserStories(List<UserStory> userStories) { this.userStories = userStories; }
    public void addUserStory(UserStory story) { this.userStories.add(story); }

    /**
     * 获取下一个待实现的故事（按优先级排序）。
     */
    public UserStory getNextIncompleteStory() {
        return userStories.stream()
                .filter(s -> !s.isPasses())
                .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 检查是否所有故事都已完成（passes=true）。
     */
    public boolean allStoriesCompleted() {
        return userStories.stream().allMatch(UserStory::isPasses);
    }

    /**
     * 检查是否所有故事都完全通过（passes + architectVerified）。
     */
    public boolean allStoriesFullyComplete() {
        return userStories.stream().allMatch(UserStory::isFullyComplete);
    }

    /**
     * 根据 ID 查找故事。
     */
    public UserStory findStoryById(String storyId) {
        return userStories.stream()
                .filter(s -> s.getId().equals(storyId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public String toString() {
        return String.format("PRD[%s] branch=%s, stories=%d, completed=%d",
                project, branchName, userStories.size(),
                userStories.stream().filter(UserStory::isPasses).count());
    }
}
