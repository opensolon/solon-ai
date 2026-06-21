package org.noear.solon.ai.loop.prd;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 用户故事模型 —— 对标 oh-my-claudecode 的 ralph/prd.ts 中的 UserStory 接口。
 *
 * <p>描述一个功能需求的完整定义，包含 ID、标题、描述、验收条件和验证状态。</p>
 *
 * @since 4.0.3
 */
public class UserStory {
    private String id;                           // "US-001"
    private String title;                        // 短标题
    private String description;                  // 用户故事描述
    private List<String> acceptanceCriteria;     // 验收条件
    private int priority;                        // 执行优先级（1=最高）
    private boolean passes;                      // 是否完成
    private boolean architectVerified;           // Architect 是否验证通过
    private String notes;                        // 实现备注

    public UserStory() {
        this.acceptanceCriteria = new ArrayList<>();
        this.priority = 5;
        this.passes = false;
        this.architectVerified = false;
    }

    public UserStory(String id, String title, String description) {
        this();
        this.id = id;
        this.title = title;
        this.description = description;
    }

    public UserStory(String id, String title, String description, List<String> acceptanceCriteria, int priority) {
        this(id, title, description);
        this.acceptanceCriteria = acceptanceCriteria != null ? acceptanceCriteria : new ArrayList<>();
        this.priority = priority;
    }

    // ===== Getters & Setters =====

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getAcceptanceCriteria() { return acceptanceCriteria; }
    public void setAcceptanceCriteria(List<String> acceptanceCriteria) {
        this.acceptanceCriteria = acceptanceCriteria;
    }
    public void addAcceptanceCriteria(String criterion) {
        this.acceptanceCriteria.add(criterion);
    }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isPasses() { return passes; }
    public void setPasses(boolean passes) { this.passes = passes; }

    public boolean isArchitectVerified() { return architectVerified; }
    public void setArchitectVerified(boolean architectVerified) { this.architectVerified = architectVerified; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    /**
     * 故事是否完全通过（passes + architectVerified）。
     */
    public boolean isFullyComplete() {
        return passes && architectVerified;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserStory)) return false;
        UserStory that = (UserStory) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("US[%s] %s (priority=%d, passes=%s, verified=%s)",
                id, title, priority, passes, architectVerified);
    }
}
