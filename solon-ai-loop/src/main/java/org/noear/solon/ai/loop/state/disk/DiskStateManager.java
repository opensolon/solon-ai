package org.noear.solon.ai.loop.state.disk;

import org.noear.snack4.ONode;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.state.LoopStateData;
import org.noear.solon.ai.loop.state.StateManager;
import org.noear.solon.ai.loop.state.disk.FilePermissionUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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
 * <p>序列化采用 ONode（org.noear.snack4），完整支持 contextData 和 metadata
 * 嵌套对象的序列化与反序列化。</p>
 *
 * @since 4.0.3
 */
public class DiskStateManager implements StateManager {

    private static final String[] MODES = {"ralph", "team", "ultraqa"};

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

    // ===== StateManager 接口实现 =====

    /**
     * {@inheritDoc}
     *
     * <p>内部委托 writeState，模式从 contextData 中的 mode 字段推断。</p>
     */
    @Override
    public void saveState(String sessionId, LoopStateData state) {
        String mode = getModeFromState(state);
        writeState(mode, state, sessionId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>依次尝试 ralph / team / ultraqa 三个目录查找状态文件，返回首个匹配结果。</p>
     */
    @Override
    public LoopStateData loadState(String sessionId) {
        for (String mode : MODES) {
            LoopStateData data = readState(mode, sessionId);
            if (data != null) {
                return data;
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>清理 ralph / team / ultraqa 三个目录下该 session 的所有状态文件及摘要。</p>
     */
    @Override
    public void clearState(String sessionId) {
        for (String mode : MODES) {
            clearState(mode, sessionId);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>检查 ralph / team / ultraqa 任一目录下是否存在该 session 的状态文件。</p>
     */
    @Override
    public boolean hasState(String sessionId) {
        for (String mode : MODES) {
            if (hasState(mode, sessionId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getAllSessionIds() {
        return new ArrayList<>(sessionOwnership.keySet());
    }

    /**
     * {@inheritDoc}
     *
     * <p>遍历所有模式目录下的状态文件，删除最后修改时间超过 maxAge 的文件。</p>
     */
    @Override
    public int cleanupExpiredStates(long maxAge) {
        long cutoffMillis = System.currentTimeMillis() - maxAge;
        int cleaned = 0;
        for (String mode : MODES) {
            Path modeDir = basePath.resolve(STATE_DIR).resolve(mode);
            if (!Files.exists(modeDir)) {
                continue;
            }
            List<Path> files = new ArrayList<>();
            try (Stream<Path> paths = Files.list(modeDir)) {
                paths.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            } catch (IOException ignored) {
            }
            for (Path file : files) {
                try {
                    long lastModified = Files.getLastModifiedTime(file).toMillis();
                    if (lastModified < cutoffMillis) {
                        if (AtomicWrite.delete(file)) {
                            cleaned++;
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return cleaned;
    }

    // ===== 核心状态读写 =====

    /**
     * 写入状态数据（原子写入 + ONode 序列化）。
     *
     * <p>JSON 结构包含 _meta（written_at, mode, sessionId）和 data 两层。
     * data 层完整序列化 LoopStateData 的所有字段，包括 contextData 和 metadata。</p>
     *
     * @param mode      模式名：ralph / team / ultraqa
     * @param state     状态数据
     * @param sessionId 会话 ID
     * @return 是否成功
     */
    public boolean writeState(String mode, LoopStateData state, String sessionId) {
        try {
            Path filePath = getStateFilePath(mode, sessionId);

            // 先构建 _meta 子对象
            ONode meta = new ONode().asObject();
            meta.set("written_at", Instant.now().toString());
            meta.set("mode", mode);
            meta.set("sessionId", sessionId);

            // 先构建 data 子对象
            ONode data = new ONode().asObject();
            data.set("sessionId", state.getSessionId());
            data.set("state", state.getState() != null ? state.getState().name() : "UNKNOWN");
            data.set("iterationCount", state.getIterationCount());
            data.set("successfulIterations", state.getSuccessfulIterations());

            if (state.getStartTime() != null) {
                data.set("startTime", state.getStartTime().toString());
            }
            if (state.getLastUpdateTime() != null) {
                data.set("lastUpdateTime", state.getLastUpdateTime().toString());
            }
            if (state.getContextData() != null) {
                data.set("contextData", ONode.ofBean(state.getContextData()));
            }
            if (state.getMetadata() != null) {
                data.set("metadata", ONode.ofBean(state.getMetadata()));
            }

            // 组装 root
            ONode root = new ONode().asObject();
            root.set("_meta", meta);
            root.set("data", data);

            AtomicWrite.write(filePath, root.toJson());
            updateSessionSummary(sessionId, mode);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读取状态数据（ONode 反序列化，剥离 _meta 只返回 data 部分）。
     *
     * @param mode      模式名
     * @param sessionId 会话 ID
     * @return 状态数据，不匹配或不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public LoopStateData readState(String mode, String sessionId) {
        try {
            Path filePath = getStateFilePath(mode, sessionId);
            if (!AtomicWrite.exists(filePath)) {
                return null;
            }
            String json = AtomicWrite.read(filePath);

            // ONode 解析，剥离 _meta，提取 data
            ONode root = ONode.ofJson(json);
            ONode data = root.get("data");
            if (data == null || !data.isObject()) {
                // 旧版格式（无 _meta 包装）：根节点本身就是 data
                if (root.hasKey("sessionId")) {
                    data = root;
                } else {
                    return null;
                }
            }

            LoopStateData result = new LoopStateData();

            if (data.hasKey("sessionId")) {
                result.setSessionId(data.get("sessionId").getString());
            }
            if (data.hasKey("state")) {
                String stateStr = data.get("state").getString();
                if (stateStr != null) {
                    try {
                        result.setState(LoopState.valueOf(stateStr));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            if (data.hasKey("iterationCount")) {
                result.setIterationCount(data.get("iterationCount").getInt());
            }
            if (data.hasKey("successfulIterations")) {
                result.setSuccessfulIterations(data.get("successfulIterations").getInt());
            }
            if (data.hasKey("startTime")) {
                String startTimeStr = data.get("startTime").getString();
                if (startTimeStr != null) {
                    try {
                        result.setStartTime(Instant.parse(startTimeStr));
                    } catch (Exception ignored) {
                    }
                }
            }
            if (data.hasKey("lastUpdateTime")) {
                String lastUpdateStr = data.get("lastUpdateTime").getString();
                if (lastUpdateStr != null) {
                    try {
                        result.setLastUpdateTime(Instant.parse(lastUpdateStr));
                    } catch (Exception ignored) {
                    }
                }
            }
            if (data.hasKey("contextData")) {
                ONode ctxNode = data.get("contextData");
                if (ctxNode.isObject()) {
                    result.setContextData(ctxNode.toBean(Map.class));
                }
            }
            if (data.hasKey("metadata")) {
                ONode metaNode = data.get("metadata");
                if (metaNode.isObject()) {
                    result.setMetadata(metaNode.toBean(Map.class));
                }
            }

            return result;
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

    // ===== Ghost-Legacy 清理 =====

    /**
     * 清理幽灵/遗留状态文件。
     *
     * <p>对标 oh-my-claudecode 的 ghost-legacy 清理逻辑。处理以下场景：</p>
     * <ul>
     *   <li>孤儿文件：属于已不存在 session 的状态文件</li>
     *   <li>不属于当前 projectPath 的 session 文件</li>
     *   <li>不同的文件命名变体</li>
     * </ul>
     *
     * @param projectPath 当前项目路径，用于归属判定
     * @return 清理的文件数
     */
    public int cleanupGhostLegacy(String projectPath) {
        final int[] cleaned = {0};  // 使用数组绕过 lambda 限制

        // 遍历所有模式目录
        for (String mode : MODES) {
            Path modeDir = basePath.resolve(STATE_DIR).resolve(mode);
            if (!Files.exists(modeDir)) continue;

            List<Path> files = new ArrayList<>();
            try (Stream<Path> paths = Files.list(modeDir)) {
                paths.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            } catch (IOException ignored) {}

            for (Path file : files) {
                String sessionId = file.getFileName().toString().replace(".json", "");

                // 检查 session 归属
                String ownership = sessionOwnership.get(sessionId);
                if (ownership == null) {
                    // 无归属 → 孤儿文件，清理
                    if (AtomicWrite.delete(file)) cleaned[0]++;
                    continue;
                }

                // 检查项目路径匹配
                if (projectPath != null && !ownership.contains(projectPath)) {
                    if (AtomicWrite.delete(file)) cleaned[0]++;
                }
            }
        }

        // 清理 PRD 目录中的幽灵文件
        Path prdDir = basePath.resolve(PRD_DIR);
        if (Files.exists(prdDir)) {
            try (Stream<Path> paths = Files.list(prdDir)) {
                paths.filter(p -> p.toString().endsWith(".json")).forEach(file -> {
                    String sessionId = file.getFileName().toString().replace(".json", "");
                    if (!sessionOwnership.containsKey(sessionId)) {
                        if (AtomicWrite.delete(file)) cleaned[0]++;
                    }
                });
            } catch (IOException ignored) {}
        }

        // 清理 Progress 目录中的幽灵文件
        Path progressDir = basePath.resolve(PROGRESS_DIR);
        if (Files.exists(progressDir)) {
            try (Stream<Path> paths = Files.list(progressDir)) {
                paths.filter(p -> p.toString().endsWith(".txt")).forEach(file -> {
                    String sessionId = file.getFileName().toString().replace(".txt", "");
                    if (!sessionOwnership.containsKey(sessionId)) {
                        if (AtomicWrite.delete(file)) cleaned[0]++;
                    }
                });
            } catch (IOException ignored) {}
        }

        // 清理 session 摘要目录中的幽灵文件
        Path sessionsDir = basePath.resolve(STATE_DIR).resolve(SESSIONS_DIR);
        if (Files.exists(sessionsDir)) {
            try (Stream<Path> paths = Files.list(sessionsDir)) {
                paths.filter(p -> p.toString().endsWith(".json")).forEach(file -> {
                    String sessionId = file.getFileName().toString().replace(".json", "");
                    if (!sessionOwnership.containsKey(sessionId)) {
                        if (AtomicWrite.delete(file)) cleaned[0]++;
                    }
                });
            } catch (IOException ignored) {}
        }

        return cleaned[0];
    }

    // ===== 辅助方法 =====

    /**
     * 从 LoopStateData 的 contextData 中推断模式。
     *
     * <p>读取 contextData 中的 "mode" 字段；如果不存在或为 null，则返回 "ralph" 作为默认值。</p>
     *
     * @param state 状态数据
     * @return 模式名
     */
    public String getModeFromState(LoopStateData state) {
        if (state != null && state.getContextData() != null) {
            Object mode = state.getContextData().get("mode");
            if (mode != null) {
                return mode.toString();
            }
        }
        return "ralph";
    }

    // ===== 会话摘要管理 =====

    private void updateSessionSummary(String sessionId, String mode) {
        try {
            Path summaryPath = getSessionSummaryPath(sessionId);
            ONode summary = new ONode().asObject();
            summary.set("sessionId", sessionId);
            summary.set("mode", mode);
            summary.set("lastUpdated", Instant.now().toString());
            AtomicWrite.write(summaryPath, summary.toJson());
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

    /**
     * 获取 PRD 文件路径。
     */
    public Path getPrdFilePath(String sessionId) {
        return basePath.resolve(PRD_DIR).resolve(sessionId + ".json");
    }

    private Path getProgressFilePath(String sessionId) {
        return basePath.resolve(PROGRESS_DIR).resolve(sessionId + ".txt");
    }

    private void ensureDirectories() {
        try {
            // 创建目录后设置 0o700 权限（对标 OMC）
            Path baseDir = Files.createDirectories(basePath);
            FilePermissionUtil.set0700(baseDir);

            Path stateDir = Files.createDirectories(basePath.resolve(STATE_DIR));
            FilePermissionUtil.set0700(stateDir);

            Path[] dirs = {
                    Files.createDirectories(basePath.resolve(STATE_DIR).resolve("ralph")),
                    Files.createDirectories(basePath.resolve(STATE_DIR).resolve("team")),
                    Files.createDirectories(basePath.resolve(STATE_DIR).resolve("ultraqa")),
                    Files.createDirectories(basePath.resolve(STATE_DIR).resolve(SESSIONS_DIR)),
                    Files.createDirectories(basePath.resolve(PRD_DIR)),
                    Files.createDirectories(basePath.resolve(PROGRESS_DIR))
            };
            for (Path dir : dirs) {
                FilePermissionUtil.set0700(dir);
            }
        } catch (IOException ignored) {
        }
    }

}
