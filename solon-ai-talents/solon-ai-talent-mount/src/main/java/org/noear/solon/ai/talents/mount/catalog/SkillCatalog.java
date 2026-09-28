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

import java.util.Collection;

/** 
 * 技能目录：负责技能索引、搜索、读取和刷新。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public interface SkillCatalog {
    void refresh();

    default void refreshByMount(String mountAlias) {
        refresh();
    }

    int getSkillCount();

    Collection<SkillDescriptor> getDescriptors();

    Collection<SkillDescriptor> searchDescriptors(String query);

    SkillDescriptor getDescriptor(String name);

    SkillContent readContent(String name);

    boolean isAllowed(SkillDescriptor descriptor);
}
