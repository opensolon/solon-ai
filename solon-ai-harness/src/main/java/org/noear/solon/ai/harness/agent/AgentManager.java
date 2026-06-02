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

import org.noear.solon.ai.talents.mount.AgentMd;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.core.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private static final Logger LOG = LoggerFactory.getLogger(AgentManager.class);
    private static final String AGENT_MD_BASE = "META-INF/solon/ai/harness/";

    private final MountManager mountManager;
    private final Map<String, AgentDefinition> agentMap = new ConcurrentHashMap<>();


    /**
     * 完整构造（支持从 MountManager 加载自定义代理）
     */
    public AgentManager(MountManager mountManager) {
        this.mountManager = mountManager;
        loadBuiltinAgents();
    }

    private void loadBuiltinAgents() {
        loadAgentFile("bash", ResourceUtil.getResource(AGENT_MD_BASE + "bash.md"), null);
        loadAgentFile("explore", ResourceUtil.getResource(AGENT_MD_BASE + "explore.md"), null);
        loadAgentFile("plan", ResourceUtil.getResource(AGENT_MD_BASE + "plan.md"), null);
        loadAgentFile("general", ResourceUtil.getResource(AGENT_MD_BASE + "general.md"), null);
        loadAgentFile("git-summary", ResourceUtil.getResource(AGENT_MD_BASE + "git-summary.md"), null);
    }

    public void addAgent(AgentDefinition agentDefinition) {
        agentMap.putIfAbsent(agentDefinition.getName(), agentDefinition);
    }

    /**
     * 获取指定名称的代理（支持自定义代理）
     */
    public AgentDefinition getAgent(String agentName) {
        // 1. 优先从缓存取（含内置 + 已解析的挂载代理）
        AgentDefinition cached = agentMap.get(agentName);
        if (cached != null) {
            return cached;
        }

        // 2. 从 MountManager 的 AgentMd 按需解析
        if (mountManager != null) {
            AgentMd agentMd = mountManager.getAgent(agentName);
            if (agentMd != null) {
                AgentDefinition definition = loadFromAgentMd(agentMd);
                agentMap.put(agentName, definition);
                return definition;
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
        if (mountManager != null) {
            return mountManager.getAgent(agentName) != null;
        }
        return false;
    }

    /**
     * 获取所有已注册的代理
     */
    public Collection<AgentDefinition> getAgents() {
        Map<String, AgentDefinition> all = new LinkedHashMap<>(agentMap);

        // 补充挂载代理（未被缓存的）
        if (mountManager != null) {
            for (AgentMd agentMd : mountManager.getAgents()) {
                if (!all.containsKey(agentMd.getName())) {
                    AgentDefinition def = loadFromAgentMd(agentMd);
                    all.put(agentMd.getName(), def);
                    agentMap.put(agentMd.getName(), def); // 顺带缓存
                }
            }
        }

        return all.values().stream()
                .filter(a -> !a.getMetadata().isHidden())
                .collect(Collectors.toList());
    }

    /**
     * 清除所有代理
     */
    public void clear() {
        agentMap.clear();
    }

    /**
     * 仅清除自定义代理（保留内置代理）
     */
    public void clearCustomAgents() {
        agentMap.entrySet().removeIf(e -> e.getValue().getMountAlias() != null);
    }

    /**
     * 按挂载别名清理已缓存的代理定义
     */
    public synchronized void removeByMountAlias(String mountAlias) {
        if (mountAlias == null) {
            return;
        }
        agentMap.entrySet().removeIf(e -> mountAlias.equals(e.getValue().getMountAlias()));
    }

    /**
     * 从 AgentMd 解析完整定义
     */
    private AgentDefinition loadFromAgentMd(AgentMd agentMd) {
        try {
            List<String> lines = Files.readAllLines(agentMd.getFilePath(), StandardCharsets.UTF_8);
            AgentDefinition definition = AgentDefinition.fromMarkdown(lines);

            String name = definition.getName();
            if (name == null || name.isEmpty()) {
                name = agentMd.getName();
            }

            definition.setMountAlias(agentMd.getMountAlias());
            return definition;
        } catch (IOException e) {
            LOG.error("Load agent failed from AgentMd: {}", agentMd.getFilePath(), e);
            throw new RuntimeException("Failed to load agent: " + agentMd.getName(), e);
        }
    }

    /**
     * 从 URL 加载代理定义（内置代理用）
     */
    public void loadAgentFile(String fileName, URL url, String mountAlias) {
        if (url == null) {
            return;
        }

        try {
            String[] fullContent = ResourceUtil.getResourceAsString(url).split("\n");

            loadAgentFile(fileName, Arrays.asList(fullContent), mountAlias);
        } catch (IOException e) {
            LOG.error("Load agent failed, file: {}", url, e);
        }
    }

    public void loadAgentFile(String fileName, List<String> fullContent, String mountAlias) {
        AgentDefinition definition = AgentDefinition.fromMarkdown(fullContent);

        String agentTypeName = definition.getName();

        if (agentTypeName == null || agentTypeName.isEmpty()) {
            agentTypeName = fileName.substring(0, fileName.length() - 3);
        }

        if (mountAlias != null) {
            definition.setMountAlias(mountAlias);
        }

        agentMap.put(agentTypeName, definition);
    }
}
