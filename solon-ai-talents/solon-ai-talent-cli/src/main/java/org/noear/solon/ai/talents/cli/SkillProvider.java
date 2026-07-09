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
package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.talents.mount.SkillDir;

import java.util.Collection;

/**
 * 技能提供者
 *
 * @author noear 2026/6/15 created
 * @since 4.0.1
 */
public interface SkillProvider {
    /**
     * 刷新
     */
    void refresh();

    /**
     * 刷新指定挂载的技能（用于与 FileWatchService 对接）
     */
    default void refreshByGroup(String groupName) {
        refresh();
    }

    /**
     * 获取技能数
     */
    int getSkillCount();

    /**
     * 获取所有 Skill 包
     */
    Collection<SkillDir> getSkillAll();

    /**
     * 查找技能包
     */
    Collection<SkillDir> searchSkill(String query);

    /**
     * 获取 Skill 包
     */
    SkillDir getSkill(String name);

    /**
     * 读取 Skill 内容
     */
    String readSkill(String name);
}