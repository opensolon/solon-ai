/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 积压索引：状态来源目录下每个「可能有积压」的会话一个空标记文件（文件名为编码后的 sessionId）。
 *
 * <p>索引是超集：入队时登记，观察到队列已空时注销；进程重启后宿主据此续发，不需要枚举会话目录。
 * 内存里记一份已登记集合，避免每次续发结束都去删一个不存在的文件。多个节点共用状态来源时各写各的文件。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class QueueIndex {
    private static final Logger LOG = LoggerFactory.getLogger(QueueIndex.class);

    private final MountSource source;
    private final String dir;
    private final Set<String> marked = ConcurrentHashMap.newKeySet();
    private volatile boolean loaded;

    QueueIndex(MountSource source, String dir) {
        this.source = source;
        this.dir = source.normalize(dir);
    }

    /** 读取索引中的会话（读失败返回空），并同步到内存集合 */
    List<String> read() {
        try {
            if (source.isDirectory(dir) == false) {
                loaded = true;
                return Collections.emptyList();
            }
            List<String> ids = new ArrayList<>();
            for (MountEntry e : source.list(dir)) {
                if (e.isDirectory() == false) {
                    ids.add(URLDecoder.decode(e.getName(), "UTF-8"));
                }
            }
            Collections.sort(ids);
            marked.addAll(ids);
            loaded = true;
            return ids;
        } catch (Throwable e) {
            LOG.warn("Queue index read failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    void mark(String sessionId) {
        if (sessionId == null || marked.add(sessionId) == false) {
            return;
        }
        try {
            source.writeString(marker(sessionId), "");
        } catch (Throwable e) {
            marked.remove(sessionId);
            LOG.warn("Queue index update failed for '{}': {}", sessionId, e.getMessage());
        }
    }

    void unmark(String sessionId) {
        if (sessionId == null) {
            return;
        }
        if (loaded == false) {
            read();
        }
        if (marked.remove(sessionId) == false) {
            return;
        }
        try {
            source.deleteIfExists(marker(sessionId));
        } catch (Throwable e) {
            LOG.warn("Queue index update failed for '{}': {}", sessionId, e.getMessage());
        }
    }

    private String marker(String sessionId) throws UnsupportedEncodingException {
        // 文件名安全编码（含 * 与 . 开头，防路径穿越与通配符）
        String s = URLEncoder.encode(sessionId, "UTF-8").replace("*", "%2A");
        return dir + "/" + (s.startsWith(".") ? "%2E" + s.substring(1) : s);
    }
}
