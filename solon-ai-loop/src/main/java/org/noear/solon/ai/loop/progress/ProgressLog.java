package org.noear.solon.ai.loop.progress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 进度日志 —— 对标 oh-my-claudecode 的 ralph/progress.ts 中的 ProgressLog。
 *
 * <p>包含 Codebase Patterns 和 ProgressEntry 列表，用于在迭代之间传递经验。</p>
 *
 * @since 4.0.3
 */
public class ProgressLog {
    private final List<CodebasePattern> patterns;
    private final List<ProgressEntry> entries;
    private final String startedAt;

    public ProgressLog() {
        this.patterns = new ArrayList<>();
        this.entries = new ArrayList<>();
        this.startedAt = Instant.now().toString();
    }

    public List<CodebasePattern> getPatterns() { return patterns; }
    public List<ProgressEntry> getEntries() { return entries; }
    public String getStartedAt() { return startedAt; }

    /**
     * 添加代码库模式。
     */
    public void addPattern(String description) {
        patterns.add(new CodebasePattern(description));
    }

    /**
     * 追加进度条目。
     */
    public void addEntry(ProgressEntry entry) {
        entries.add(entry);
    }

    /**
     * 获取最近的 learnings。
     *
     * @param limit 最大条数
     * @return learnings 列表
     */
    public List<String> getRecentLearnings(int limit) {
        List<String> allLearnings = new ArrayList<>();
        for (ProgressEntry entry : entries) {
            allLearnings.addAll(entry.getLearnings());
        }
        int size = allLearnings.size();
        if (size <= limit) return allLearnings;
        return allLearnings.subList(size - limit, size);
    }

    /**
     * 格式化 patterns 用于上下文注入。
     */
    public String formatPatternsForContext() {
        if (patterns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## Codebase Patterns\n");
        for (CodebasePattern p : patterns) {
            sb.append("- ").append(p.getDescription()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化为完整的 context 注入文本。
     */
    public String formatForContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Ralph Progress Log\n");
        sb.append("Started: ").append(startedAt).append("\n\n");

        if (!patterns.isEmpty()) {
            sb.append("## Codebase Patterns\n");
            for (CodebasePattern p : patterns) {
                sb.append("- ").append(p.getDescription()).append("\n");
            }
            sb.append("\n---\n\n");
        }

        for (ProgressEntry entry : entries) {
            sb.append(entry.format());
            sb.append("---\n\n");
        }

        return sb.toString();
    }

    /**
     * 代码库模式。
     */
    public static class CodebasePattern {
        private final String description;
        private final String discoveredAt;

        public CodebasePattern(String description) {
            this.description = description;
            this.discoveredAt = Instant.now().toString();
        }

        public String getDescription() { return description; }
        public String getDiscoveredAt() { return discoveredAt; }

        @Override
        public String toString() {
            return description;
        }
    }
}
