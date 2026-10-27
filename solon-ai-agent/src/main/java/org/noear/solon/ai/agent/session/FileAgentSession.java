/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.session;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文件型智能体会话适配器 (带内存缓存层)
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class FileAgentSession implements AgentSession {
    private static final Logger LOG = LoggerFactory.getLogger(FileAgentSession.class);

    private static final String TMP_SUFFIX = ".tmp.";

    /** 陈旧临时文件的判定阈值：短于此时长的临时文件视为其它实例正在写。 */
    private static final long STALE_TMP_MILLIS = 60_000L;

    private final String sessionId;
    private final File messagesFile;
    private final File snapshotFile;
    private final InMemoryAgentSession cache;
    private final ReentrantLock locker = new ReentrantLock();

    public FileAgentSession(String sessionId, String dir) {
        Objects.requireNonNull(sessionId, "sessionId is required");
        Objects.requireNonNull(dir, "dir is required");

        File baseDir = new File(dir);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        this.sessionId = sessionId;
        this.messagesFile = new File(baseDir, sessionId + ".messages.ndjson");
        this.snapshotFile = new File(baseDir, sessionId + ".snapshot.json");

        // 清理上次崩溃遗留的临时文件（原子写残留）
        cleanupStaleTmp(baseDir);

        // --- 1. 初始化快照 ---
        FlowContext snapshot = null;
        if (snapshotFile.exists()) {
            try {
                byte[] bytes = Files.readAllBytes(snapshotFile.toPath());
                snapshot = FlowContext.fromJson(new String(bytes, StandardCharsets.UTF_8));
            } catch (Throwable e) {
                LOG.warn("Load snapshot failed, session: {}", sessionId, e);
            }
        }

        if (snapshot == null) {
            snapshot = FlowContext.of(sessionId);
        }

        // --- 2. 初始化缓存层 ---
        this.cache = new InMemoryAgentSession(snapshot);

        // --- 3. 加载历史消息到缓存 ---
        loadMessagesToCache();

        // 注入当前 FileAgentSession 到上下文，确保 updateSnapshot 触发的是当前实例
        this.cache.getContext().put(Agent.KEY_SESSION, this);
    }

    private void loadMessagesToCache() {
        if (!messagesFile.exists()) return;

        List<ChatMessage> history = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(messagesFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Utils.isNotEmpty(line)) {
                    history.add(ChatMessage.fromJson(line));
                }
            }
            // 批量加入缓存（内存层会自动过滤 System 消息）
            this.cache.addMessage(history);
        } catch (IOException e) {
            LOG.error("Load messages to cache failed", e);
        }
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public List<ChatMessage> getMessages() {
        // 直接走缓存
        return cache.getMessages();
    }

    @Override
    public List<ChatMessage> getLatestMessages(int windowSize) {
        // 直接走缓存
        return cache.getLatestMessages(windowSize);
    }

    @Override
    public void removeLatestMessage(int windowSize) {
        // 1. 先从内存层安全删除
        cache.removeLatestMessage(windowSize);

        // 2. 同步到磁盘：重写整个消息文件
        persistMessages();
    }

    /**
     * 将当前内存缓存中的所有非 System 消息持久化到磁盘（原子全量覆盖）
     */
    private void persistMessages() {
        try {
            StringBuilder buf = new StringBuilder();
            for (ChatMessage msg : cache.getMessages()) {
                if (msg.getRole() != ChatRole.SYSTEM) {
                    buf.append(ChatMessage.toJson(msg)).append('\n');
                }
            }
            writeAtomic(messagesFile.toPath(), buf.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.error("Persist messages failed: {}", e.toString());
        }
    }

    @Override
    public void addMessage(Collection<? extends ChatMessage> messages) {
        if (Utils.isEmpty(messages)) return;

        // 1. 同步到内存层
        cache.addMessage(messages);

        // 2. 持久化到磁盘 (NDJSON 追加模式)
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(messagesFile, true), StandardCharsets.UTF_8))) {
            for (ChatMessage msg : messages) {
                if (msg.getRole() != ChatRole.SYSTEM) {
                    writer.write(ChatMessage.toJson(msg));
                    writer.newLine();
                }
            }
            writer.flush();
        } catch (IOException e) {
            LOG.error("Persistence messages failed: {}", e.getMessage());
        }
    }

    @Override
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    @Override
    public Map<String, Object> attrs() {
        return cache.attrs();
    }

    @Override
    public void updateSnapshot() {
        locker.lock();
        try {
            // 将缓存中的快照全量持久化（原子写：临时文件 + fsync + ATOMIC_MOVE）
            String json = cache.getContext().toJson();
            writeAtomic(snapshotFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            LOG.error("Persistence snapshot failed: {}", e.toString());
        } finally {
            locker.unlock();
        }
    }

    /**
     * 原子写入：同目录临时文件 → fsync → 原子重命名覆盖目标。
     *
     * <p>用于快照与消息文件的全量覆盖写。若直接覆盖写在中断时留下半截文件，
     * 消息文件会丢失记录，快照则会连同其他会话级数据（队列、目标等）一并损坏。
     * 原子重命名可保证目标文件要么是旧内容、要么是完整新内容。</p>
     */
    private static void writeAtomic(Path target, byte[] data) throws IOException {
        Path dir = target.getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX + UUID.randomUUID());
        try {
            Files.write(tmp, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            fsync(tmp);

            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 极少数文件系统不支持原子移动，回退为普通覆盖
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 移动成功时临时文件已不存在；失败时尽力清理
            }
        }
    }

    /** 落盘前的 fsync，避免断电时只有元数据就位；不支持的文件系统忽略。 */
    private static void fsync(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            LOG.debug("fsync not supported for {}: {}", path, e.toString());
        }
    }

    /**
     * 清理本会话上次崩溃遗留的临时文件（快照与消息文件）。
     *
     * <p>只删除超过 {@link #STALE_TMP_MILLIS} 的临时文件：构造函数执行时本实例尚未持锁，
     * 同一会话可能有其它进程/实例正在写自己的临时文件，误删会导致对方的原子移动失败。</p>
     */
    private void cleanupStaleTmp(File baseDir) {
        String[] prefixes = {
                sessionId + ".snapshot.json" + TMP_SUFFIX,
                sessionId + ".messages.ndjson" + TMP_SUFFIX
        };

        File[] files = baseDir.listFiles();
        if (files == null) return;

        long deadline = System.currentTimeMillis() - STALE_TMP_MILLIS;
        for (File f : files) {
            if (!isTmpOf(f.getName(), prefixes) || f.lastModified() > deadline) {
                continue; // 非本会话 tmp，或可能是其它实例正在使用的临时文件
            }
            if (!f.delete()) {
                LOG.debug("Delete stale tmp failed: {}", f.getName());
            }
        }
    }

    private static boolean isTmpOf(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public FlowContext getContext() {
        // 外部读写的快照对象直接来自缓存
        return cache.getContext();
    }

    public void clear() {
        cache.clear();
        if (messagesFile.exists()) messagesFile.delete();
        if (snapshotFile.exists()) snapshotFile.delete();
    }
}