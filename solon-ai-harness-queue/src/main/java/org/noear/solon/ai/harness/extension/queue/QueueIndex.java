/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 积压索引：状态来源目录下每个「可能有积压」的会话一个空标记文件（文件名为编码后的 sessionId）。
 *
 * <p>索引是超集：有积压时一定有标记，有标记不一定有积压。进程重启后宿主据此续发，不需要枚举会话目录。</p>
 *
 * <p>本类只做单条写入、单条删除与整目录列举，<b>不在内存里保存目录内容</b>：多个节点共用状态来源时目录是共享事实，
 * 进程内的副本必然过期（会话换节点后，同一个标记会被不同节点先后写入和删除）。「这次能不能不碰磁盘」的判断
 * 挂在会话上（{@link QueueAttrs#QUEUE_UNMARKED}），与队列同一把锁、同一生命周期，见 {@code QueueExtension#syncIndex}。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class QueueIndex {
    private static final Logger LOG = LoggerFactory.getLogger(QueueIndex.class);

    private final MountSource source;
    private final String dir;

    QueueIndex(MountSource source, String dir) {
        this.source = source;
        this.dir = source.normalize(dir);
    }

    /**
     * 列举索引中的会话（按 sessionId 排序；目录不存在时为空）。
     *
     * <p>读失败抛出而不是返回空：调用方要区分「没有积压」和「这次没读到」，后者应当重试。</p>
     *
     * @throws IOException 列举失败
     */
    List<String> list() throws IOException {
        try {
            if (source.isDirectory(dir) == false) {
                return Collections.emptyList();
            }
            List<String> ids = new ArrayList<>();
            for (MountEntry e : source.list(dir)) {
                String name = e.getName();
                // 「.」开头的是原子写留下的临时文件：真实标记的前导点已编码为 %2E，不会以「.」开头
                if (e.isDirectory() || name == null || name.startsWith(".")) {
                    continue;
                }
                try {
                    ids.add(URLDecoder.decode(name, "UTF-8"));
                } catch (IllegalArgumentException ignore) {
                    // 不是本索引写出的文件名：跳过，不让一个坏条目挡住整次恢复
                }
            }
            Collections.sort(ids);
            return ids;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            // 状态来源（远程实现尤其）可能抛非受检异常：归一为本方法的契约，否则调用方（宿主重试）认不出「这次没读到」
            throw new IOException("Queue index read failed: " + e, e);
        }
    }

    /**
     * 登记。每次都写：空文件覆盖写是幂等的，不能拿「以前写过」跳过——那个标记可能早被别的节点删了。
     *
     * @return 是否写成功
     */
    boolean mark(String sessionId) {
        try {
            source.writeString(marker(sessionId), "");
            return true;
        } catch (Throwable e) {
            LOG.warn("Queue index mark failed for '{}': {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 注销（只删这一条，不列目录；本来就没有也算成功）。
     *
     * @return 是否已确认标记不存在（失败时为 false，调用方下次再删）
     */
    boolean unmark(String sessionId) {
        try {
            source.deleteIfExists(marker(sessionId));
            return true;
        } catch (Throwable e) {
            LOG.warn("Queue index unmark failed for '{}': {}", sessionId, e.getMessage());
            return false;
        }
    }

    private String marker(String sessionId) throws UnsupportedEncodingException {
        // 文件名安全编码（含 * 与 . 开头，防路径穿越与通配符）
        String s = URLEncoder.encode(sessionId, "UTF-8").replace("*", "%2A");
        return dir + "/" + (s.startsWith(".") ? "%2E" + s.substring(1) : s);
    }
}
