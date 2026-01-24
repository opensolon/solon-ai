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
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.lang.Preview;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 技能描述实现类（提供流式构建支持）
 * <p>查询示例：</p>
 *
 * <pre>{@code
 * Skill weatherSkill = SkillDesc.builder("weather")
 *     .description("查询实时天气和预报")
 *     .isSupported("天气", "气温") // 关键词匹配
 *     .instruction("查询天气时，如果用户没说城市，默认查杭州。") // 静态指令
 *     .toolAdd(new WeatherTools()) // 挂载工具
 *     .build();
 * }</pre>
 *
 * @author noear
 * @since 3.8.4
 */
@Preview("3.8.4")
public class SkillDesc implements Skill {

    private final SkillMetadata metadata;

    private final Function<Prompt, Boolean> isSupportedHandler;
    private final Function<Prompt, String> getInstructionHandler;
    private final Consumer<Prompt> onAttachHandler;
    Function<Prompt, Collection<FunctionTool>> getToolsHandler;

    public SkillDesc(SkillMetadata metadata, Function<Prompt, Boolean> isSupportedHandler, Function<Prompt, String> getInstructionHandler, Consumer<Prompt> onAttachHandler, Function<Prompt, Collection<FunctionTool>> getToolsHandler) {
        this.metadata = metadata;
        this.getToolsHandler = getToolsHandler;
        this.isSupportedHandler = isSupportedHandler;
        this.getInstructionHandler = getInstructionHandler;
        this.onAttachHandler = onAttachHandler;
    }

    @Override
    public String name() {
        return metadata.getName();
    }

    @Override
    public String description() {
        return metadata.getDescription();
    }

    @Override
    public SkillMetadata metadata() {
        return metadata;
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        if (isSupportedHandler != null) {
            return isSupportedHandler.apply(prompt);
        }

        return true;
    }

    @Override
    public void onAttach(Prompt prompt) {
        if (onAttachHandler != null) {
            onAttachHandler.accept(prompt);
        }
    }

    @Override
    public String getInstruction(Prompt prompt) {
        if (getInstructionHandler != null) {
            return getInstructionHandler.apply(prompt);
        }

        return null;
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        return getToolsHandler.apply(prompt);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final SkillMetadata metadata;
        private final List<FunctionTool> tools = new ArrayList<>();

        private Function<Prompt, Boolean> isSupportedHandler;
        private Function<Prompt, String> getInstructionHandler;
        private Function<Prompt, Collection<FunctionTool>> getToolsHandler;
        private Consumer<Prompt> onAttachHandler;

        public Builder(String name) {
            metadata = new SkillMetadata(name, null);
        }

        public Builder description(String description) {
            metadata.description(description);
            return this;
        }

        public Builder metadata(Consumer<SkillMetadata> metadata) {
            metadata.accept(this.metadata);
            return this;
        }

        public Builder isSupported(String... keywords) {
            this.isSupportedHandler = (prompt) -> {
                String content = prompt.getUserContent();
                if (content == null) return false;
                for (String k : keywords) {
                    if (content.contains(k)) return true;
                }
                return false;
            };
            return this;
        }

        public Builder isSupported(Function<Prompt, Boolean> isSupported) {
            this.isSupportedHandler = isSupported;
            return this;
        }

        public Builder instruction(String instruction) {
            this.getInstructionHandler = (prompt) -> instruction;
            return this;
        }

        public Builder instruction(Function<Prompt, String> getInstruction) {
            this.getInstructionHandler = getInstruction;
            return this;
        }

        public Builder onAttach(Consumer<Prompt> onAttach) {
            this.onAttachHandler = onAttach;
            return this;
        }

        public Builder tools(Function<Prompt, Collection<FunctionTool>> getTools) {
            this.getToolsHandler = getTools;
            this.tools.clear();
            return this;
        }

        public Builder toolAdd(FunctionTool tool) {
            this.getToolsHandler = null;
            this.tools.add(tool);
            return this;
        }

        public Builder toolAdd(ToolProvider toolProvider) {
            this.getToolsHandler = null;
            this.tools.addAll(toolProvider.getTools());
            return this;
        }

        public Builder toolAdd(Object toolObj) {
            return toolAdd(new MethodToolProvider(toolObj));
        }

        public SkillDesc build() {
            if (getToolsHandler == null) {
                //如果没有，返回静态的工具集
                getToolsHandler = (prompt) -> tools;
            }

            return new SkillDesc(metadata, isSupportedHandler, getInstructionHandler, onAttachHandler, getToolsHandler);
        }
    }
}