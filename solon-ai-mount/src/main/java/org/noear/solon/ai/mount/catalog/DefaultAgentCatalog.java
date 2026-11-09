/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.mount.catalog;

import org.noear.solon.ai.mount.*;
import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于统一挂载注册表的来源无关 Agent 目录。
 *
 * <p>创建时订阅 {@link MountManager} 的挂载变更（{@link MountListener}），挂载注册/移除后自动刷新。</p>
 *
 * @author noear
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class DefaultAgentCatalog implements AgentCatalog {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultAgentCatalog.class);
    private final MountManager mountManager;
    private volatile Map<String, AgentDescriptor> agents = Collections.emptyMap();
    /**
     * 刷新锁。
     *
     * <p>临界区内要逐个挂载扫盘读 Agent 定义（远程挂载还会走网络），属于持锁 I/O。
     * 用 {@link ReentrantLock} 而非方法监视器：在支持虚拟线程的 JDK（21–23）上，
     * 进入被占用的监视器会钉住载体线程。快照字段为 volatile，去掉监视器不影响可见性。</p>
     */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /**
     * 创建目录、立即扫描挂载中的 Agent，并订阅挂载变更。
     *
     * @param mountManager 挂载管理器
     */
    public DefaultAgentCatalog(MountManager mountManager) {
        this.mountManager = mountManager;
        refresh();
        mountManager.addListener(new MountListener() {
            @Override
            public void onMountRegistered(Mount mount) {
                refreshByMount(mount.getAlias());
            }

            @Override
            public void onMountRemoved(Mount mount) {
                refreshByMount(mount.getAlias());
            }
        });
    }

    /**
     * 扫描所有启用的 Agent 来源挂载并替换目录快照。
     */
    @Override
    public void refresh() {
        refreshLock.lock();
        try {
            Map<String, AgentDescriptor> discoveredAgents = new LinkedHashMap<>();
            for (Mount mount : mountManager.getMounts()) {
                if (mount.isEnabled() && mount.getType() == MountType.AGENTS) {
                    scanAgents(mount, discoveredAgents);
                }
            }
            agents = Collections.unmodifiableMap(discoveredAgents);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 委托 {@link #refresh()} 完整刷新目录，而非仅刷新指定挂载。
     *
     * @param mountAlias 请求刷新的挂载别名；当前不使用
     */
    @Override
    public void refreshByMount(String mountAlias) {
        refresh();
    }

    /**
     * 获取当前目录快照中的全部 Agent 描述。
     *
     * @return 不可修改的 Agent 描述集合
     */
    @Override
    public Collection<AgentDescriptor> getAgents() {
        return agents.values();
    }

    /**
     * 按规范化后的挂载别名筛选 Agent 描述。
     *
     * @param mountAlias 挂载别名，可带或不带 {@code @} 前缀
     * @return 不可修改的 Agent 描述集合；别名无效时返回空集合
     */
    @Override
    public Collection<AgentDescriptor> getAgentsByMount(String mountAlias) {
        String key = normalizeAlias(mountAlias);
        if (key == null) {
            return Collections.emptyList();
        }
        java.util.List<AgentDescriptor> result = new java.util.ArrayList<>();
        for (AgentDescriptor agent : agents.values()) {
            if (key.equals(agent.getMountAlias())) {
                result.add(agent);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 按名称从当前目录快照中查询 Agent 描述。
     *
     * @param name Agent 名称
     * @return 对应的 Agent 描述；不存在或名称为 {@code null} 时返回 {@code null}
     */
    @Override
    public AgentDescriptor getAgent(String name) {
        return name == null ? null : agents.get(name);
    }

    /**
     * 扫描挂载中的非隐藏 Markdown 文件，并按名称收录有效的 Agent。
     *
     * @param mount 待扫描的挂载
     * @param result 用于收录 Agent 的映射；同名时保留已有条目
     */
    private void scanAgents(Mount mount, Map<String, AgentDescriptor> result) {
        List<MountEntry> entries;
        // 发现阶段是尽力而为：任何异常都不能向上冒泡，否则会让挂载注册、乃至整个工作区启动失败。
        try {
            entries = mount.getSource().find("", FindOptions.builder()
                    .maxDepth(3).maxEntries(10000).filesOnly(true).build());
        } catch (Throwable e) {
            LOG.debug("Scan agent mount failed: {}", mount.getAlias(), e);
            return;
        }

        for (MountEntry entry : entries) {
            String path = entry.getPath();
            String fileName = entry.getName();
            if (hiddenParent(path) || fileName.startsWith(".") || !fileName.endsWith(".md")) continue;
            try {
                AgentDescriptor descriptor = AgentDescriptor.parse(
                        mount.getAlias(), mount.getSource(), path);
                result.putIfAbsent(descriptor.getName(), descriptor);
            } catch (Throwable e) {
                // 单个 Agent 定义异常时跳过，不影响同一来源的其余定义
                LOG.debug("Skip invalid agent: {}:{}", mount.getAlias(), path, e);
            }
        }
    }

    /**
     * 判断路径的父目录中是否存在隐藏目录。
     *
     * @param path 文件路径
     * @return 父目录中存在以点开头的目录时返回 {@code true}
     */
    private static boolean hiddenParent(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].startsWith(".")) return true;
        }
        return false;
    }

    /**
     * 去除挂载别名两端空白并补齐 {@code @} 前缀。
     *
     * @param alias 原始挂载别名
     * @return 规范化后的别名；为空或仅含空白时返回 {@code null}
     */
    private static String normalizeAlias(String alias) {
        if (alias == null) {
            return null;
        }
        String value = alias.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.startsWith("@") ? value : "@" + value;
    }
}
