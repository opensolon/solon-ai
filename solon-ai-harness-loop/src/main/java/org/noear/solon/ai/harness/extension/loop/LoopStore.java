/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.loop;

import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.MoveOptions;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务存储：按会话分存，每个会话一个文件 {@code <dir>/<encode(sessionId)>.json}
 * （{@code {version:2, tasks:[{...task, sessionId}]}}），变更只重写内容有变化的会话。
 *
 * <p><b>不吞别人的数据</b>：会话文件里本插件不认识的条目（如 {@code type=GOAL}、缺 sessionId 或解析失败的坏条目）
 * 在加载时原样留存为该会话的「外来条目」，之后每次落盘都按原始内容写回，不参与调度。</p>
 *
 * <p><b>不用空快照覆盖读不懂的文件</b>：单个会话文件无法解析（或结构不对）时改名为
 * {@code <file>.corrupt-<毫秒时间戳>} 备份，不影响其它会话；备份失败或读不出来（IO 错误）时进入只读模式，
 * 本进程不再写盘——宁可本次修改不落盘，也不能销毁可救回的数据。</p>
 *
 * <p><b>旧单文件迁移</b>：设置了 legacyPath 且其存在时，首次加载把其中任务拆成分存文件，
 * 旧文件改名为 {@code <legacy>.migrated-<时间戳>}（外来条目随备份完整保留）。</p>
 *
 * <p><b>存储介质无关</b>：经 {@link MountSource} 读写（本地文件、db、http 均可）。
 * {@code source == null} 时为纯内存模式（测试友好），读写均为空操作。调用方负责串行化写入。</p>
 */
final class LoopStore {
    private static final Logger LOG = LoggerFactory.getLogger(LoopStore.class);

    private final MountSource source;
    /** 存储目录 */
    private final String dir;
    /** 待迁移的旧单文件（可空） */
    private final String legacyPath;
    /** 每个会话文件上次写入的内容（只重写有变化的会话） */
    private final Map<String, String> lastWritten = new ConcurrentHashMap<>();
    /** 会话文件里的外来条目（原样写回到同一文件） */
    private final Map<String, List<ONode>> foreignBySession = new ConcurrentHashMap<>();
    /** 只读模式：文件读不出来或损坏文件备份失败时置位，之后 save 一律跳过。 */
    private volatile boolean readOnly;

    /**
     * @param source     存储来源（null = 纯内存）
     * @param dir        来源内目录
     * @param legacyPath 首次加载时要迁移的旧单文件（可空）
     */
    LoopStore(MountSource source, String dir, String legacyPath) {
        if (source != null && (dir == null || dir.trim().isEmpty())) {
            throw new IllegalArgumentException("Loop store dir must not be empty");
        }
        this.source = source;
        this.dir = source == null ? null : source.normalize(dir);
        this.legacyPath = source == null || legacyPath == null || legacyPath.trim().isEmpty()
                ? null : source.normalize(legacyPath);
    }

    /** 会话文件路径（防路径穿越：会话 ID 整体编码为单段文件名） */
    String sessionFile(String sessionId) {
        try {
            String name = URLEncoder.encode(sessionId, "UTF-8").replace("*", "%2A");
            return dir + "/" + (name.startsWith(".") ? "%2E" + name.substring(1) : name) + ".json";
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 是否持久化（设置了存储来源）。 */
    boolean isPersistent() {
        return source != null;
    }

    /** 是否已进入只读模式（测试与诊断用）。 */
    boolean isReadOnly() {
        return readOnly;
    }

    /** 是否为本插件的任务条目：type 缺省或为 LOOP/HEARTBEAT（历史取值）。 */
    static boolean isLoopEntry(ONode item) {
        ONode type = item.getOrNull("type");
        if (type == null || type.isNull()) {
            return true;
        }
        String t = type.getString();
        return t == null || t.isEmpty() || "LOOP".equalsIgnoreCase(t) || "HEARTBEAT".equalsIgnoreCase(t);
    }

    /**
     * 读取全部任务（按 sessionId 归组）；外来条目留存在本实例里。
     */
    Map<String, List<LoopTask>> load() {
        Map<String, List<LoopTask>> result = new LinkedHashMap<>();
        lastWritten.clear();
        foreignBySession.clear();
        readOnly = false;
        if (source == null) {
            return result;
        }

        try {
            migrateLegacyIfNeeded();

            if (source.isDirectory(dir) == false) {
                return result;
            }

            for (MountEntry entry : source.list(dir)) {
                if (entry.isDirectory() || entry.getName() == null || entry.getName().endsWith(".json") == false) {
                    continue;
                }
                String file = dir + "/" + entry.getName();
                String json = source.readStringOrNull(file);
                if (json == null || json.trim().isEmpty()) {
                    continue; // 空文件没有可救的内容
                }

                ONode root;
                try {
                    root = ONode.ofJson(json);
                } catch (Exception e) {
                    root = null;
                }
                if (isTasksRoot(root) == false) {
                    // 单个会话文件损坏：改名备份，不影响其它会话
                    String backup = file + ".corrupt-" + System.currentTimeMillis();
                    try {
                        source.move(file, backup, MoveOptions.defaults());
                        LOG.error("Loop session file is unreadable, backed up to {}", backup);
                    } catch (IOException | RuntimeException e) {
                        readOnly = true;
                        LOG.error("Loop session file unreadable and backup failed, store is read-only: {}", e.getMessage());
                    }
                    continue;
                }

                Map<String, List<LoopTask>> part = new LinkedHashMap<>();
                List<ONode> foreignPart = new ArrayList<>();
                parseEntries(root, part, foreignPart);
                String fileSession = decodeFileName(entry.getName());
                if (foreignPart.isEmpty() == false && fileSession != null) {
                    foreignBySession.put(fileSession, foreignPart);
                }
                for (Map.Entry<String, List<LoopTask>> kv : part.entrySet()) {
                    result.computeIfAbsent(kv.getKey(), k -> new ArrayList<>()).addAll(kv.getValue());
                }
                if (fileSession != null) {
                    lastWritten.put(fileSession, json);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 读不出来不等于没有数据：不写盘，避免下一次修改覆盖原文件
            readOnly = true;
            LOG.error("Read loop store failed, loop store is read-only for this process: {}", e.getMessage());
        }
        return result;
    }

    /** 写入快照：只重写内容有变化的会话；快照里没有的会话删除文件（有外来条目时只保留外来条目）。 */
    void save(Map<String, List<LoopTask>> snapshot) {
        if (source == null || readOnly) {
            return;
        }

        Set<String> sessions = new HashSet<>(snapshot.keySet());
        sessions.addAll(lastWritten.keySet());

        for (String sessionId : sessions) {
            try {
                List<LoopTask> tasks = snapshot.getOrDefault(sessionId, Collections.emptyList());
                List<ONode> foreignItems = foreignBySession.get(sessionId);
                boolean empty = tasks.stream().allMatch(LoopTask::isCancelled)
                        && (foreignItems == null || foreignItems.isEmpty());
                String file = sessionFile(sessionId);

                if (empty) {
                    if (lastWritten.remove(sessionId) != null) {
                        source.deleteIfExists(file);
                    }
                    continue;
                }

                String json = toJson(sessionId, tasks, foreignItems);
                if (json.equals(lastWritten.get(sessionId))) {
                    continue;
                }
                source.writeString(file, json);
                lastWritten.put(sessionId, json);
            } catch (Exception e) {
                LOG.error("Failed to save loop session '{}': {}", sessionId, e.getMessage());
            }
        }
    }

    private static void parseEntries(ONode root, Map<String, List<LoopTask>> result, List<ONode> foreignOut) {
        for (ONode item : root.get("tasks").getArray()) {
            if (!isLoopEntry(item)) {
                foreignOut.add(item);
                continue;
            }
            String sessionId = item.getOrNull("sessionId") != null ? item.get("sessionId").getString() : null;
            if (sessionId == null || sessionId.trim().isEmpty()) {
                foreignOut.add(item);
                continue;
            }
            try {
                result.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(LoopTask.fromONode(item));
            } catch (Exception e) {
                LOG.warn("Keep unparsable task entry as-is: {}", e.getMessage());
                foreignOut.add(item);
            }
        }
    }

    private static boolean isTasksRoot(ONode root) {
        return root != null && root.isObject() && root.getOrNull("tasks") != null && root.get("tasks").isArray();
    }

    private static String decodeFileName(String name) {
        try {
            return URLDecoder.decode(name.substring(0, name.length() - ".json".length()), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** 旧单文件 → 按会话分存（一次性；旧文件改名备份，外来条目随备份完整保留） */
    private void migrateLegacyIfNeeded() throws IOException {
        if (legacyPath == null || source.exists(legacyPath) == false) {
            return;
        }

        String json = source.readStringOrNull(legacyPath);
        Map<String, List<LoopTask>> legacy = new LinkedHashMap<>();
        List<ONode> legacyForeign = new ArrayList<>();
        boolean blank = json == null || json.trim().isEmpty();
        if (blank == false) {
            ONode root;
            try {
                root = ONode.ofJson(json);
            } catch (Exception e) {
                root = null;
            }
            if (isTasksRoot(root)) {
                parseEntries(root, legacy, legacyForeign);
                blank = legacy.isEmpty() && legacyForeign.isEmpty();
            }
        }

        for (Map.Entry<String, List<LoopTask>> kv : legacy.entrySet()) {
            String file = sessionFile(kv.getKey());
            if (source.exists(file)) {
                continue; // 已有分存文件以分存为准（迁移中断后重跑不覆盖）
            }
            source.writeString(file, toJson(kv.getKey(), kv.getValue(), null));
        }

        if (blank) {
            // 空文件或空任务表没有可救的内容：直接删除，不留备份（读不懂的文件仍改名备份）
            source.deleteIfExists(legacyPath);
            return;
        }

        String backup = legacyPath + ".migrated-" + System.currentTimeMillis();
        source.move(legacyPath, backup, MoveOptions.defaults());
        LOG.info("Loop store migrated {} session(s) from {} (backup: {}, foreign entries kept in backup: {})",
                legacy.size(), legacyPath, backup, legacyForeign.size());
    }

    private static String toJson(String sessionId, List<LoopTask> tasks, List<ONode> foreignItems) {
        ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
        root.set("version", 2);
        // ★ snack4 陷阱：必须 getOrNew("tasks").asArray()，对 null 链式 asArray 会得到幽灵数组
        ONode array = root.getOrNew("tasks").asArray();
        for (LoopTask t : tasks) {
            if (t.isCancelled()) continue;
            ONode node = t.toONode();
            node.set("sessionId", sessionId);
            array.add(node);
        }
        if (foreignItems != null) {
            for (ONode item : foreignItems) {
                array.add(item);
            }
        }
        return root.toJson();
    }
}
