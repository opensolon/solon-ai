package org.noear.solon.ai.loop.prd;

import org.noear.solon.ai.loop.state.disk.DiskStateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PRD 文件管理器 —— 负责 PRD 文档的读写和启动确认。
 *
 * <p>对标 oh-my-claudecode 的 ralph/prd.ts 中的 PRD 文件操作逻辑。</p>
 *
 * @since 4.0.3
 */
public class PRDFileManager {

    private final DiskStateManager stateManager;

    public PRDFileManager(DiskStateManager stateManager) {
        this.stateManager = stateManager;
    }

    /**
     * 读取 PRD 文档。
     *
     * @param sessionId 会话 ID
     * @return PRD 文档，不存在则返回 null
     */
    public PRDDocument readPrd(String sessionId) {
        String json = stateManager.readPrd(sessionId);
        if (json == null) {
            return null;
        }
        return deserializePrd(json);
    }

    /**
     * 写入 PRD 文档。
     *
     * @param sessionId 会话 ID
     * @param prd       PRD 文档
     * @return 是否成功
     */
    public boolean writePrd(String sessionId, PRDDocument prd) {
        String json = serializePrd(prd);
        return stateManager.writePrd(json, sessionId);
    }

    /**
     * 初始化 PRD 文档（自动生成骨架）。
     *
     * @param sessionId   会话 ID
     * @param project     项目名称
     * @param branchName  Git 分支名
     * @param description 总体描述
     * @param stories     用户故事输入
     * @return 创建的 PRD 文档
     */
    public PRDDocument initPrd(String sessionId, String project, String branchName,
                                String description, List<UserStoryInput> stories) {
        PRDDocument prd = new PRDDocument(project, branchName, description);

        int idx = 1;
        for (UserStoryInput input : stories) {
            UserStory story = new UserStory(
                    String.format("US-%03d", idx),
                    input.title,
                    input.description,
                    input.acceptanceCriteria,
                    input.priority
            );
            prd.addUserStory(story);
            idx++;
        }

        writePrd(sessionId, prd);
        return prd;
    }

    /**
     * 确保启动时有有效的 PRD 文档。
     *
     * @param sessionId 会话 ID
     * @return 确认结果
     */
    public EnsurePrdResult ensurePrdForStartup(String sessionId) {
        PRDDocument existing = readPrd(sessionId);
        if (existing != null && !existing.getUserStories().isEmpty()) {
            return new EnsurePrdResult(existing, false);
        }

        // 尝试从 root 查找 prd.json
        String rootJson = null; // 从 root 目录读取
        if (rootJson != null) {
            PRDDocument rootPrd = deserializePrd(rootJson);
            if (rootPrd != null) {
                writePrd(sessionId, rootPrd);
                return new EnsurePrdResult(rootPrd, true);
            }
        }

        return new EnsurePrdResult(null, true);
    }

    /**
     * 标记故事完成。
     */
    public boolean markStoryComplete(String sessionId, String storyId, String notes) {
        PRDDocument prd = readPrd(sessionId);
        if (prd == null) return false;

        UserStory story = prd.findStoryById(storyId);
        if (story == null) return false;

        story.setPasses(true);
        if (notes != null) {
            story.setNotes(notes);
        }
        return writePrd(sessionId, prd);
    }

    /**
     * 标记故事未完成。
     */
    public boolean markStoryIncomplete(String sessionId, String storyId, String notes) {
        PRDDocument prd = readPrd(sessionId);
        if (prd == null) return false;

        UserStory story = prd.findStoryById(storyId);
        if (story == null) return false;

        story.setPasses(false);
        story.setArchitectVerified(false);
        if (notes != null) {
            story.setNotes(notes);
        }
        return writePrd(sessionId, prd);
    }

    /**
     * Architect 验证通过。
     */
    public boolean markStoryArchitectVerified(String sessionId, String storyId) {
        PRDDocument prd = readPrd(sessionId);
        if (prd == null) return false;

        UserStory story = prd.findStoryById(storyId);
        if (story == null || !story.isPasses()) return false;

        story.setArchitectVerified(true);
        return writePrd(sessionId, prd);
    }

    // ===== 序列化/反序列化 =====

    private String serializePrd(PRDDocument prd) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"project\": \"").append(escapeJson(prd.getProject())).append("\",\n");
        sb.append("  \"branchName\": \"").append(escapeJson(prd.getBranchName())).append("\",\n");
        sb.append("  \"description\": \"").append(escapeJson(prd.getDescription())).append("\",\n");
        sb.append("  \"userStories\": [\n");
        List<UserStory> stories = prd.getUserStories();
        for (int i = 0; i < stories.size(); i++) {
            UserStory s = stories.get(i);
            sb.append("    {\n");
            sb.append("      \"id\": \"").append(escapeJson(s.getId())).append("\",\n");
            sb.append("      \"title\": \"").append(escapeJson(s.getTitle())).append("\",\n");
            sb.append("      \"description\": \"").append(escapeJson(s.getDescription())).append("\",\n");
            sb.append("      \"acceptanceCriteria\": [");
            List<String> ac = s.getAcceptanceCriteria();
            for (int j = 0; j < ac.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(ac.get(j))).append("\"");
            }
            sb.append("],\n");
            sb.append("      \"priority\": ").append(s.getPriority()).append(",\n");
            sb.append("      \"passes\": ").append(s.isPasses()).append(",\n");
            sb.append("      \"architectVerified\": ").append(s.isArchitectVerified()).append(",\n");
            sb.append("      \"notes\": \"").append(escapeJson(s.getNotes())).append("\"\n");
            sb.append("    }");
            if (i < stories.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private PRDDocument deserializePrd(String json) {
        try {
            PRDDocument prd = new PRDDocument();

            prd.setProject(extractJsonValue(json, "project"));
            prd.setBranchName(extractJsonValue(json, "branchName"));
            prd.setDescription(extractJsonValue(json, "description"));

            // 解析 userStories 数组（简化实现）
            List<UserStory> stories = new ArrayList<>();
            int storiesStart = json.indexOf("\"userStories\"");
            if (storiesStart >= 0) {
                int arrayStart = json.indexOf("[", storiesStart);
                int arrayEnd = json.lastIndexOf("]");
                if (arrayStart >= 0 && arrayEnd > arrayStart) {
                    String arrayContent = json.substring(arrayStart + 1, arrayEnd);
                    stories = parseStoriesArray(arrayContent);
                }
            }
            prd.setUserStories(stories);
            return prd;
        } catch (Exception e) {
            return null;
        }
    }

    private List<UserStory> parseStoriesArray(String arrayContent) {
        List<UserStory> stories = new ArrayList<>();
        int depth = 0;
        int objStart = -1;

        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String objContent = arrayContent.substring(objStart, i + 1);
                    UserStory story = parseSingleStory(objContent);
                    if (story != null) {
                        stories.add(story);
                    }
                    objStart = -1;
                }
            }
        }
        return stories;
    }

    private UserStory parseSingleStory(String objContent) {
        try {
            UserStory story = new UserStory();
            story.setId(extractJsonValue(objContent, "id"));
            story.setTitle(extractJsonValue(objContent, "title"));
            story.setDescription(extractJsonValue(objContent, "description"));
            story.setPriority(extractJsonInt(objContent, "priority", 5));
            story.setPasses(extractJsonBoolean(objContent, "passes", false));
            story.setArchitectVerified(extractJsonBoolean(objContent, "architectVerified", false));
            story.setNotes(extractJsonValue(objContent, "notes"));

            // 解析 acceptanceCriteria 数组
            List<String> ac = new ArrayList<>();
            int acStart = objContent.indexOf("\"acceptanceCriteria\"");
            if (acStart >= 0) {
                int bracketStart = objContent.indexOf("[", acStart);
                int bracketEnd = objContent.indexOf("]", bracketStart);
                if (bracketStart >= 0 && bracketEnd > bracketStart) {
                    String acContent = objContent.substring(bracketStart + 1, bracketEnd);
                    int si = 0;
                    while ((si = acContent.indexOf("\"", si)) >= 0) {
                        int ei = acContent.indexOf("\"", si + 1);
                        if (ei > si) {
                            ac.add(acContent.substring(si + 1, ei));
                            si = ei + 1;
                        } else break;
                    }
                }
            }
            story.setAcceptanceCriteria(ac);
            return story;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\": \"";
        int start = json.indexOf(searchKey);
        if (start < 0) return "";
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return "";
        return unescapeJson(json.substring(start, end));
    }

    private int extractJsonInt(String json, String key, int defaultValue) {
        String searchKey = "\"" + key + "\": ";
        int start = json.indexOf(searchKey);
        if (start < 0) return defaultValue;
        start += searchKey.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return defaultValue;
        try { return Integer.parseInt(json.substring(start, end)); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private boolean extractJsonBoolean(String json, String key, boolean defaultValue) {
        String searchKey = "\"" + key + "\": ";
        int start = json.indexOf(searchKey);
        if (start < 0) return defaultValue;
        start += searchKey.length();
        if (json.startsWith("true", start)) return true;
        if (json.startsWith("false", start)) return false;
        return defaultValue;
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    // ===== 内部类型 =====

    /**
     * 用户故事输入（用于初始化）。
     */
    public static class UserStoryInput {
        public final String title;
        public final String description;
        public final List<String> acceptanceCriteria;
        public final int priority;

        public UserStoryInput(String title, String description, List<String> acceptanceCriteria, int priority) {
            this.title = title;
            this.description = description;
            this.acceptanceCriteria = acceptanceCriteria;
            this.priority = priority;
        }
    }

    /**
     * PRD 启动确认结果。
     */
    public static class EnsurePrdResult {
        public final PRDDocument prd;
        public final boolean needsCreation;

        public EnsurePrdResult(PRDDocument prd, boolean needsCreation) {
            this.prd = prd;
            this.needsCreation = needsCreation;
        }
    }
}
