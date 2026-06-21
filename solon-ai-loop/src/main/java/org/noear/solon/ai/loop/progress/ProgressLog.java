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
     * 添加代码库模式（支持占位符替换）。
     *
     * <p>如果 patterns 中只有一个 placeholder 条目 "You haven't added any patterns yet." 或空白条目，
     * 在添加第一个真实 pattern 时替换它。</p>
     */
    public void addPattern(String description) {
        // 占位符替换：如果 patterns 只有一个无效条目，替换它
        if (patterns.size() == 1) {
            String existing = patterns.get(0).getDescription();
            if (existing == null || existing.trim().isEmpty()
                    || existing.contains("haven't added any patterns")
                    || existing.contains("还没有添加")) {
                patterns.set(0, new CodebasePattern(description));
                return;
            }
        }
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
     * 格式化 patterns 用于上下文注入（XML 标签格式）。
     */
    public String formatPatternsForContext() {
        if (patterns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<codebase_patterns>\n");
        for (CodebasePattern p : patterns) {
            sb.append("  <pattern>").append(escapeXml(p.getDescription())).append("</pattern>\n");
        }
        sb.append("</codebase_patterns>\n");
        return sb.toString();
    }

    /**
     * 格式化为完整的 context 注入文本（XML 标签格式，用于 LLM 上下文注入）。
     */
    public String formatForContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("<progress_log>\n");
        sb.append("  <started_at>").append(escapeXml(startedAt)).append("</started_at>\n");

        if (!patterns.isEmpty()) {
            sb.append("  <codebase_patterns>\n");
            for (CodebasePattern p : patterns) {
                sb.append("    <pattern>").append(escapeXml(p.getDescription())).append("</pattern>\n");
            }
            sb.append("  </codebase_patterns>\n");
        }

        sb.append("  <entries>\n");
        for (ProgressEntry entry : entries) {
            sb.append(entry.format());
        }
        sb.append("  </entries>\n");
        sb.append("</progress_log>\n");

        return sb.toString();
    }

    // ===== XML 转义 =====

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
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
