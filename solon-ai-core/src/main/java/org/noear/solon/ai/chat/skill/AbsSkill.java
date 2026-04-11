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

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * 技能定制基类
 *
 * @author noear
 * @since 3.8.4
 */
@Preview("3.8.4")
public abstract class AbsSkill implements Skill {
    private volatile SkillMetadata metadata;
    private final Map<String, FunctionTool> toolMap;
    private final List<FunctionTool> tools;

    protected AbsSkill() {
        this(null);
    }

    /**
     * @since 3.10.2
     */
    protected AbsSkill(Map<String, Object> binding) {
        this.tools = new ArrayList<>();
        this.tools.addAll(new MethodToolProvider(this)
                .binding(binding)
                .includeProvide(false)
                .getTools());

        Map<String, FunctionTool> toolMap0 = new LinkedHashMap<>();
        for (FunctionTool tool : tools) {
            toolMap0.put(tool.name(), tool);
        }

        toolMap = Collections.unmodifiableMap(toolMap0);
    }

    public Map<String, FunctionTool> getToolMap() {
        return toolMap;
    }

    public Collection<FunctionTool> getToolAry() {
        return tools;
    }

    public Collection<FunctionTool> getToolAry(String... names) {
        return getToolAry(Arrays.asList(names));
    }

    public Collection<FunctionTool> getToolAry(Collection<String> names) {
        List<FunctionTool> list = new ArrayList<>();
        for (String key : names) {
            if (toolMap.containsKey(key)) {
                list.add(toolMap.get(key));
            }
        }
        return list;
    }

    @Override
    public SkillMetadata metadata() {
        if (this.metadata == null) {
            this.metadata = new SkillMetadata(this.name(), this.description());
        }

        return this.metadata;
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        return tools;
    }
}