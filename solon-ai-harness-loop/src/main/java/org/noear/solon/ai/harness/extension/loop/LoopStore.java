/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.loop;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tasks.json 存储（{@code {version:2, tasks:[{...task, sessionId}]}}，temp + ATOMIC_MOVE 原子替换）。
 *
 * <p><b>不吞别人的数据</b>：同一文件里可能有本插件不认识的条目（如尚未迁走的 {@code type=GOAL}、
 * 缺 sessionId 或解析失败的坏条目）。它们在加载时被原样留存为「外来条目」，之后任何一次落盘都按原始
 * 内容写回，不参与调度。旧实现以内存快照整体覆盖文件，这些条目会在第一次修改后静默消失。</p>
 *
 * <p><b>不用空快照覆盖读不懂的文件</b>：整个文件无法解析（或结构不对）时，先把原文件改名为
 * {@code tasks.json.corrupt-<毫秒时间戳>} 留作备份，再按空列表继续；备份失败或文件读不出来（IO 错误）时
 * 进入只读模式，本进程不再写盘——宁可本次修改不落盘，也不能销毁可救回的数据。</p>
 *
 * <p>{@code file == null} 时为纯内存模式（测试友好），读写均为空操作。调用方负责串行化写入。</p>
 */
final class LoopStore {
    private static final Logger LOG = LoggerFactory.getLogger(LoopStore.class);

    private final Path file;
    /** 加载时留存的外来条目（原样写回）。 */
    private final List<ONode> foreign = Collections.synchronizedList(new ArrayList<>());
    /** 只读模式：文件读不出来或损坏文件备份失败时置位，之后 save 一律跳过。 */
    private volatile boolean readOnly;

    LoopStore(Path file) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
    }

    /** 是否落盘（file 非 null）。 */
    boolean isPersistent() {
        return file != null;
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
        foreign.clear();
        readOnly = false;
        if (file == null || !Files.isRegularFile(file)) {
            return result;
        }

        String json;
        try {
            json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 读不出来不等于没有数据：不写盘，避免下一次修改覆盖原文件
            readOnly = true;
            LOG.error("Read tasks.json failed, loop store is read-only for this process: {}", e.getMessage());
            return result;
        }
        if (json.trim().isEmpty()) {
            return result; // 空文件没有可救的内容
        }

        ONode root;
        try {
            root = ONode.ofJson(json);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.isObject() || root.getOrNull("tasks") == null || !root.get("tasks").isArray()) {
            backupCorrupt();
            return result;
        }

        for (ONode item : root.get("tasks").getArray()) {
            if (!isLoopEntry(item)) {
                foreign.add(item);
                continue;
            }
            String sessionId = item.getOrNull("sessionId") != null ? item.get("sessionId").getString() : null;
            if (sessionId == null || sessionId.trim().isEmpty()) {
                foreign.add(item);
                continue;
            }
            try {
                result.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(LoopTask.fromONode(item));
            } catch (Exception e) {
                LOG.warn("Keep unparsable task entry as-is: {}", e.getMessage());
                foreign.add(item);
            }
        }
        return result;
    }

    /** 外来条目数（测试与诊断用）。 */
    int foreignCount() {
        return foreign.size();
    }

    /** 损坏文件改名备份；备份不了就进入只读（绝不让后续写盘覆盖它）。 */
    private void backupCorrupt() {
        Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, backup);
            LOG.error("tasks.json is unreadable, backed up to {} and starting empty", backup.getFileName());
        } catch (IOException e) {
            readOnly = true;
            LOG.error("tasks.json is unreadable and backup failed, loop store is read-only for this process: {}",
                    e.getMessage());
        }
    }

    /** 写入快照（取消的任务不落盘）+ 外来条目原样追加。 */
    void save(Map<String, List<LoopTask>> snapshot) {
        if (file == null || readOnly) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());

            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
            root.set("version", 2);
            // ★ snack4 陷阱：必须 getOrNew("tasks").asArray()，对 null 链式 asArray 会得到幽灵数组
            ONode array = root.getOrNew("tasks").asArray();
            for (Map.Entry<String, List<LoopTask>> entry : snapshot.entrySet()) {
                for (LoopTask t : entry.getValue()) {
                    if (t.isCancelled()) continue;
                    ONode node = t.toONode();
                    node.set("sessionId", entry.getKey());
                    array.add(node);
                }
            }
            synchronized (foreign) {
                for (ONode item : foreign) {
                    array.add(item);
                }
            }

            Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tempFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                    StandardCharsets.UTF_8)) {
                w.write(root.toJson());
            }
            try {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException fallback) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.error("Failed to save tasks.json: {}", e.getMessage());
        }
    }
}
