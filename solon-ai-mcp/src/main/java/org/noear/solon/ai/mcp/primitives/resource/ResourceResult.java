/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.mcp.primitives.resource;

import org.noear.solon.ai.chat.content.ResourceBlock;

import java.util.List;
import java.util.ArrayList;

/**
 * 资源读取结果
 *
 * @author noear
 * @since 3.9.2
 */
public class ResourceResult {
    private final List<ResourceBlock> resources = new ArrayList<>();

    public ResourceResult() {
        //用于反序列化
    }

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

    public int size() {
        return resources.size();
    }

    public List<ResourceBlock> getResources() {
        return resources;
    }

    @Override
    public String toString() {
        return resources.toString();
    }
}