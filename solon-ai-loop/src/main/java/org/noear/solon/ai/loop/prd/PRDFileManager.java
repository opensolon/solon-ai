package org.noear.solon.ai.loop.prd;

import org.noear.snack4.ONode;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        Path rootPrdPath = Paths.get(stateManager.getRootDirectory(), "prd.json");
        if (Files.exists(rootPrdPath)) {
            try {
                String rootJson = new String(Files.readAllBytes(rootPrdPath), java.nio.charset.StandardCharsets.UTF_8);
                PRDDocument rootPrd = deserializePrd(rootJson);
                if (rootPrd != null) {
                    writePrd(sessionId, rootPrd);
                    return new EnsurePrdResult(rootPrd, true);
                }
            } catch (IOException e) {
                // 读取失败则忽略
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

    // ===== PRD 格式化输出（用于 LLM prompt 注入） =====

    /**
     * 生成下一个待实现故事的 LLM prompt。
     * 对标 oh-my-claudecode 的 formatNextStoryPrompt。
     *
     * @param prd PRD 文档
     * @return XML 标签格式的 prompt 文本，如果全部完成则返回空字符串
     */
    public String formatNextStoryPrompt(PRDDocument prd) {
        if (prd == null) return "";

        UserStory nextStory = prd.getNextIncompleteStory();
        if (nextStory == null) {
            return "";  // 所有故事已完成
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<next_story>\n");
        sb.append("  <storyId>").append(escapeXml(nextStory.getId())).append("</storyId>\n");
        sb.append("  <title>").append(escapeXml(nextStory.getTitle())).append("</title>\n");
        if (nextStory.getDescription() != null && !nextStory.getDescription().trim().isEmpty()) {
            sb.append("  <description>").append(escapeXml(nextStory.getDescription())).append("</description>\n");
        }
        if (!nextStory.getAcceptanceCriteria().isEmpty()) {
            sb.append("  <acceptance_criteria>\n");
            for (String ac : nextStory.getAcceptanceCriteria()) {
                sb.append("    <criterion>").append(escapeXml(ac)).append("</criterion>\n");
            }
            sb.append("  </acceptance_criteria>\n");
        }
        sb.append("  <priority>").append(nextStory.getPriority()).append("</priority>\n");
        sb.append("</next_story>\n");
        return sb.toString();
    }

    /**
     * 格式化单个故事的输出（包含状态标记）。
     * 对标 oh-my-claudecode 的 formatStory。
     *
     * @param story 用户故事
     * @return 格式化文本
     */
    public String formatStory(UserStory story) {
        if (story == null) return "";

        StringBuilder sb = new StringBuilder();
        String status;
        if (story.isArchitectVerified()) {
            status = "COMPLETE";
        } else if (story.isPasses()) {
            status = "AWAITING_VERIFICATION";
        } else {
            status = "PENDING";
        }

        sb.append("<story>\n");
        sb.append("  <id>").append(escapeXml(story.getId())).append("</id>\n");
        sb.append("  <title>").append(escapeXml(story.getTitle())).append("</title>\n");
        sb.append("  <status>").append(status).append("</status>\n");
        if (story.getNotes() != null && !story.getNotes().trim().isEmpty()) {
            sb.append("  <notes>").append(escapeXml(story.getNotes())).append("</notes>\n");
        }
        sb.append("</story>\n");
        return sb.toString();
    }

    /**
     * 检测内容中是否包含 -no-prd / --no-prd 标记。
     * 对标 oh-my-claudecode 的 detectNoPrdFlag。
     *
     * @param content 用户输入内容
     * @return true 如果包含 no-prd 标记
     */
    public static boolean detectNoPrdFlag(String content) {
        if (content == null) return false;
        return Pattern.compile("-?-?no-prd\\b", Pattern.CASE_INSENSITIVE).matcher(content).find();
    }

    /**
     * 移除内容中的 -no-prd / --no-prd 标记。
     * 对标 oh-my-claudecode 的 stripNoPrdFlag。
     *
     * @param content 用户输入内容
     * @return 移除标记后的内容
     */
    public static String stripNoPrdFlag(String content) {
        if (content == null) return "";
        return content.replaceAll("(?i)-?-?no-prd\\b", "").trim();
    }

    /**
     * 检测内容中的 critic mode 标记，返回模式值。
     * 对标 oh-my-claudecode 的 detectCriticModeFlag。
     *
     * <p>支持格式：-critic-mode arch, --critic-mode arch, -cm arch, --cm arch</p>
     *
     * @param content 用户输入内容
     * @return 检测到的 critic mode，未发现则返回 "architect" 默认值
     */
    public static String detectCriticModeFlag(String content) {
        if (content == null) return "architect";
        Matcher m = Pattern.compile("-?-?(?:critic-mode|cm)\\s+(\\S+)", Pattern.CASE_INSENSITIVE).matcher(content);
        if (m.find()) {
            String mode = m.group(1).toLowerCase();
            if (mode.equals("arch") || mode.equals("architect")) {
                return "architect";
            } else if (mode.equals("critic") || mode.equals("full")) {
                return "critic";
            } else if (mode.equals("none") || mode.equals("off")) {
                return "none";
            }
            return mode;
        }
        return "architect";
    }

    /**
     * 移除内容中的 critic mode 标记。
     * 对标 oh-my-claudecode 的 stripCriticModeFlag。
     *
     * @param content 用户输入内容
     * @return 移除标记后的内容
     */
    public static String stripCriticModeFlag(String content) {
        if (content == null) return "";
        return content.replaceAll("(?i)-?-?(?:critic-mode|cm)\\s+\\S+", "").trim();
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

    // ===== 序列化/反序列化 =====

    private String serializePrd(PRDDocument prd) {
        ONode root = new ONode();
        root.set("project", prd.getProject());
        root.set("branchName", prd.getBranchName());
        root.set("description", prd.getDescription());

        ONode storiesNode = new ONode().asArray();
        root.set("userStories", storiesNode);
        for (UserStory s : prd.getUserStories()) {
            ONode storyNode = storiesNode.addNew();
            storyNode.set("id", s.getId());
            storyNode.set("title", s.getTitle());
            storyNode.set("description", s.getDescription());

            ONode acNode = new ONode().asArray();
            storyNode.set("acceptanceCriteria", acNode);
            for (String ac : s.getAcceptanceCriteria()) {
                acNode.add(ac);
            }

            storyNode.set("priority", s.getPriority());
            storyNode.set("passes", s.isPasses());
            storyNode.set("architectVerified", s.isArchitectVerified());
            storyNode.set("notes", s.getNotes());
        }
        return root.toJson();
    }

    private PRDDocument deserializePrd(String json) {
        ONode root = ONode.ofJson(json);
        if (root.isNull()) {
            return null;
        }

        PRDDocument prd = new PRDDocument();
        prd.setProject(root.get("project").getString());
        prd.setBranchName(root.get("branchName").getString());
        prd.setDescription(root.get("description").getString());

        List<UserStory> stories = new ArrayList<>();
        ONode storiesNode = root.get("userStories");
        if (storiesNode.isArray()) {
            for (ONode sn : storiesNode.getArray()) {
                UserStory story = new UserStory();
                story.setId(sn.get("id").getString());
                story.setTitle(sn.get("title").getString());
                story.setDescription(sn.get("description").getString());
                story.setPriority(sn.get("priority").getInt());
                story.setPasses(sn.get("passes").getBoolean());
                story.setArchitectVerified(sn.get("architectVerified").getBoolean());
                story.setNotes(sn.get("notes").getString());

                List<String> ac = new ArrayList<>();
                ONode acNode = sn.get("acceptanceCriteria");
                if (acNode.isArray()) {
                    for (ONode acItem : acNode.getArray()) {
                        ac.add(acItem.getString());
                    }
                }
                story.setAcceptanceCriteria(ac);
                stories.add(story);
            }
        }
        prd.setUserStories(stories);
        return prd;
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
