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
package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.mount.catalog.AgentCatalog;
import org.noear.solon.ai.mount.catalog.AgentDescriptor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 代理定义管理器
 *
 * @author bai
 * @author noear
 * @since 3.9.5
 */
public class AgentManager {
    private final AgentCatalog agentCatalog;
    private final Map<String, AgentDefinition> agentMap = new ConcurrentHashMap<>();


    /**
     * 完整构造（支持从 AgentCatalog 加载自定义代理）
     */
    public AgentManager(AgentCatalog agentCatalog) {
        this.agentCatalog = agentCatalog;
    }

    private volatile boolean sealed;

    /**
     * 封口（SERVER 模式在 {@code build()} 末尾调用）：之后 {@link #addAgent} 与 {@link #clear} 抛
     * {@link IllegalStateException}。{@link #addAgentIfAbsent} 不受封口影响：它永不覆盖、也不遮蔽已有定义，
     * 运行期新增全局子代理由调用方的策略把关（如 {@code agentPersistPolicy}）。
     *
     * @since 4.2.0
     */
    public void seal() {
        this.sealed = true;
    }

    public boolean isSealed() {
        return sealed;
    }

    private void assertNotSealed(String op) {
        if (sealed) {
            throw new IllegalStateException("AgentManager is sealed (runtimeMutable=false): " + op);
        }
    }

    /**
     * 仅在不存在同名定义时注册（含挂载目录里的：运行时注册表优先于目录，同名注册会遮蔽内置 / 工作区代理）。
     *
     * @return true = 已注册；false = 已有同名定义（未做任何修改）
     */
    public synchronized boolean addAgentIfAbsent(AgentDefinition agentDefinition) {
        if (hasAgent(agentDefinition.getName())) {
            return false;
        }
        return agentMap.putIfAbsent(agentDefinition.getName(), agentDefinition) == null;
    }

    public void addAgent(AgentDefinition agentDefinition) {
        assertNotSealed("addAgent");
        agentMap.put(agentDefinition.getName(), agentDefinition);
    }


    public AgentDefinition getAgentOrNil(String agentName) {
        // 内置与运行时注册的代理优先；挂载代理按当前目录读取，避免刷新后返回旧定义。
        AgentDefinition cached = agentMap.get(agentName);
        if (cached != null) {
            return cached;
        }

        if (agentCatalog != null) {
            AgentDescriptor agentMd = agentCatalog.getAgent(agentName);
            if (agentMd != null) {
                return loadFromAgentMd(agentMd);
            }
        }

        return null;
    }

    /**
     * 获取指定名称的代理（支持自定义代理）
     */
    public AgentDefinition getAgent(String agentName) {
        // 内置与运行时注册的代理优先；挂载代理按当前目录读取，避免刷新后返回旧定义。
        AgentDefinition cached = agentMap.get(agentName);
        if (cached != null) {
            return cached;
        }

        if (agentCatalog != null) {
            AgentDescriptor agentMd = agentCatalog.getAgent(agentName);
            if (agentMd != null) {
                return loadFromAgentMd(agentMd);
            }
        }

        throw new IllegalArgumentException("Agent not found: " + agentName);
    }

    /**
     * 检查代理是否已注册
     */
    public boolean hasAgent(String agentName) {
        if (agentMap.containsKey(agentName)) {
            return true;
        }
        if (agentCatalog != null) {
            return agentCatalog.getAgent(agentName) != null;
        }
        return false;
    }

    /**
     * 获取所有已注册的代理
     */
    public Collection<AgentDefinition> getAgents() {
        Map<String, AgentDefinition> all = new LinkedHashMap<>(agentMap);

        // 来源定义不缓存；目录刷新或文件内容变化后，下次查询读取当前内容。
        if (agentCatalog != null) {
            for (AgentDescriptor agentMd : agentCatalog.getAgents()) {
                if (!all.containsKey(agentMd.getName())) {
                    all.put(agentMd.getName(), loadFromAgentMd(agentMd));
                }
            }
        }

        return all.values().stream()
                .filter(a -> !a.getMetadata().isHidden())
                .collect(Collectors.toList());
    }

    /**
     * 按挂载别名清理已缓存的代理定义。
     */
    public synchronized void removeByMountAlias(String mountAlias) {
        if (mountAlias == null) {
            return;
        }
        agentMap.entrySet().removeIf(e -> mountAlias.equals(e.getValue().getMountAlias()));
    }

    /**
     * 清除所有代理
     */
    public void clear() {
        assertNotSealed("clear");
        agentMap.clear();
    }

    /**
     * 仅清除自定义代理（保留内置代理）
     */
    public void clearCustomAgents() {
        agentMap.entrySet().removeIf(e -> e.getValue().getMountAlias() != null);
    }


    /**
     * 基于目录中已解析的 Markdown 构建运行时定义。
     */
    private AgentDefinition loadFromAgentMd(AgentDescriptor agentMd) {
        AgentDefinition definition = AgentDefinition.fromParsedMarkdown(agentMd.getMarkdown());
        definition.setMountAlias(agentMd.getMountAlias());
        return definition;
    }
}
