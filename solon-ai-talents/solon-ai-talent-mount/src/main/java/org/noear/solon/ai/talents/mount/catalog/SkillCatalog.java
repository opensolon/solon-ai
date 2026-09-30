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
    /**
     * 重新扫描所有技能来源并刷新目录。
     */
    void refresh();

    /**
     * 刷新技能目录；默认实现执行全量刷新。
     *
     * @param mountAlias 挂载别名；默认实现不使用此参数
     */
    default void refreshByMount(String mountAlias) {
        refresh();
    }

    /**
     * 获取当前允许访问的技能数量。
     *
     * @return 允许访问的技能数量
     */
    int getSkillCount();

    /**
     * 获取目录中的全部技能描述，不按访问权限过滤。
     *
     * @return 全部技能描述
     */
    Collection<SkillDescriptor> getDescriptors();

    /**
     * 搜索允许访问且匹配任一关键词的技能，最多返回 15 条。
     *
     * @param query 空白分隔的搜索关键词
     * @return 匹配的技能描述；查询为空时返回空集合
     */
    Collection<SkillDescriptor> searchDescriptors(String query);

    /**
     * 按名称或标识查找技能描述。
     *
     * @param name 技能名称或标识
     * @return 技能描述；未找到时返回 null
     */
    SkillDescriptor getDescriptor(String name);

    /**
     * 读取允许访问的技能内容。
     *
     * @param name 技能名称或标识
     * @return 技能内容；未找到、不允许访问或读取失败时返回 null
     */
    SkillContent readContent(String name);

    /**
     * 判断技能是否存在于目录且未被禁止访问。
     *
     * @param descriptor 待检查的技能描述
     * @return 可以访问时为 true，否则为 false
     */
    boolean isAllowed(SkillDescriptor descriptor);
}
