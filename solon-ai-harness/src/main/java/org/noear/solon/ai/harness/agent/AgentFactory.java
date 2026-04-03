package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillMetadata;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.skills.cli.TerminalSkill;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;
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
    /**
     * 根据定义生成代理
     */
    public static ReActAgent.Builder create(HarnessEngine agentRuntime, AgentDefinition agentDefinition) {
        ReActAgent.Builder builder = ReActAgent.of(agentRuntime.getChatModel());

        AgentDefinition.Metadata metadata = agentDefinition.getMetadata();

        builder.name(agentDefinition.getName());

        if (Assert.isNotEmpty(agentRuntime.getProps().getWorkspace())) {
            builder.defaultToolContextPut(HarnessEngine.ATTR_CWD, agentRuntime.getProps().getWorkspace());
        }

        if (Assert.isNotEmpty(agentDefinition.getSystemPrompt())) {
            builder.systemPrompt(r -> agentDefinition.getSystemPrompt());
        }

        builder.defaultInterceptorAdd(agentRuntime.getSummarizationInterceptor());

        if (metadata.getMaxSteps() != null && metadata.getMaxSteps() > 0) {
            builder.maxSteps(metadata.getMaxSteps());
        } else if (metadata.hasMaxTurns()) {
            builder.maxSteps(metadata.getMaxTurns());
        } else {
            builder.maxSteps(30);
        }

        if (metadata.getMaxStepsAutoExtensible() != null) {
            builder.maxStepsExtensible(metadata.getMaxStepsAutoExtensible());
        } else {
            builder.maxStepsExtensible(true);
        }

        if (metadata.getSessionWindowSize() != null) {
            builder.sessionWindowSize(metadata.getSessionWindowSize());
        } else {
            builder.sessionWindowSize(8);
        }

        if (Assert.isNotEmpty(metadata.getTools())) {
            //目前参考了： https://opencode.ai/docs/zh-cn/permissions/
            TerminalSkillProxy terminalSkillWrap = new TerminalSkillProxy(agentRuntime.getCliSkills().getTerminalSkill());

            for (String toolName : metadata.getTools()) {
                switch (toolName) {
                    case "read": {
                        terminalSkillWrap.addTools("read");
                        break;
                    }
                    case "edit": {
                        terminalSkillWrap.addTools("read", "write", "edit", "undo");
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
                        builder.defaultSkillAdd(agentRuntime.getTaskSkill());
                        break;
                    }
                    case "skill": {
                        builder.defaultSkillAdd(agentRuntime.getCliSkills().getExpertSkill());
                        break;
                    }
                    case "todoread":
                    case "todowrite":
                    case "todo": {
                        todoAddDo(metadata, builder, agentRuntime);
                        break;
                    }
                    case "webfetch": {
                        builder.defaultToolAdd(WebfetchTool.getInstance());
                        break;
                    }
                    case "websearch": {
                        builder.defaultToolAdd(WebsearchTool.getInstance());
                        break;
                    }
                    case "codesearch": {
                        builder.defaultToolAdd(CodeSearchTool.getInstance());
                        break;
                    }
                    case "*": {
                        builder.defaultSkillAdd(agentRuntime.getCliSkills());
                        todoAddDo(metadata, builder, agentRuntime);
                        builder.defaultSkillAdd(agentRuntime.getCodeSkill());

                        builder.defaultToolAdd(WebfetchTool.getInstance());
                        builder.defaultToolAdd(WebsearchTool.getInstance());
                        builder.defaultToolAdd(CodeSearchTool.getInstance());

                        if (agentRuntime.getProps().isSubagentEnabled()) {
                            builder.defaultSkillAdd(agentRuntime.getTaskSkill());
                        }
                        break;
                    }

                    //-------


                    case "generate": {
                        if (agentRuntime.getProps().isSubagentEnabled()) {
                            builder.defaultToolAdd(agentRuntime.getGenerateTool());
                        }
                        break;
                    }
                    case "code": {
                        builder.defaultSkillAdd(agentRuntime.getCodeSkill());
                        break;
                    }
                    case "mcp": {
                        if (agentRuntime.getMcpGatewaySkill() != null) {
                            builder.defaultSkillAdd(agentRuntime.getMcpGatewaySkill());
                        }
                        break;
                    }
                    case "restapi": {
                        if (agentRuntime.getRestApiSkill() != null) {
                            builder.defaultSkillAdd(agentRuntime.getRestApiSkill());
                        }
                    }
                    case "hitl": {
                        if (agentRuntime.getProps().isHitlEnabled()) {
                            builder.defaultInterceptorAdd(agentRuntime.getHitlInterceptor());
                        }
                        break;
                    }

                    case "**": {
                        builder.defaultSkillAdd(agentRuntime.getCliSkills());
                        todoAddDo(metadata, builder, agentRuntime);
                        builder.defaultSkillAdd(agentRuntime.getCodeSkill());

                        builder.defaultToolAdd(WebfetchTool.getInstance());
                        builder.defaultToolAdd(WebsearchTool.getInstance());
                        builder.defaultToolAdd(CodeSearchTool.getInstance());

                        if (agentRuntime.getProps().isSubagentEnabled()) {
                            builder.defaultSkillAdd(agentRuntime.getTaskSkill());
                        }

                        //---

                        if (agentRuntime.getProps().isSubagentEnabled()) {
                            builder.defaultToolAdd(agentRuntime.getGenerateTool());
                        }

                        //mcp
                        if (agentRuntime.getMcpGatewaySkill() != null) {
                            builder.defaultSkillAdd(agentRuntime.getMcpGatewaySkill());
                        }

                        //rest-api
                        if (agentRuntime.getRestApiSkill() != null) {
                            builder.defaultSkillAdd(agentRuntime.getRestApiSkill());
                        }

                        //hitl
                        if (agentRuntime.getProps().isHitlEnabled()) {
                            builder.defaultInterceptorAdd(agentRuntime.getHitlInterceptor());
                        }
                        break;
                    }
                }
            }

            if (terminalSkillWrap.isEmpty() == false) {
                // terminalSkill / tools 需要通过以 skill 形态加载（getInstruction 里有 SOP）
                builder.defaultSkillAdd(terminalSkillWrap);
            }
        }

        return builder;
    }

    private static void todoAddDo(AgentDefinition.Metadata metadata, ReActAgent.Builder builder, HarnessEngine agentRuntime) {
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