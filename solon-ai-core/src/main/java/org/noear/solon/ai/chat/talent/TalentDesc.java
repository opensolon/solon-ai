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
package org.noear.solon.ai.chat.talent;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 工具包描述实现类（提供流式构建支持）
 * <p>查询示例：</p>
 *
 * <pre>{@code
 * Talent weatherTalent = TalentDesc.builder("weather")
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
public class TalentDesc implements Talent {

    private final TalentMetadata metadata;

    private final Function<Prompt, Boolean> isSupportedHandler;
    private final Function<Prompt, String> getInstructionHandler;
    private final Consumer<Prompt> onAttachHandler;
    private final Function<Prompt, Collection<FunctionTool>> getToolsHandler;
    private volatile boolean enabled;

    public TalentDesc(TalentMetadata metadata, boolean enabled, Function<Prompt, Boolean> isSupportedHandler, Function<Prompt, String> getInstructionHandler, Consumer<Prompt> onAttachHandler, Function<Prompt, Collection<FunctionTool>> getToolsHandler) {
        this.metadata = metadata;
        this.enabled = enabled;
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
    public TalentMetadata metadata() {
        return metadata;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        if (enabled != null) {
            this.enabled = enabled;
        }
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
        private final TalentMetadata metadata;
        private final Map<String, FunctionTool> tools = new LinkedHashMap<>();

        private Function<Prompt, Boolean> isSupportedHandler;
        private Function<Prompt, String> getInstructionHandler;
        private Function<Prompt, Collection<FunctionTool>> getToolsHandler;
        private Consumer<Prompt> onAttachHandler;
        private boolean enabled = true;

        public Builder(String name) {
            metadata = new TalentMetadata(name, null);
        }

        public Builder description(String description) {
            metadata.description(description);
            return this;
        }

        public Builder metadata(Consumer<TalentMetadata> metadata) {
            metadata.accept(this.metadata);
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
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

        public Builder toolAdd(FunctionTool... tools) {
            this.getToolsHandler = null;
            for (FunctionTool tool : tools) {
                this.tools.put(tool.name(), tool);
            }
            return this;
        }

        public Builder toolAdd(ToolProvider toolProvider) {
            this.getToolsHandler = null;
            for (FunctionTool tool : toolProvider.getTools()) {
                this.tools.put(tool.name(), tool);
            }
            return this;
        }

        public Builder toolAdd(Object toolObj) {
            if (toolObj instanceof FunctionTool) {
                FunctionTool tool = (FunctionTool) toolObj;
                this.tools.put(tool.name(), tool);
                return this;
            } else {
                return toolAdd(new MethodToolProvider(toolObj));
            }
        }

        public TalentDesc build() {
            if (getToolsHandler == null) {
                //如果没有，返回静态的工具集
                getToolsHandler = (prompt) -> tools.values();
            }

            return new TalentDesc(metadata, enabled, isSupportedHandler, getInstructionHandler, onAttachHandler, getToolsHandler);
        }
    }
}