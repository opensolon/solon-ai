/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Session 队列的唯一持久化存储。Web、IM、CLI 只操作同一个 queue-tasks.json。 */
public final class SessionQueueStore {
    public static final String FILE_NAME = "queue-tasks.json";
    // 读取旧版 Web 队列一次；新任务只写 queue-tasks.json。
    private static final String OLD_QUEUE = "queue.json";
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private SessionQueueStore() { }

    private static Object lock(Path dir) {
        return LOCKS.computeIfAbsent(dir.toAbsolutePath().normalize(), k -> new Object());
    }

    public static List<SessionQueueItem> load(Path sessionDir) throws IOException {
        if (sessionDir == null) return new ArrayList<>();
        synchronized (lock(sessionDir)) {
            migrateUnlocked(sessionDir);
            return loadUnlocked(sessionDir);
        }
    }

    private static void migrateUnlocked(Path dir) throws IOException {
        Path oldQueue = dir.resolve(OLD_QUEUE);
        if (!Files.exists(oldQueue)) return;
        List<SessionQueueItem> items = loadUnlocked(dir);
        items.addAll(readItems(oldQueue));
        saveUnlocked(dir, items);
        Files.deleteIfExists(oldQueue);
    }

    private static List<SessionQueueItem> loadUnlocked(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return new ArrayList<>();
        return readItems(file);
    }

    private static List<SessionQueueItem> readItems(Path file) throws IOException {
        ONode root = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        ONode nodes = root == null ? null : root.get("items");
        if (nodes == null || !nodes.isArray()) throw new IOException("queue items is not an array");

        Map<String, SessionQueueItem> unique = new LinkedHashMap<>();
        for (ONode node : nodes.getArray()) {
            if (node == null) continue;
            Map<String, Object> map;
            if (node.isObject()) {
                map = toMap(node);
            } else if (node.isValue()) {
                map = new LinkedHashMap<>();
                map.put("text", node.getString());
            } else {
                continue;
            }
            SessionQueueItem item = SessionQueueItem.fromMap(map);
            if (item != null) unique.put(item.getId(), item);
        }

        ONode inFlight = root.get("inFlight");
        if (inFlight != null && inFlight.isObject()) {
            SessionQueueItem item = SessionQueueItem.fromMap(toMap(inFlight));
            if (item != null) {
                item.setStatus("RUNNING");
                unique.put(item.getId(), item);
            }
        }

        List<SessionQueueItem> result = new ArrayList<>(unique.values());
        // 上一次进程未完成的任务必须先恢复。
        for (int i = result.size() - 1; i >= 0; i--) {
            if ("RUNNING".equals(result.get(i).getStatus())) {
                SessionQueueItem item = result.remove(i);
                result.add(0, item);
            }
        }
        return result;
    }

    public static void save(Path sessionDir, List<SessionQueueItem> items) throws IOException {
        if (sessionDir == null) throw new IOException("session directory is null");
        synchronized (lock(sessionDir)) {
            // 旧 Web 队列若在绑定后才出现，保存时仍要保留其任务。
            Path oldQueue = sessionDir.resolve(OLD_QUEUE);
            if (Files.exists(oldQueue)) {
                List<SessionQueueItem> merged = new ArrayList<>(items == null ? new ArrayList<SessionQueueItem>() : items);
                merged.addAll(readItems(oldQueue));
                saveUnlocked(sessionDir, merged);
                Files.deleteIfExists(oldQueue);
            } else {
                saveUnlocked(sessionDir, items);
            }
        }
    }

    private static void saveUnlocked(Path sessionDir, List<SessionQueueItem> items) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> inFlight = null;
        if (items != null) {
            for (SessionQueueItem item : items) {
                if (item == null) continue;
                if ("RUNNING".equals(item.getStatus())) inFlight = item.toMap();
                else rows.add(item.toMap());
            }
        }

        if (rows.isEmpty() && inFlight == null) {
            Files.deleteIfExists(sessionDir.resolve(FILE_NAME));
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("updatedAt", System.currentTimeMillis());
        payload.put("items", rows);
        if (inFlight != null) payload.put("inFlight", inFlight);

        Files.createDirectories(sessionDir);
        Path file = sessionDir.resolve(FILE_NAME);
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        String json = ONode.ofBean(payload, Options.of(Feature.Write_PrettyFormat)).toJson();
        Files.write(temp, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(ONode node) {
        return (Map<String, Object>) node.toBean(Map.class);
    }
}
