package org.noear.solon.ai.mcp.server.resource;

import org.noear.solon.ai.chat.content.ResourceBlock;

import java.util.List;
import java.util.ArrayList;

/**
 *
 * @author noear 2026/2/10 created
 *
 */
public class ResourceResult {
    private final List<ResourceBlock> resources = new ArrayList<>();

    public ResourceResult(List<ResourceBlock> resources) {
        this.resources.addAll(resources);
    }

    public String getContent() {
        if (resources.isEmpty()) {
            return null;
        } else {
            return resources.get(0).getContent();
        }
    }

    public List<ResourceBlock> getResources() {
        return resources;
    }

    @Override
    public String toString() {
        return resources.toString();
    }
}