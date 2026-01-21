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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ReAct (Reason + Act) 协同推理智能体构建器
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActAgentBuilder {
    private ReActAgentConfig config;

    public ReActAgentBuilder(ChatModel chatModel) {
        this.config = new ReActAgentConfig(chatModel);
    }

    public ReActAgentBuilder then(Consumer<ReActAgentBuilder> consumer) {
        consumer.accept(this);
        return this;
    }

    public ReActAgentBuilder name(String val) {
        config.setName(val);
        return this;
    }

    public ReActAgentBuilder title(String val) {
        config.setTitle(val);
        return this;
    }

    public ReActAgentBuilder description(String val) {
        config.setDescription(val);
        return this;
    }

    public ReActAgentBuilder profile(AgentProfile profile) {
        config.setProfile(profile);
        return this;
    }

    public ReActAgentBuilder profile(Consumer<AgentProfile> profileConsumer) {
        profileConsumer.accept(config.getProfile());
        return this;
    }

    /**
     * 微调推理图结构
     */
    public ReActAgentBuilder graphAdjuster(Consumer<GraphSpec> graphBuilder) {
        config.setGraphAdjuster(graphBuilder);
        return this;
    }

    /**
     * 定义 LLM 输出中的任务结束标识符
     */
    public ReActAgentBuilder finishMarker(String val) {
        config.setFinishMarker(val);
        return this;
    }

    public ReActAgentBuilder systemPrompt(ReActSystemPrompt val) {
        config.setSystemPrompt(val);
        return this;
    }

    public ReActAgentBuilder systemPrompt(Consumer<ReActSystemPrompt.Builder> promptBuilder) {
        ReActSystemPrompt.Builder builder = ReActSystemPrompt.builder();
        promptBuilder.accept(builder);
        config.setSystemPrompt(builder.build());
        return this;
    }

    public ReActAgentBuilder modelOptions(Consumer<ModelOptionsAmend<?, ReActInterceptor>> chatOptions) {
        chatOptions.accept(config.getDefaultOptions().getModelOptions());
        return this;
    }

    public ReActAgentBuilder retryConfig(int maxRetries, long retryDelayMs) {
        config.getDefaultOptions().setRetryConfig(maxRetries, retryDelayMs);
        return this;
    }

    /**
     * 单次任务允许的最大推理步数（防止死循环）
     */
    public ReActAgentBuilder maxSteps(int val) {
        config.getDefaultOptions().setMaxSteps(val);
        return this;
    }

    public ReActAgentBuilder outputKey(String val) {
        config.setOutputKey(val);
        return this;
    }

    public ReActAgentBuilder outputSchema(String val) {
        config.getDefaultOptions().setOutputSchema(val);
        return this;
    }

    public ReActAgentBuilder outputSchema(Type type) {
        config.getDefaultOptions().setOutputSchema(ToolSchemaUtil.buildOutputSchema(type));
        return this;
    }

    public ReActAgentBuilder sessionWindowSize(int val) {
        config.getDefaultOptions().setSessionWindowSize(val);
        return this;
    }


    public ReActAgentBuilder defaultToolAdd(FunctionTool tool) {
        config.getDefaultOptions().getModelOptions().toolAdd(tool);
        return this;
    }

    public ReActAgentBuilder defaultToolAdd(Collection<FunctionTool> tools) {
        config.getDefaultOptions().getModelOptions().toolAdd(tools);
        return this;
    }

    public ReActAgentBuilder defaultToolAdd(ToolProvider toolProvider) {
        config.getDefaultOptions().getModelOptions().toolAdd(toolProvider);
        return this;
    }

    public ReActAgentBuilder defaultSkillAdd(Skill skill) {
        config.getDefaultOptions().getModelOptions().skillAdd(skill);
        return this;
    }

    public ReActAgentBuilder defaultSkillAdd(Skill skill, int index) {
        config.getDefaultOptions().getModelOptions().skillAdd(index, skill);
        return this;
    }

    public ReActAgentBuilder defaultToolContextPut(String key, Object value) {
        config.getDefaultOptions().getModelOptions().toolContextPut(key, value);
        return this;
    }

    public ReActAgentBuilder defaultToolContextPut(Map<String, Object> objectsMap) {
        config.getDefaultOptions().getModelOptions().toolContextPut(objectsMap);
        return this;
    }

    public ReActAgentBuilder defaultInterceptorAdd(ReActInterceptor... vals) {
        for (ReActInterceptor val : vals) {
            config.getDefaultOptions().getModelOptions().interceptorAdd(0, val);
        }

        return this;
    }

    public ReActAgentBuilder defaultInterceptorAdd(int index, ReActInterceptor val) {
        config.getDefaultOptions().getModelOptions().interceptorAdd(index, val);
        return this;
    }

    public ReActAgentBuilder enablePlanning(boolean val) {
        config.getDefaultOptions().setEnablePlanning(val);
        return this;
    }

    public ReActAgentBuilder planInstruction(Function<ReActTrace, String> provider) {
        config.getDefaultOptions().setPlanInstructionProvider(provider);
        return this;
    }

    public ReActAgent build() {
        if (config.getName() == null) {
            config.setName("react_agent");
        }

        if (config.getDescription() == null) {
            config.setDescription(config.getTitle() != null ? config.getTitle() : config.getName());
        }

        return new ReActAgent(config);
    }
}