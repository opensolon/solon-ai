package org.noear.solon.ai.loop.state.disk;

import org.noear.solon.ai.loop.state.LoopStateData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 磁盘状态管理器 —— 对标 oh-my-claudecode 的 lib/mode-state-io.ts
 *
 * <p>将循环状态持久化到本地磁盘 JSON 文件，支持：
 * <ul>
 *   <li>Session 隔离：状态文件按 sessionId 分目录存储</li>
 *   <li>原子写入：基于 AtomicWrite 防止写入中断损坏</li>
 *   <li>元数据包裹：自动添加 _meta 信息（写入时间、模式、sessionId）</li>
 *   <li>会话摘要：维护 sessions 索引便于全局查询</li>
 * </ul>
 * </p>
 *
 * <p>文件结构：</p>
 * <pre>
 * .solon-ai-loop/
 * ├── state/
 * │   ├── ralph/{sessionId}.json
 * │   ├── team/{sessionId}.json
 * │   ├── ultraqa/{sessionId}.json
 * │   └── sessions/
 * │       └── {sessionId}.json     // 会话摘要
 * ├── prd/
 * │   └── {sessionId}.json         // PRD 文档
 * └── progress/
 *     └── {sessionId}.txt          // 进度记忆
 * </pre>
 *
 * @since 4.0.3
 */
public class DiskStateManager {

    private static final String BASE_DIR = ".solon-ai-loop";
    private static final String STATE_DIR = "state";
    private static final String SESSIONS_DIR = "sessions";
    private static final String PRD_DIR = "prd";
    private static final String PROGRESS_DIR = "progress";

    private final String rootDirectory;
    private final Path basePath;
    private final Map<String, String> sessionOwnership = new ConcurrentHashMap<>();

    public DiskStateManager(String rootDirectory) {
        this.rootDirectory = rootDirectory;
        this.basePath = Paths.get(rootDirectory, BASE_DIR);
        ensureDirectories();
    }

    /**
     * 获取项目根目录。
     */
    public String getRootDirectory() {
        return rootDirectory;
    }

    // ===== 核心状态读写 =====

    /**
     * 写入状态数据（原子写入 + JSON 序列化）。
     *
     * @param mode      模式名：ralph / team / ultraqa
     * @param state     状态数据
     * @param sessionId 会话 ID
     * @return 是否成功
     */
    public boolean writeState(String mode, LoopStateData state, String sessionId) {
        try {
            Path filePath = getStateFilePath(mode, sessionId);
            String json = serializeStateWithMeta(state, mode, sessionId);
            AtomicWrite.write(filePath, json);
            updateSessionSummary(sessionId, mode);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读取状态数据（带 Session 隔离验证）。
     *
     * @param mode      模式名
     * @param sessionId 会话 ID
     * @return 状态数据，不匹配或不存在时返回 null
     */
    public LoopStateData readState(String mode, String sessionId) {
        try {
            Path filePath = getStateFilePath(mode, sessionId);
            if (!AtomicWrite.exists(filePath)) {
                return null;
            }
            String json = AtomicWrite.read(filePath);
            return deserializeState(json);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 清除状态数据。
     */
    public boolean clearState(String mode, String sessionId) {
        Path filePath = getStateFilePath(mode, sessionId);
        boolean cleared = AtomicWrite.delete(filePath);
        // 清理会话摘要
        Path summaryPath = getSessionSummaryPath(sessionId);
        AtomicWrite.delete(summaryPath);
        sessionOwnership.remove(sessionId);
        return cleared;
    }

    /**
     * 检查是否存在状态。
     */
    public boolean hasState(String mode, String sessionId) {
        Path filePath = getStateFilePath(mode, sessionId);
        return AtomicWrite.exists(filePath);
    }

    // ===== PRD 文档读写 =====

    /**
     * 写入 PRD 文档。
     */
    public boolean writePrd(String jsonContent, String sessionId) {
        try {
            Path filePath = getPrdFilePath(sessionId);
            AtomicWrite.write(filePath, jsonContent);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读取 PRD 文档。
     */
    public String readPrd(String sessionId) {
        try {
            Path filePath = getPrdFilePath(sessionId);
            if (!AtomicWrite.exists(filePath)) {
                return null;
            }
            return AtomicWrite.read(filePath);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 查找 PRD 文件路径（优先按 session，否则按 root）。
     */
    public Path findPrdPath(String sessionId) {
        Path sessionPath = getPrdFilePath(sessionId);
        if (AtomicWrite.exists(sessionPath)) {
            return sessionPath;
        }
        // 回退到 root 级别
        Path rootPath = Paths.get(rootDirectory, "prd.json");
        if (AtomicWrite.exists(rootPath)) {
            return rootPath;
        }
        return sessionPath;
    }

    // ===== 进度记忆读写 =====

    /**
     * 写入进度记忆。
     */
    public boolean writeProgress(String content, String sessionId) {
        try {
            Path filePath = getProgressFilePath(sessionId);
            AtomicWrite.write(filePath, content);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读取进度记忆。
     */
    public String readProgress(String sessionId) {
        try {
            Path filePath = getProgressFilePath(sessionId);
            if (!AtomicWrite.exists(filePath)) {
                return null;
            }
            return AtomicWrite.read(filePath);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 追加进度记忆内容。
     */
    public boolean appendProgress(String content, String sessionId) {
        String existing = readProgress(sessionId);
        if (existing == null) {
            return writeProgress(content, sessionId);
        }
        return writeProgress(existing + "\n" + content, sessionId);
    }

    // ===== Session 隔离验证 =====

    /**
     * 注册 Session 所有权（模式 + sessionId + projectPath 绑定）。
     */
    public void registerSession(String sessionId, String mode, String projectPath) {
        sessionOwnership.put(sessionId, mode + ":" + projectPath);
    }

    /**
     * 验证 Session 所有权。
     *
     * @param sessionId   会话 ID
     * @param expectedMode 期望的模式
     * @param projectPath  项目路径
     * @return 是否匹配
     */
    public boolean validateSession(String sessionId, String expectedMode, String projectPath) {
        String ownership = sessionOwnership.get(sessionId);
        if (ownership == null) {
            return false;
        }
        return ownership.equals(expectedMode + ":" + projectPath);
    }

    /**
     * 获取所有活跃的 Session ID 列表。
     */
    public List<String> getAllActiveSessionIds() {
        return new ArrayList<>(sessionOwnership.keySet());
    }

    // ===== 会话摘要管理 =====

    private void updateSessionSummary(String sessionId, String mode) {
        try {
            Path summaryPath = getSessionSummaryPath(sessionId);
            Map<String, String> summary = new LinkedHashMap<>();
            summary.put("sessionId", sessionId);
            summary.put("mode", mode);
            summary.put("lastUpdated", Instant.now().toString());
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"sessionId\": \"").append(escapeJson(summary.get("sessionId"))).append("\",\n");
            sb.append("  \"mode\": \"").append(escapeJson(summary.get("mode"))).append("\",\n");
            sb.append("  \"lastUpdated\": \"").append(escapeJson(summary.get("lastUpdated"))).append("\"\n");
            sb.append("}\n");
            AtomicWrite.write(summaryPath, sb.toString());
        } catch (IOException ignored) {
        }
    }

    // ===== 路径生成 =====

    private Path getStateFilePath(String mode, String sessionId) {
        return basePath.resolve(STATE_DIR).resolve(mode).resolve(sessionId + ".json");
    }

    private Path getSessionSummaryPath(String sessionId) {
        return basePath.resolve(STATE_DIR).resolve(SESSIONS_DIR).resolve(sessionId + ".json");
    }

    private Path getPrdFilePath(String sessionId) {
        return basePath.resolve(PRD_DIR).resolve(sessionId + ".json");
    }

    private Path getProgressFilePath(String sessionId) {
        return basePath.resolve(PROGRESS_DIR).resolve(sessionId + ".txt");
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(basePath.resolve(STATE_DIR).resolve("ralph"));
            Files.createDirectories(basePath.resolve(STATE_DIR).resolve("team"));
            Files.createDirectories(basePath.resolve(STATE_DIR).resolve("ultraqa"));
            Files.createDirectories(basePath.resolve(STATE_DIR).resolve(SESSIONS_DIR));
            Files.createDirectories(basePath.resolve(PRD_DIR));
            Files.createDirectories(basePath.resolve(PROGRESS_DIR));
        } catch (IOException ignored) {
        }
    }

    // ===== 序列化工具 =====

    private String serializeStateWithMeta(LoopStateData state, String mode, String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_meta\": {\n");
        sb.append("    \"written_at\": \"").append(escapeJson(Instant.now().toString())).append("\",\n");
        sb.append("    \"mode\": \"").append(escapeJson(mode)).append("\",\n");
        sb.append("    \"sessionId\": \"").append(escapeJson(sessionId)).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"data\": {\n");
        sb.append("    \"sessionId\": \"").append(escapeJson(state.getSessionId())).append("\",\n");
        String stateName = state.getState() != null ? state.getState().name() : "UNKNOWN";
        sb.append("    \"state\": \"").append(escapeJson(stateName)).append("\",");
        sb.append("\n");
        sb.append("    \"iterationCount\": ").append(state.getIterationCount()).append(",\n");
        sb.append("    \"successfulIterations\": ").append(state.getSuccessfulIterations()).append(",\n");
        if (state.getStartTime() != null) {
            sb.append("    \"startTime\": \"").append(escapeJson(state.getStartTime().toString())).append("\",\n");
        }
        if (state.getLastUpdateTime() != null) {
            sb.append("    \"lastUpdateTime\": \"").append(escapeJson(state.getLastUpdateTime().toString())).append("\"\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private LoopStateData deserializeState(String json) {
        try {
            // 简单的 JSON 手动解析（避免 Jackson 依赖）
            LoopStateData data = new LoopStateData();

            // 提取 sessionId
            String sessionId = extractJsonValue(json, "sessionId");
            if (sessionId != null) {
                data.setSessionId(sessionId);
            }

            // 提取 state
            String stateStr = extractJsonValue(json, "state");
            if (stateStr != null) {
                try {
                    data.setState(org.noear.solon.ai.loop.state.LoopState.valueOf(stateStr));
                } catch (IllegalArgumentException ignored) {
                }
            }

            // 提取 iterationCount
            Integer iterationCount = extractJsonInt(json, "iterationCount");
            if (iterationCount != null) {
                data.setIterationCount(iterationCount);
            }

            // 提取 successfulIterations
            Integer successCount = extractJsonInt(json, "successfulIterations");
            if (successCount != null) {
                data.setSuccessfulIterations(successCount);
            }

            // 提取时间戳
            String startTime = extractJsonValue(json, "startTime");
            if (startTime != null) {
                try {
                    data.setStartTime(Instant.parse(startTime));
                } catch (Exception ignored) {
                }
            }

            String lastUpdate = extractJsonValue(json, "lastUpdateTime");
            if (lastUpdate != null) {
                try {
                    data.setLastUpdateTime(Instant.parse(lastUpdate));
                } catch (Exception ignored) {
                }
            }

            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\": \"";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private Integer extractJsonInt(String json, String key) {
        String searchKey = "\"" + key + "\": ";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        start += searchKey.length();
        // 找到数字结束位置
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return null;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
