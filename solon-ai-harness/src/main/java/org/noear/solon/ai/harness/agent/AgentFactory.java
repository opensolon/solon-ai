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
package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.skills.cli.TerminalSkillProxy;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;

/**
 * 代理工厂
 *
 * @author noear 2026/3/20 created
 */
public class AgentFactory {
    //**
    private static String[] TOOL_ALL_FULL = {"read", "write", "edit", "glob", "grep", "ls", "bash", "bash_start", "bash_wait", "bash_stdin", "bash_stop", "skill", "todo", "code", "codesearch", "websearch", "webfetch", "task", "generate", "mcp", "restapi", "hitl", "lsp"};
    //*
    private static String[] TOOL_ALL_PUBLIC = {"read", "write", "edit", "glob", "grep", "ls", "bash", "bash_start", "bash_wait", "bash_stdin", "bash_stop", "skill", "todo", "code", "codesearch", "websearch", "webfetch", "task", "lsp"};
    //pi
    private static String[] TOOL_PI = {"read", "write", "edit", "bash", "bash_start", "bash_wait", "bash_stdin", "bash_stop"};


    /**
     * 根据定义生成代理
     */
    public static ReActAgent.Builder create(HarnessEngine engine, AgentDefinition agentDefinition) {
        return create(engine, agentDefinition, null);
    }

    /**
     * 根据定义生成代理
     */
    public static ReActAgent.Builder create(HarnessEngine engine, AgentDefinition agentDefinition, @Nullable String sessionModel) {
        final String selectedModel;

        if (Assert.isEmpty(agentDefinition.getModel())) {
            //如果没有配置指定，则用会话选中的
            selectedModel = sessionModel;
        } else {
            selectedModel = agentDefinition.getModel();
        }

        ChatModel chatModel = engine.getModelOrMain(selectedModel);

        ReActAgent.Builder builder = ReActAgent.of(chatModel)
                .retryConfig(engine.getProps().getModelRetries(), 1000L);

        AgentDefinition.Metadata metadata = agentDefinition.getMetadata();

        builder.name(agentDefinition.getName());

        if (Assert.isNotEmpty(engine.getProps().getWorkspace())) {
            builder.defaultToolContextPut(HarnessEngine.ATTR_CWD, engine.getProps().getWorkspace());
        }

        if (Assert.isNotEmpty(agentDefinition.getSystemPrompt())) {
            builder.systemPrompt(r -> agentDefinition.getSystemPrompt());
        }

        if (metadata.getMaxSteps() != null && metadata.getMaxSteps() > 0) {
            builder.maxSteps(metadata.getMaxSteps());
        } else if (metadata.getMaxTurns() != null && metadata.getMaxTurns() > 0) {
            builder.maxSteps(metadata.getMaxTurns());
        } else {
            builder.maxSteps(engine.getProps().getMaxSteps());
        }

        if (metadata.getAutoRethink() != null) {
            builder.autoRethink(metadata.getAutoRethink());
        } else {
            builder.autoRethink(engine.getProps().isAutoRethink());
        }

        if (metadata.getSessionWindowSize() != null) {
            builder.sessionWindowSize(metadata.getSessionWindowSize());
        } else {
            builder.sessionWindowSize(engine.getProps().getSessionWindowSize());
        }

        if (metadata.getSummaryWindowSize() == null && metadata.getSummaryWindowToken() == null) {
            builder.defaultInterceptorAdd(engine.getSummarizationInterceptor());
        } else {
            int summaryWindowSize = metadata.getSummaryWindowSize() == null ? engine.getProps().getSummaryWindowSize() : metadata.getSummaryWindowSize();
            int summaryWindowToken = metadata.getSummaryWindowToken() == null ? engine.getProps().getSummaryWindowToken() : metadata.getSummaryWindowToken();
            builder.defaultInterceptorAdd(engine.getSummarizationInterceptor().copyWith(summaryWindowSize, summaryWindowToken));
        }

        if (Assert.isNotEmpty(metadata.getTools())) {
            //目前参考了： https://opencode.ai/docs/zh-cn/permissions/
            TerminalSkillProxy terminalSkillWrap = new TerminalSkillProxy(engine.getCliSkills().getTerminalSkill());

            for (String toolName : metadata.getTools()) {
                if ("**".equals(toolName)) {
                    for (String t1 : TOOL_ALL_FULL) {
                        toolAddDo(engine, builder, terminalSkillWrap, metadata, t1);
                    }
                } else if ("*".equals(toolName)) {
                    for (String t1 : TOOL_ALL_PUBLIC) {
                        toolAddDo(engine, builder, terminalSkillWrap, metadata, t1);
                    }
                } else if ("pi".equals(toolName)) {
                    for (String t1 : TOOL_PI) {
                        toolAddDo(engine, builder, terminalSkillWrap, metadata, t1);
                    }
                } else {
                    toolAddDo(engine, builder, terminalSkillWrap, metadata, toolName);
                }
            }

            if (terminalSkillWrap.isEmpty() == false) {
                // terminalSkill / tools 需要通过以 skill 形态加载（getInstruction 里有 SOP）
                builder.defaultSkillAdd(terminalSkillWrap);
            }
        }

        for (HarnessExtension extension : engine.getProps().getExtensions()) {
            extension.configure(agentDefinition.getName(), builder);
        }

        return builder;
    }

    private static void toolAddDo(HarnessEngine engine, ReActAgent.Builder builder, TerminalSkillProxy terminalSkillWrap, AgentDefinition.Metadata metadata, String toolName) {
        //当前禁止
        if (metadata.getDisallowedTools().contains(toolName)) {
            return;
        }

        //全局禁止
        if (engine.getProps().getDisallowedTools().contains(toolName)) {
            return;
        }

        switch (toolName) {
            case "read": {
                terminalSkillWrap.addTools("read");
                break;
            }
            case "write": {
                terminalSkillWrap.addTools("write");
                break;
            }
            case "edit": {
                terminalSkillWrap.addTools("edit");

                toolAddDo(engine, builder, terminalSkillWrap, metadata, "read");
                toolAddDo(engine, builder, terminalSkillWrap, metadata, "write");
                break;
            }
            case "glob": {
                terminalSkillWrap.addTools("glob");
                break;
            }
            case "grep": {
                terminalSkillWrap.addTools("grep");
                break;
            }
            case "ls":
            case "list": {
                terminalSkillWrap.addTools("ls");
                break;
            }
            case "bash": {
                terminalSkillWrap.addTools("bash");
                break;
            }
            case "bash_start": {
                terminalSkillWrap.addTools("bash_start");
                break;
            }
            case "bash_wait": {
                terminalSkillWrap.addTools("bash_wait");
                break;
            }
            case "bash_stdin": {
                terminalSkillWrap.addTools("bash_stdin");
                break;
            }
            case "bash_stop": {
                terminalSkillWrap.addTools("bash_stop");
                break;
            }
            case "subagent":
            case "task": {
                builder.defaultSkillAdd(engine.getTaskSkill());
                break;
            }
            case "todoread":
            case "todowrite":
            case "todo": {
                todoToolAddDo(metadata, builder, engine);
                break;
            }
            case "webfetch": {
                builder.defaultToolAdd(engine.getWebfetchTool());
                break;
            }
            case "websearch": {
                builder.defaultToolAdd(engine.getWebsearchTool());
                break;
            }
            case "codesearch": {
                builder.defaultToolAdd(engine.getCodeSearchTool());
                break;
            }
            case "skill": {
                builder.defaultSkillAdd(engine.getCliSkills().getExpertSkill());
                break;
            }

            //-------


            case "generate": {
                if (engine.getProps().isSubagentEnabled()) {
                    builder.defaultToolAdd(engine.getGenerateTool());
                }
                break;
            }
            case "code": {
                builder.defaultSkillAdd(engine.getCodeSkill());
                break;
            }
            case "mcp": {
                if (engine.getMcpGatewaySkill() != null) {
                    builder.defaultSkillAdd(engine.getMcpGatewaySkill());
                }
                break;
            }
            case "restapi": {
                if (engine.getRestApiSkill() != null) {
                    builder.defaultSkillAdd(engine.getRestApiSkill());
                }
                break;
            }
            case "lsp": {
                if (engine.getLspSkill() != null) {
                    builder.defaultSkillAdd(engine.getLspSkill());
                }
                break;
            }
            case "hitl": {
                if (engine.getProps().isHitlEnabled()) {
                    builder.defaultInterceptorAdd(engine.getHitlInterceptor());
                }
                break;
            }
        }
    }

    private static void todoToolAddDo(AgentDefinition.Metadata metadata, ReActAgent.Builder builder, HarnessEngine agentRuntime) {
        if (metadata.isPrimary()) {
            //主代理，用文件模式
            builder.defaultSkillAdd(agentRuntime.getTodoSkill());
        } else {
            //次代理，用内存模式
            builder.planningMode(true);
        }
    }
}
