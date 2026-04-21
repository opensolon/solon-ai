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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillMetadata;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.skills.cli.TerminalSkill;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 代理工厂
 *
 * @author noear 2026/3/20 created
 */
public class AgentFactory {
    //**
    private static String[] TOOL_ALL_FULL = {"read", "write", "edit", "glob", "grep", "ls", "bash", "skill", "todo", "code", "codesearch", "websearch", "webfetch", "task", "generate", "mcp", "restapi", "hitl", "lsp"};
    //*
    private static String[] TOOL_ALL_PUBLIC = {"read", "write", "edit", "glob", "grep", "ls", "bash", "skill", "todo", "code", "codesearch", "websearch", "webfetch", "task", "lsp"};
    //pi
    private static String[] TOOL_PI = {"read", "write", "edit", "bash"};


    /**
     * 根据定义生成代理
     */
    public static ReActAgent.Builder create(HarnessEngine engine, AgentDefinition agentDefinition) {
        ChatModel chatModel = engine.getModelOrMain(agentDefinition.getModel());

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

        if (metadata.getMaxStepsAutoExtensible() != null) {
            builder.maxStepsExtensible(metadata.getMaxStepsAutoExtensible());
        } else {
            builder.maxStepsExtensible(engine.getProps().isMaxStepsAutoExtensible());
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
        if(engine.getProps().getDisallowedTools().contains(toolName)){
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
                terminalSkillWrap.addTools("read", "write", "edit");
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

//                    case "pi": { // {"read", "write", "edit", "bash"}
//                        terminalSkillWrap.addTools("read", "write", "edit", "bash");
//                        break;
//                    }
//                    case "*": { // {"skill","cli","todo","code","codesearch","websearch","webfetch","task"}
//                        builder.defaultSkillAdd(engine.getCliSkills());
//                        todoAddDo(metadata, builder, engine);
//                        builder.defaultSkillAdd(engine.getCodeSkill());
//
//                        builder.defaultToolAdd(engine.getCodeSearchTool());
//                        builder.defaultToolAdd(engine.getWebsearchTool());
//                        builder.defaultToolAdd(engine.getWebfetchTool());
//
//                        if (engine.getProps().isSubagentEnabled()) {
//                            builder.defaultSkillAdd(engine.getTaskSkill());
//                        }
//                        break;
//                    }
//                    case "**": { //{"skill","cli","todo","code","codesearch","websearch","webfetch","task","generate","mcp","restapi","hitl"};
//                        builder.defaultSkillAdd(engine.getCliSkills());
//                        todoAddDo(metadata, builder, engine);
//                        builder.defaultSkillAdd(engine.getCodeSkill());
//
//                        builder.defaultToolAdd(engine.getCodeSearchTool());
//                        builder.defaultToolAdd(engine.getWebsearchTool());
//                        builder.defaultToolAdd(engine.getWebfetchTool());
//
//                        if (engine.getProps().isSubagentEnabled()) {
//                            builder.defaultSkillAdd(engine.getTaskSkill());
//                        }
//
//                        //---
//
//                        if (engine.getProps().isSubagentEnabled()) {
//                            builder.defaultToolAdd(engine.getGenerateTool());
//                        }
//
//                        //mcp
//                        if (engine.getMcpGatewaySkill() != null) {
//                            builder.defaultSkillAdd(engine.getMcpGatewaySkill());
//                        }
//
//                        //rest-api
//                        if (engine.getRestApiSkill() != null) {
//                            builder.defaultSkillAdd(engine.getRestApiSkill());
//                        }
//
//                        //hitl
//                        if (engine.getProps().isHitlEnabled()) {
//                            builder.defaultInterceptorAdd(engine.getHitlInterceptor());
//                        }
//                        break;
//                    }
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


    /**
     * TerminalSkill 代理
     *
     * @author noear 2026/3/20 created
     */
    static class TerminalSkillProxy implements Skill {
        private final TerminalSkill terminalSkill;
        private final List<FunctionTool> toolList = new ArrayList<>();

        public TerminalSkillProxy(TerminalSkill terminalSkill) {
            this.terminalSkill = terminalSkill;
        }

        public boolean isEmpty() {
            return toolList.isEmpty();
        }

        public void addTools(String... names) {
            toolList.addAll(terminalSkill.getToolAry(names));
        }

        @Override
        public String name() {
            return terminalSkill.name();
        }

        @Override
        public String description() {
            return terminalSkill.description();
        }

        @Override
        public SkillMetadata metadata() {
            return terminalSkill.metadata();
        }

        @Override
        public boolean isSupported(Prompt prompt) {
            return terminalSkill.isSupported(prompt);
        }

        @Override
        public void onAttach(Prompt prompt) {
            terminalSkill.onAttach(prompt);
        }

        @Override
        public String getInstruction(Prompt prompt) {
            return terminalSkill.getInstruction(prompt);
        }

        @Override
        public Collection<FunctionTool> getTools(Prompt prompt) {
            return toolList;
        }
    }
}