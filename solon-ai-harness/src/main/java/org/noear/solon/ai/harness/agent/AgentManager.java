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

import org.noear.solon.core.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final Map<String, AgentDefinition> agentMap = new ConcurrentHashMap<>();

    public AgentManager() {
        loadAgentFile("bash", ResourceUtil.getResource(AGENT_MD_BASE + "bash.md"));
        loadAgentFile("explore", ResourceUtil.getResource(AGENT_MD_BASE + "explore.md"));
        loadAgentFile("plan", ResourceUtil.getResource(AGENT_MD_BASE + "plan.md"));
        loadAgentFile("general", ResourceUtil.getResource(AGENT_MD_BASE + "general.md"));
    }

    public void addAgent(AgentDefinition agentDefinition) {
        agentMap.putIfAbsent(agentDefinition.getName(), agentDefinition);
    }

    /**
     * 获取指定名称的代理（支持自定义代理）
     */
    public AgentDefinition getAgent(String agentName) {
        // 1. 首先尝试作为预定义类型
        AgentDefinition agentDefinition = agentMap.get(agentName);

        if (agentDefinition == null) {
            throw new IllegalArgumentException("Agent not found:" + agentName);
        } else {
            return agentDefinition;
        }
    }


    /**
     * 检查代理是否已注册
     */
    public boolean hasAgent(String agentName) {
        return agentMap.containsKey(agentName);
    }

    /**
     * 获取所有已注册的代理
     */
    public Collection<AgentDefinition> getAgents() {
        return agentMap.values().stream()
                .filter(a -> a.getMetadata().isHidden() == false)
                .collect(Collectors.toList());
    }

    /**
     * 清除所有代理
     */
    public void clear() {
        agentMap.clear();
        pathCached.clear();
    }

    private final Set<Path> pathCached = new HashSet<>();

    /**
     * 注册自定义 agents 池
     *
     * @param dir       agents 目录路径，可以是绝对路径或相对路径
     * @param recursive 是否递归扫描子目录（用于团队成员目录）
     */
    public void agentPool(Path dir, boolean recursive) {
        if (dir == null) {
            return;
        }

        Path path = dir.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            LOG.debug("Agent pool directory does not exist: {}", dir);
            return;
        }

        if (!Files.isDirectory(path)) {
            LOG.debug("Agent pool path is not a directory: {}", dir);
            return;
        }

        if (pathCached.contains(path)) {
            return;
        } else {
            pathCached.add(path);
        }

        try {
            if (recursive) {
                // 递归扫描子目录（用于团队成员）
                Files.walk(path)
                        .filter(p -> p.toString().endsWith(".md"))
                        .forEach(file -> loadAgentFile(file));
            } else {
                // 只扫描当前目录
                try (Stream<Path> stream = Files.list(path)) {
                    List<Path> files = stream.filter(p -> p.toString().endsWith(".md"))
                            .collect(Collectors.toList());

                    for (Path file : files) {
                        loadAgentFile(file);
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to scan agent pool directory: {}", dir, e);
        }
    }

    /**
     * 注册自定义 agents 池（不递归）
     *
     * @param dir agents 目录路径，可以是绝对路径或相对路径
     */
    public void agentPool(Path dir) {
        agentPool(dir, false);
    }

    /**
     * 从文件加载代理定义
     */
    public void loadAgentFile(String fileName, URL url) {
        if (url == null) {
            return;
        }

        try {
            String[] fullContent = ResourceUtil.getResourceAsString(url).split("\n");

            loadAgentFile(fileName, Arrays.asList(fullContent));
        } catch (IOException e) {
            LOG.error("Load agent failed, file: {}", url, e);
        }
    }

    /**
     * 从文件加载代理定义
     */
    public void loadAgentFile(Path file) {
        if (file == null) {
            return;
        }

        try {
            String fileName = file.getFileName().toString();
            List<String> fullContent = Files.readAllLines(file, StandardCharsets.UTF_8);

            loadAgentFile(fileName, fullContent);
        } catch (IOException e) {
            LOG.error("Load agent failed, file: {}", file, e);
        }
    }

    public void loadAgentFile(String fileName, List<String> fullContent) {
        AgentDefinition definition = AgentDefinition.fromMarkdown(fullContent);

        String agentTypeName = definition.getName();

        if (agentTypeName == null || agentTypeName.isEmpty()) {
            agentTypeName = fileName.substring(0, fileName.length() - 3);
        }

        agentMap.put(agentTypeName, definition);
    }
}