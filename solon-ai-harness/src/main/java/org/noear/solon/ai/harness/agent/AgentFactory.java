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
import org.noear.solon.ai.talents.cli.TerminalTalentProxy;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;

/**
 * 代理工厂
 *
 * @author noear 2026/3/20 created
 */
public class AgentFactory {
    //**
    private static String[] TOOL_ALL_FULL = {"read", "write", "edit", "glob", "grep", "ls", "bash", "bash_start", "bash_wait", "bash_stdin", "bash_stop", "skill", "todo", "code", "codesearch", "websearch", "webfetch", "task", "generate", "mcp", "openapi", "hitl", "lsp", "memory"};
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
        AgentDefinition.Metadata metadata = agentDefinition.getMetadata();

        ReActAgent.Builder builder = ReActAgent.of(chatModel);

        builder.name(agentDefinition.getName());
        builder.retryConfig(engine.getModelRetries(), 1000L);
        builder.maxTurns(engine.getMaxTurns());
        builder.autoRethink(engine.isAutoRethink());
        builder.sessionWindowSize(engine.getSessionWindowSize());
        builder.defaultInterceptorAdd(engine.getCompressionInterceptor());
        builder.defaultInterceptorAdd(9, engine.getStopLoopInterceptor());

        if (Assert.isNotEmpty(agentDefinition.getSystemPrompt())) {
            builder.systemPrompt(r -> agentDefinition.getSystemPrompt());
        }

        if (Assert.isNotEmpty(engine.getWorkspace())) {
            builder.defaultToolContextPut(HarnessEngine.ATTR_CWD, engine.getWorkspace());
        }

        if (Assert.isNotEmpty(metadata.getTools())) {
            //目前参考了： https://opencode.ai/docs/zh-cn/permissions/
            TerminalTalentProxy terminalTalentProxy = new TerminalTalentProxy(engine.getTerminalTalent());

            for (String toolName : metadata.getTools()) {
                if ("**".equals(toolName)) {
                    for (String t1 : TOOL_ALL_FULL) {
                        toolAddDo(engine, builder, terminalTalentProxy, metadata, t1);
                    }
                } else if ("*".equals(toolName)) {
                    for (String t1 : TOOL_ALL_PUBLIC) {
                        toolAddDo(engine, builder, terminalTalentProxy, metadata, t1);
                    }
                } else if ("pi".equals(toolName)) {
                    for (String t1 : TOOL_PI) {
                        toolAddDo(engine, builder, terminalTalentProxy, metadata, t1);
                    }
                } else {
                    toolAddDo(engine, builder, terminalTalentProxy, metadata, toolName);
                }
            }

            if (terminalTalentProxy.isEmpty() == false) {
                builder.defaultTalentAdd(terminalTalentProxy);
            }

            builder.defaultTalentAdd(engine.getClockTalent());
        }

        for (HarnessExtension extension : engine.getExtensions()) {
            extension.configure(agentDefinition.getName(), builder);
        }

        builder.modelOptions(o -> {
            if (engine.getCacheControl() != null) {
                o.cacheControl(engine.getCacheControl());
            }
        });

        return builder;
    }

    private static void toolAddDo(HarnessEngine engine, ReActAgent.Builder builder, TerminalTalentProxy terminalTalentProxy, AgentDefinition.Metadata metadata, String toolName) {
        //当前禁止
        if (metadata.getDisallowedTools().contains(toolName)) {
            return;
        }

        //全局禁止
        if (engine.getDisallowedTools().contains(toolName)) {
            return;
        }

        switch (toolName) {
            case "read": {
                terminalTalentProxy.addTools("read");
                break;
            }
            case "write": {
                terminalTalentProxy.addTools("write");
                break;
            }
            case "edit": {
                terminalTalentProxy.addTools("edit");

                toolAddDo(engine, builder, terminalTalentProxy, metadata, "read");
                toolAddDo(engine, builder, terminalTalentProxy, metadata, "write");
                break;
            }
            case "glob": {
                terminalTalentProxy.addTools("glob");
                break;
            }
            case "grep": {
                terminalTalentProxy.addTools("grep");
                break;
            }
            case "ls":
            case "list": {
                terminalTalentProxy.addTools("ls");
                break;
            }
            case "bash": {
                terminalTalentProxy.addTools("bash");
                break;
            }
            case "bash_start": {
                terminalTalentProxy.addTools("bash_start");
                break;
            }
            case "bash_wait": {
                terminalTalentProxy.addTools("bash_wait");
                break;
            }
            case "bash_stdin": {
                terminalTalentProxy.addTools("bash_stdin");
                break;
            }
            case "bash_stop": {
                terminalTalentProxy.addTools("bash_stop");
                break;
            }
            case "todoread":
            case "todowrite":
            case "todo": {
                todoToolAddDo(metadata, builder, engine);
                break;
            }
            case "webfetch": {
                builder.defaultTalentAdd(engine.getWebfetchTalent());
                break;
            }
            case "websearch": {
                builder.defaultTalentAdd(engine.getWebsearchTalent());
                break;
            }
            case "codesearch": {
                builder.defaultTalentAdd(engine.getCodeSearchTalent());
                break;
            }
            case "skill": {
                builder.defaultTalentAdd(engine.getSkillTalent());
                break;
            }
            case "subagent":
            case "task": {
                engine.getTaskTalent().setEnabled(engine.isSubagentEnabled());

                builder.defaultTalentAdd(engine.getTaskTalent());
                break;
            }
            case "generate": {
                engine.getGenerateTalent().setEnabled(engine.isSubagentEnabled());

                builder.defaultTalentAdd(engine.getGenerateTalent());
                break;
            }

            //-------


            case "memory": {
                builder.defaultTalentAdd(engine.getMemoryTalent());
                break;
            }
            case "code": {
                builder.defaultTalentAdd(engine.getCodeTalent());
                break;
            }
            case "mcp": {
                builder.defaultTalentAdd(engine.getMcpGatewayTalent());
                break;
            }
            case "openapi": {
                builder.defaultTalentAdd(engine.getOpenApiGatewayTalent());
                break;
            }
            case "lsp": {
                builder.defaultTalentAdd(engine.getLspTalent());
                break;
            }
            case "hitl": {
                engine.getHitlInterceptor().setEnabled(engine.isHitlEnabled());

                builder.defaultInterceptorAdd(engine.getHitlInterceptor());
                break;
            }
        }
    }

    private static void todoToolAddDo(AgentDefinition.Metadata metadata, ReActAgent.Builder builder, HarnessEngine engine) {
        if (metadata.isPrimary()) {
            //主代理，用文件模式
            builder.defaultTalentAdd(engine.getTodoTalent());
        } else {
            //次代理，用内存模式
            builder.planningMode(true);
        }
    }
}