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
package org.noear.solon.ai.chat.skill;

import org.noear.solon.Utils;
import org.noear.solon.lang.Preview;

import java.io.Serializable;

/**
 * 技能元信息
 *
 * @author noear
 * @since 3.8.4
 */
@Preview("3.8.4")
public class SkillMetadata implements Serializable {
    private String name;        // 技能名称
    private String description; // 技能简介
    private String category;    // 分类
    private String[] tags;      // 标签
    private boolean sensitive;  // 是否敏感（用于安全拦截）

    public SkillMetadata(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String[] tags() { return tags; }
    public boolean isSensitive() { return sensitive; }

    public SkillMetadata description(String description) {
        this.description = description;
        return this;
    }

    public SkillMetadata category(String category) {
        this.category = category;
        return this;
    }

    public SkillMetadata tags(String... tags) {
        this.tags = tags;
        return this;
    }

    public SkillMetadata sensitive(boolean sensitive) {
        this.sensitive = sensitive;
        return this;
    }

    /**
     * 简单的关键词匹配逻辑，用于 search_tools
     */
    public boolean match(String keywords) {
        if (Utils.isEmpty(keywords)) return false;
        String kv = keywords.toLowerCase();
        return (name != null && name.toLowerCase().contains(kv)) ||
                (description != null && description.toLowerCase().contains(kv)) ||
                (category != null && category.toLowerCase().contains(kv)); // 增加分类匹配
    }
}