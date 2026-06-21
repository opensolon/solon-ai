package org.noear.solon.ai.loop.progress;

import org.noear.solon.ai.loop.state.disk.DiskStateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 进度管理器 —— 负责进度文件的读写和管理。
 *
 * <p>对标 oh-my-claudecode 的 ralph/progress.ts 中的进度管理逻辑。</p>
 *
 * @since 4.0.3
 */
public class ProgressManager {

    private final DiskStateManager stateManager;

    public ProgressManager(DiskStateManager stateManager) {
        this.stateManager = stateManager;
    }

    /**
     * 初始化进度日志。
     *
     * @param sessionId 会话 ID
     */
    public boolean initProgress(String sessionId) {
        ProgressLog log = new ProgressLog();
        return writeLog(sessionId, log);
    }

    /**
     * 追加进度条目。
     *
     * @param sessionId 会话 ID
     * @param entry     进度条目
     */
    public boolean appendProgress(String sessionId, ProgressEntry entry) {
        ProgressLog log = readLog(sessionId);
        if (log == null) {
            log = new ProgressLog();
        }
        log.addEntry(entry);
        return writeLog(sessionId, log);
    }

    /**
     * 添加代码库模式。
     *
     * @param sessionId 会话 ID
     * @param pattern   模式描述
     */
    public boolean addPattern(String sessionId, String pattern) {
        ProgressLog log = readLog(sessionId);
        if (log == null) {
            log = new ProgressLog();
        }
        log.addPattern(pattern);
        return writeLog(sessionId, log);
    }

    /**
     * 获取上下文注入文本。
     *
     * @param sessionId 会话 ID
     * @return 格式化的进度文本
     */
    public String getProgressContext(String sessionId) {
        String content = stateManager.readProgress(sessionId);
        if (content != null) {
            System.out.println("[PM] getProgressContext: RAW_CONTENT_START");
            System.out.println(content);
            System.out.println("[PM] getProgressContext: RAW_CONTENT_END");
            System.out.println("[PM] getProgressContext: contains US-001=" + content.contains("US-001"));
        }
        if (content == null || content.trim().isEmpty()) {
            return "No progress log available.";
        }
        // 直接返回原始存储内容，绕过 parseFromContent 的丢失问题
        return content;
    }

    /**
     * 获取最近的 learnings。
     *
     * @param sessionId 会话 ID
     * @param limit     最大条数
     * @return learnings 列表
     */
    public List<String> getRecentLearnings(String sessionId, int limit) {
        ProgressLog log = readLog(sessionId);
        if (log == null) {
            return new ArrayList<>();
        }
        return log.getRecentLearnings(limit);
    }

    /**
     * 格式化 patterns 用于上下文注入。
     *
     * @param sessionId 会话 ID
     * @return 格式化的 patterns 文本
     */
    public String formatPatternsForContext(String sessionId) {
        ProgressLog log = readLog(sessionId);
        if (log == null) {
            return "";
        }
        return log.formatPatternsForContext();
    }

    /**
     * 读取进度日志。
     */
    private ProgressLog readLog(String sessionId) {
        String content = stateManager.readProgress(sessionId);
        if (content == null) {
            return null;
        }
        ProgressLog log = parseFromContent(content);
        System.out.println("[PM] readLog: entriesFound=" + (log != null ? log.getEntries().size() : 0) + " patternsFound=" + (log != null ? log.getPatterns().size() : 0));
        return log;
    }

    /**
     * 写入进度日志。
     */
    private boolean writeLog(String sessionId, ProgressLog log) {
        String content = serializeLog(log);
        System.out.println("[PM] writeLog: writing with entries=" + log.getEntries().size() + " patterns=" + log.getPatterns().size());
        System.out.println("[PM] writeLog: CONTENT_START");
        System.out.print(content);
        System.out.println("[PM] writeLog: CONTENT_END");
        return stateManager.writeProgress(content, sessionId);
    }

    /**
     * 序列化 ProgressLog 为 JSON 字符串。
     * 使用手写 JSON 序列化，无需外部依赖。
     */
    private String serializeLog(ProgressLog log) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"startedAt\": \"").append(escapeJson(log.getStartedAt())).append("\",\n");

        // patterns
        sb.append("  \"patterns\": [");
        List<ProgressLog.CodebasePattern> patterns = log.getPatterns();
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\n    {");
            sb.append("\"description\": \"").append(escapeJson(patterns.get(i).getDescription())).append("\"");
            sb.append("}");
        }
        sb.append("\n  ],\n");

        // entries
        sb.append("  \"entries\": [");
        List<ProgressEntry> entries = log.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            ProgressEntry e = entries.get(i);
            sb.append("\n    {");
            sb.append("\"storyId\": \"").append(escapeJson(e.getStoryId())).append("\",");
            sb.append("\"timestamp\": \"").append(escapeJson(e.getTimestamp())).append("\",");
            sb.append("\"implementation\": [");
            for (int j = 0; j < e.getImplementation().size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(e.getImplementation().get(j))).append("\"");
            }
            sb.append("],");
            sb.append("\"filesChanged\": [");
            for (int j = 0; j < e.getFilesChanged().size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(e.getFilesChanged().get(j))).append("\"");
            }
            sb.append("],");
            sb.append("\"learnings\": [");
            for (int j = 0; j < e.getLearnings().size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(e.getLearnings().get(j))).append("\"");
            }
            sb.append("]");
            sb.append("}");
        }
        sb.append("\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 从 JSON 反序列化 ProgressLog。
     */
    private ProgressLog parseFromContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }

        ProgressLog log = new ProgressLog();

        // 简单 JSON 解析：提取 patterns
        int patternsIdx = content.indexOf("\"patterns\"");
        if (patternsIdx >= 0) {
            int arrStart = content.indexOf('[', patternsIdx);
            if (arrStart >= 0) {
                int arrEnd = findMatchingBracket(content, arrStart);
                if (arrEnd > arrStart) {
                    String patternsArr = content.substring(arrStart + 1, arrEnd);
                    int pos = 0;
                    while (true) {
                        int objStart = patternsArr.indexOf('{', pos);
                        if (objStart < 0) break;
                        int objEnd = patternsArr.indexOf('}', objStart);
                        if (objEnd < 0) break;
                        String obj = patternsArr.substring(objStart + 1, objEnd);
                        String desc = extractJsonString(obj, "description");
                        if (desc != null) {
                            log.addPattern(desc);
                        }
                        pos = objEnd + 1;
                    }
                }
            }
        }

        // 提取 entries
        int entriesIdx = content.indexOf("\"entries\"");
        if (entriesIdx >= 0) {
            int arrStart = content.indexOf('[', entriesIdx);
            if (arrStart >= 0) {
                int arrEnd = findMatchingBracket(content, arrStart);
                if (arrEnd > arrStart) {
                    String entriesArr = content.substring(arrStart + 1, arrEnd);
                    int pos = 0;
                    while (true) {
                        int objStart = entriesArr.indexOf('{', pos);
                        if (objStart < 0) break;
                        int objEnd = entriesArr.indexOf('}', objStart);
                        if (objEnd < 0) break;
                        String obj = entriesArr.substring(objStart + 1, objEnd);

                        String storyId = extractJsonString(obj, "storyId");
                        String timestamp = extractJsonString(obj, "timestamp");
                        List<String> impl = extractJsonArray(obj, "implementation");
                        List<String> files = extractJsonArray(obj, "filesChanged");
                        List<String> learningsList = extractJsonArray(obj, "learnings");

                        if (storyId != null) {
                            ProgressEntry entry = new ProgressEntry(storyId, impl, files, learningsList);
                            log.addEntry(entry);
                        }
                        pos = objEnd + 1;
                    }
                }
            }
        }

        return log;
    }

    /**
     * 找到匹配的闭合括号（跟踪所有括号类型 []{} 的完整嵌套）。
     */
    private int findMatchingBracket(String s, int openIdx) {
        char open = s.charAt(openIdx);
        char close = (open == '[') ? ']' : '}';
        int depth = 1;
        boolean inString = false;
        for (int i = openIdx + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) continue;
            if (c == '\\' && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 从 JSON 对象中提取指定 key 的字符串值。
     */
    private String extractJsonString(String json, String key) {
        // 匹配 "key":" 或 "key": "  (支持带空格和不带空格)
        String withSpace = "\"" + key + "\": \"";
        String noSpace = "\"" + key + "\":\"";
        int idx = json.indexOf(withSpace);
        int valStart;
        if (idx >= 0) {
            valStart = idx + withSpace.length();
        } else {
            idx = json.indexOf(noSpace);
            if (idx < 0) return null;
            valStart = idx + noSpace.length();
        }
        int end = json.indexOf('"', valStart);
        if (end < 0) return null;
        return unescapeJson(json.substring(valStart, end));
    }

    /**
     * 从 JSON 对象中提取指定 key 的字符串数组。
     */
    private List<String> extractJsonArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String withSpace = "\"" + key + "\": [";
        String noSpace = "\"" + key + "\":[";
        int idx = json.indexOf(withSpace);
        if (idx < 0) {
            idx = json.indexOf(noSpace);
        }
        if (idx < 0) return result;
        int bracketStart = json.indexOf('[', idx);
        if (bracketStart < 0) return result;
        int arrStart = bracketStart + 1;
        int arrEnd = json.indexOf(']', arrStart);
        if (arrEnd < 0) return result;
        String arrContent = json.substring(arrStart, arrEnd);
        // 解析 ["a","b","c"]
        int pos = 0;
        while (true) {
            int qStart = arrContent.indexOf('"', pos);
            if (qStart < 0) break;
            int qEnd = arrContent.indexOf('"', qStart + 1);
            if (qEnd < 0) break;
            result.add(unescapeJson(arrContent.substring(qStart + 1, qEnd)));
            pos = qEnd + 1;
        }
        return result;
    }

    /**
     * JSON 转义。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * JSON 反转义。
     */
    private String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
