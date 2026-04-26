/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.util;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;

/**
 * 有元数据（YamlFrontmatter）的 markdown 文档
 *
 * @author noear
 * @since 3.9.6
 */
public class Markdown {
    protected final ONode metadata = new ONode(Options.of(Feature.Decode_IgnoreError));
    protected String content = "";

    public ONode getMetadata() {
        return metadata;
    }

    /**
     * @since 3.10.5
     */
    public boolean hasMeta(String name) {
        return metadata.hasKey(name);
    }

    /**
     * @since 3.10.5
     */
    public ONode getMeta(String name) {
        return metadata.get(name);
    }

    public String getName() {
        return metadata.get("name").getString();
    }

    public String getDescription() {
        return metadata.get("description").getString();
    }

    public String getContent() {
        return content;
    }
}