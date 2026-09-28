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
package org.noear.solon.ai.talents.mount.catalog;

import org.noear.solon.lang.Preview;

import java.util.Objects;

/** 
 * 来源无关的技能元数据；id 是可用于读取的唯一逻辑路径。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class SkillDescriptor {
    private final String id;
    private final String name;
    private final String description;
    private final String version;

    public SkillDescriptor(String id, String name, String description, String version) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.version = version == null ? "" : version;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
}
