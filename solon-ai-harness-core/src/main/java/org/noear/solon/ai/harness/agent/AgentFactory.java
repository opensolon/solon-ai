/*
 * Copyright 2017-2026 noear.org and authors
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
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.scope.ScopeGuardInterceptor;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.cli.TerminalTalentProxy;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代理工厂
 *
 * @author noear 2026/3/20 created
 */
public class AgentFactory {
    private static final Logger LOG = LoggerFactory.getLogger(AgentFactory.class);

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

        ChatModel chatModel = engine.getModelOrDefInstance(selectedModel);
        AgentDefinition.Metadata metadata = agentDefinition.getMetadata();
        boolean isMain = AgentDefinition.AGENT_MAIN.equals(agentDefinition.getMetadata().getName());
        ReActAgent.Builder builder = ReActAgent.of(chatModel);

        builder.name(agentDefinition.getName());
        builder.retryConfig(engine.getSettings().getModelRetries(), 1000L);
        builder.maxTurns(engine.getSettings().getMaxTurns());
        builder.autoRethink(engine.getSettings().isAutoRethink());
        builder.sessionWindowSize(engine.getSettings().getSessionWindowSize());
        builder.defaultInterceptorAdd(engine.getCompressionInterceptor());
        builder.defaultInterceptorAdd(9, engine.getStopLoopInterceptor());
        // 作用域守卫：主/子代理都常驻且最先执行（早于 HITL）
        builder.defaultInterceptorAdd(ScopeGuardInterceptor.INDEX, new ScopeGuardInterceptor(t -> {
            McpGatewayTalent mcp = engine.getTalents().getMcpGateway();
            return mcp == null ? null : mcp.getServerOfTool(t);
        }, t -> engine.getExtensions().stream().anyMatch(e -> e.getToolNames().contains(t)), engine::isScopeRequired));

        if (Assert.isNotEmpty(agentDefinition.getSystemPrompt())) {
            builder.systemPrompt(r -> agentDefinition.getSystemPrompt());
        }

        if (Assert.isNotEmpty(engine.getWorkspace())) {
            builder.defaultToolContextPut(HarnessEngine.ATTR_CWD, engine.getWorkspace());
        }

        if (Assert.isNotEmpty(metadata.getTools())) {
            //目前参考了： https://opencode.ai/docs/zh-cn/permissions/
            TerminalTalentProxy terminalTalentProxy = new TerminalTalentProxy(engine.getTalents().getTerminal());

            for (String toolName : metadata.getTools()) {
                for (String t1 : ToolName.expand(toolName)) {
                    toolAddDo(engine, builder, terminalTalentProxy, metadata, t1, isMain);
                }
            }

            if (terminalTalentProxy.isEmpty() == false) {
                builder.defaultTalentAdd(terminalTalentProxy);
            }

            builder.defaultTalentAdd(engine.getTalents().getClock());
        }

        // 无条件回调：扩展自己在 setEnabled 里把开关传播给子组件（Talent/Interceptor/Command），
        // 因此「挂上但处于停用」比「不挂」更容易在运行期恢复（不挂就再也挂不回来了）。
        for (HarnessExtension extension : engine.getExtensions()) {
            extension.onAgentBuild(engine, agentDefinition.getName(), builder);
        }

        // 注入 Agent 级权限 delta（仅 agent 自身的规则，不含全局规则）
        // HITL 策略在 evaluate 时会通过 trace.getExtraAs 读取并合并
        if (metadata.hasPermissionRules()) {
            PermissionContext agentCtx = PermissionContext.create().addRules(metadata.getPermissionRules());
            builder.attr(PermissionContext.ATTR_KEY, agentCtx);
        }

        builder.modelOptions(o -> {
            if (engine.getSettings().getCacheControl() != null) {
                o.cacheControl(engine.getSettings().getCacheControl());
            }

            o.httpCustomizeAdd(http -> {
                if (Assert.isNotEmpty(engine.getSettings().getUserAgent())) {
                    http.userAgent(engine.getSettings().getUserAgent());
                }

                if (engine.getSettings().getHttpProxy() != null) {
                    http.proxy(engine.getSettings().getHttpProxy());
                }

                if (engine.getSettings().getHttpCustomize() != null) {
                    engine.getSettings().getHttpCustomize().accept(http);
                }
            });
        });

        return builder;
    }

    private static void toolAddDo(HarnessEngine engine, ReActAgent.Builder builder, TerminalTalentProxy terminalTalentProxy, AgentDefinition.Metadata metadata, String toolName, boolean isMain) {
        //当前禁止
        if (metadata.getDisallowedTools().contains(toolName)) {
            return;
        }

        //全局禁止
        if (engine.getDisallowedTools().contains(toolName)) {
            return;
        }

        switch (toolName) {
            case "edit": {
                ToolName.runtimeNamesOf(toolName).forEach(terminalTalentProxy::addTools);

                toolAddDo(engine, builder, terminalTalentProxy, metadata, "read", isMain);
                toolAddDo(engine, builder, terminalTalentProxy, metadata, "write", isMain);
                break;
            }
            case "read":
            case "write":
            case "glob":
            case "grep":
            case "ls":
            case "list":
            case "bash": {
                // 终端工具的运行时名与作用域守卫共用 ToolName 的同一份映射
                ToolName.runtimeNamesOf(toolName).forEach(terminalTalentProxy::addTools);
                break;
            }
            case "todoread":
            case "todowrite":
            case "todo": {
                todoToolAddDo(metadata, builder, engine);
                break;
            }
            case "webfetch": {
                builder.defaultTalentAdd(engine.getTalents().getWebfetch());
                break;
            }
            case "websearch": {
                builder.defaultTalentAdd(engine.getTalents().getWebsearch());
                break;
            }
            case "codesearch": {
                builder.defaultTalentAdd(engine.getTalents().getCodeSearch());
                break;
            }
            case "skill": {
                builder.defaultTalentAdd(engine.getTalents().getSkill());
                break;
            }
            case "subagent":
            case "task": {
                engine.getTalents().getTask().setEnabled(engine.getSettings().isSubagentEnabled());

                builder.defaultTalentAdd(engine.getTalents().getTask());
                break;
            }
            case "generate": {
                engine.getTalents().getGenerate().setEnabled(engine.getSettings().isSubagentEnabled());

                builder.defaultTalentAdd(engine.getTalents().getGenerate());
                break;
            }

            //-------


            case "memory": {
                builder.defaultTalentAdd(engine.getTalents().getMemory());
                break;
            }
            case "code": {
                builder.defaultTalentAdd(engine.getTalents().getCode());
                break;
            }
            case "mcp": {
                builder.defaultTalentAdd(engine.getTalents().getMcpGateway());
                break;
            }
            case "restapi": //兼容旧的代号
            case "openapi": {
                builder.defaultTalentAdd(engine.getTalents().getOpenApiGateway());
                break;
            }
            case "lsp": {
                builder.defaultTalentAdd(engine.getTalents().getLsp());
                break;
            }
            case "hitl": {
                //仅主代理支持
                if (isMain == false) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("子代理[{}]不挂载 hitl：HITL 仅限主代理", metadata.getName());
                    }
                    return;
                }

                engine.getHitlInterceptor().setEnabled(engine.getSettings().isHitlEnabled());

                builder.defaultInterceptorAdd(engine.getHitlInterceptor());
                break;
            }
        }
    }

    private static void todoToolAddDo(AgentDefinition.Metadata metadata, ReActAgent.Builder builder, HarnessEngine engine) {
        if (metadata.isPrimary()) {
            //主代理，用文件模式
            builder.defaultTalentAdd(engine.getTalents().getTodo());
        } else {
            //次代理，用内存模式
            builder.planningMode(true);
        }
    }
}