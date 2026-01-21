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

import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.lang.Preview;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;

/**
 * 基于注解的技能（基类）
 *
 * @author noear
 * @since 3.8.4
 */
@Preview("3.8.4")
public abstract class AbstractSkill implements Skill {
    protected final SkillMetadata metadata;
    protected final List<FunctionTool> tools;

    protected AbstractSkill() {
        this.tools = new ArrayList<>();
        this.tools.addAll(new MethodToolProvider(this).getTools());

        this.metadata = new SkillMetadata(this.name(), this.description());
    }

    @Override
    public SkillMetadata metadata() {
        return metadata;
    }

    @Override
    public Collection<FunctionTool> getTools() {
        return tools;
    }
}