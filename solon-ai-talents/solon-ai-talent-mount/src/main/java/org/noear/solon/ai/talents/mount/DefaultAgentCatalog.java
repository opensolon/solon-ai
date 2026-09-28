package org.noear.solon.ai.talents.mount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 基于统一挂载注册表的来源无关 Agent 目录。 */
public final class DefaultAgentCatalog implements AgentCatalog {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultAgentCatalog.class);
    private final MountManager mountManager;
    private volatile Map<String, AgentMd> agents = Collections.emptyMap();

    public DefaultAgentCatalog(MountManager mountManager) {
        this.mountManager = mountManager;
        refresh();
    }

    @Override
    public void refresh() {
        Map<String, AgentMd> discoveredAgents = new LinkedHashMap<>();
        for (Mount mount : mountManager.getSourceMounts()) {
            if (mount.isEnabled() && mount.getType() == MountType.AGENTS) {
                scanAgents(mount, discoveredAgents);
            }
        }
        agents = Collections.unmodifiableMap(discoveredAgents);
    }

    @Override
    public void refreshByMount(String mountAlias) {
        refresh();
    }

    @Override
    public Collection<AgentMd> getAgents() {
        return agents.values();
    }

    @Override
    public Collection<AgentMd> getAgentsByMount(String mountAlias) {
        String key = normalizeAlias(mountAlias);
        if (key == null) {
            return Collections.emptyList();
        }
        java.util.List<AgentMd> result = new java.util.ArrayList<>();
        for (AgentMd agent : agents.values()) {
            if (key.equals(agent.getMountAlias())) {
                result.add(agent);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public AgentMd getAgent(String name) {
        return name == null ? null : agents.get(name);
    }

    private void scanAgents(Mount mount, Map<String, AgentMd> result) {
        try {
            for (MountEntry entry : mount.getSource().find("", FindOptions.builder()
                    .maxDepth(3).maxEntries(10000).filesOnly(true).build())) {
                String path = entry.getPath();
                String fileName = entry.getName();
                if (hiddenParent(path) || fileName.startsWith(".") || !fileName.endsWith(".md")) continue;
                String name = fileName.substring(0, fileName.length() - 3);
                result.put(name, new AgentMd(name, mount.getAlias(), mount.getSource(), path));
            }
        } catch (IOException e) {
            LOG.debug("Scan agent mount failed: {}", mount.getAlias(), e);
        }
    }

    private static boolean hiddenParent(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].startsWith(".")) return true;
        }
        return false;
    }

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
