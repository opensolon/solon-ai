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
package org.noear.solon.ai.chat.session;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 文件聊天会话 (带内存缓存层)
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class FileChatSession implements ChatSession {
    private static final Logger LOG = LoggerFactory.getLogger(FileChatSession.class);

    private final File messagesFile;
    private final InMemoryChatSession cache;

    public FileChatSession(String sessionId, String dir) {
        File baseDir = new File(dir);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        this.messagesFile = new File(baseDir, sessionId + ".messages.ndjson");
        this.cache = new InMemoryChatSession(sessionId);

        loadMessagesToCache();
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


    public void clear() {
        cache.clear();
        if (messagesFile.exists()) messagesFile.delete();
    }
}