/*
 * Copyright 2017-2025 noear.org and authors
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 文件型智能体会话适配器 (带内存缓存层)
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class FileAgentSession implements AgentSession {
    private static final Logger LOG = LoggerFactory.getLogger(FileAgentSession.class);

    private final File messagesFile;
    private final File snapshotFile;
    private final InMemoryAgentSession cache;

    public FileAgentSession(String sessionId, String dir) {
        File baseDir = new File(dir);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        this.messagesFile = new File(baseDir, sessionId + ".messages.ndjson");
        this.snapshotFile = new File(baseDir, sessionId + ".snapshot.json");

        // --- 1. 初始化快照 ---
        FlowContext loadedSnapshot = null;
        if (snapshotFile.exists()) {
            try {
                byte[] bytes = Files.readAllBytes(snapshotFile.toPath());
                loadedSnapshot = FlowContext.fromJson(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception e) {
                LOG.warn("Load snapshot failed, session: {}", sessionId, e);
            }
        }

        // --- 2. 初始化缓存层 ---
        if (loadedSnapshot != null) {
            this.cache = new InMemoryAgentSession(loadedSnapshot);
        } else {
            this.cache = new InMemoryAgentSession(sessionId);
        }

        // --- 3. 加载历史消息到缓存 ---
        loadMessagesToCache();

        // 注入当前 FileAgentSession 到上下文，确保 updateSnapshot 触发的是当前实例
        this.cache.getSnapshot().put(Agent.KEY_SESSION, this);
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
        return cache.getSessionId();
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
            LOG.error("Persistence messages failed", e);
        }
    }

    @Override
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    @Override
    public void updateSnapshot() {
        try {
            // 将缓存中的快照全量持久化
            String json = cache.getSnapshot().toJson();
            Files.write(snapshotFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.error("Persistence snapshot failed", e);
        }
    }

    @Override
    public FlowContext getSnapshot() {
        // 外部读写的快照对象直接来自缓存
        return cache.getSnapshot();
    }

    public void clear() {
        cache.clear();
        if (messagesFile.exists()) messagesFile.delete();
        if (snapshotFile.exists()) snapshotFile.delete();
    }
}