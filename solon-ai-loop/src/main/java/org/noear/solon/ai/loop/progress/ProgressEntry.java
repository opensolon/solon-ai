package org.noear.solon.ai.loop.progress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 进度条目 —— 对标 oh-my-claudecode 的 ralph/progress.ts 中的 ProgressEntry。
 *
 * <p>记录一次迭代实现的具体信息：实现了什么、改了哪些文件、学到了什么。</p>
 *
 * @since 4.0.3
 */
public class ProgressEntry {
    private final String timestamp;
    private final String storyId;
    private final List<String> implementation;
    private final List<String> filesChanged;
    private final List<String> learnings;

    public ProgressEntry(String storyId) {
        this.timestamp = Instant.now().toString();
        this.storyId = storyId;
        this.implementation = new ArrayList<>();
        this.filesChanged = new ArrayList<>();
        this.learnings = new ArrayList<>();
    }

    public ProgressEntry(String storyId, List<String> implementation,
                          List<String> filesChanged, List<String> learnings) {
        this.timestamp = Instant.now().toString();
        this.storyId = storyId;
        this.implementation = new ArrayList<>(implementation);
        this.filesChanged = new ArrayList<>(filesChanged);
        this.learnings = new ArrayList<>(learnings);
    }

    public String getTimestamp() { return timestamp; }
    public String getStoryId() { return storyId; }
    public List<String> getImplementation() { return implementation; }
    public List<String> getFilesChanged() { return filesChanged; }
    public List<String> getLearnings() { return learnings; }

    public void addImplementation(String item) { this.implementation.add(item); }
    public void addFileChanged(String file) { this.filesChanged.add(file); }
    public void addLearning(String learning) { this.learnings.add(learning); }

    /**
     * 格式化为进度文本。
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        String shortTimestamp = timestamp.length() > 19 ? timestamp.substring(0, 19).replace("T", " ") : timestamp;
        sb.append("## [").append(shortTimestamp).append("] - ").append(storyId).append("\n\n");

        if (!implementation.isEmpty()) {
            sb.append("**What was implemented:**\n");
            for (String item : implementation) {
                sb.append("- ").append(item).append("\n");
            }
            sb.append("\n");
        }

        if (!filesChanged.isEmpty()) {
            sb.append("**Files changed:**\n");
            for (String file : filesChanged) {
                sb.append("- ").append(file).append("\n");
            }
            sb.append("\n");
        }

        if (!learnings.isEmpty()) {
            sb.append("**Learnings for future iterations:**\n");
            for (String learning : learnings) {
                sb.append("- ").append(learning).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
