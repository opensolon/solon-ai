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
package org.noear.solon.ai.harness.scope;

import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.lang.Nullable;

import java.util.Collection;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 作用域守卫（每个代理常驻，优先级最高）
 *
 * <p>无作用域时：未要求作用域就什么都不做；要求作用域（{@code scopeRequired}）则禁用时钟以外的全部工具
 * （绕过受理的请求不得成为无限制请求）。有作用域时，在工具执行前对越界调用预填结果，
 * {@code ActionTask} 见到预填结果就不会真正执行；HITL 拦截器也会跳过已预填的调用，
 * 不会为一个本就禁止的工具弹审批卡。</p>
 *
 * <p>MCP 的判断分两路：直连工具（FULL 模式下模型直接看到 MCP 工具名）按归属服务判断；
 * 中转工具 {@code call_tool} / {@code get_tool_detail} 按参数 {@code tool_name} 的归属服务判断。
 * 已挂扩展声明的工具（{@code HarnessExtension#getToolNames}）跟随扩展放行；其它工具（含宿主自注册的）
 * 一律按名单判断。</p>
 *
 * <p>内部组件：由 {@code AgentFactory} 给每个代理装配，宿主不要直接使用。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class ScopeGuardInterceptor extends AbsReActInterceptor {
    /** 优先级：越小越先执行，必须早于 HITL（缺省 0） */
    public static final int INDEX = -1000;

    static final String DENIED_TEXT = "Error: tool '%s' is not available in this context.";

    /** MCP 工具名 → 归属服务名（非 MCP 工具返回 null） */
    private final Function<String, String> mcpServerOfTool;
    /** 是否为已挂扩展声明的工具 */
    private final Predicate<String> extensionTool;
    /** 是否要求作用域（缺失即全拒） */
    private final BooleanSupplier scopeRequired;

    public ScopeGuardInterceptor(Function<String, String> mcpServerOfTool, Predicate<String> extensionTool,
                                 BooleanSupplier scopeRequired) {
        this.mcpServerOfTool = mcpServerOfTool;
        this.extensionTool = extensionTool;
        this.scopeRequired = scopeRequired;
    }

    @Override
    public void onActionStart(ReActTrace trace, Collection<ToolExchanger> toolCalls) {
        if (toolCalls == null) {
            return;
        }

        InputScope scope = InputScope.of(trace);
        for (ToolExchanger ex : toolCalls) {
            guard(scope, ex);
        }
    }

    @Override
    public void onToolCallStart(ReActTrace trace, ToolExchanger toolExchanger) {
        // 文本模式等不经过批 Start 的路径兜底
        guard(InputScope.of(trace), toolExchanger);
    }

    private void guard(@Nullable InputScope scope, ToolExchanger ex) {
        if (ex.getToolResult() != null) {
            return;
        }

        final String reason;
        if (scope == null) {
            if (scopeRequired.getAsBoolean() == false) {
                return;
            }
            String name = ex.getToolName();
            reason = name != null && ToolName.isAlwaysAllowed(name) ? null : String.valueOf(name);
        } else {
            reason = denyReason(scope, ex.getToolName(), ex.getArgs() == null ? null : ex.getArgs().get("tool_name"));
        }
        if (reason != null) {
            ex.setToolResult(ToolResult.error(String.format(DENIED_TEXT, reason)));
        }
    }

    /**
     * @return 拒绝时返回展示用名称；允许时返回 null
     */
    @Nullable
    String denyReason(InputScope scope, String toolName, @Nullable Object targetToolName) {
        if (toolName == null) {
            return "unknown";
        }

        // 1. MCP 中转工具：按目标工具的归属服务判断
        if ("call_tool".equals(toolName) || "get_tool_detail".equals(toolName)) {
            if (!scope.allowsToolName(toolName)) {
                return toolName;
            }

            if (scope.getMcps() != null && targetToolName != null) {
                String server = mcpServerOfTool.apply(String.valueOf(targetToolName));
                if (!scope.allowsMcp(server)) {
                    return String.valueOf(targetToolName);
                }
            }
            return null;
        }

        // 2. MCP 直连工具（内置工具名优先，避免同名 MCP 工具改变内置工具的判定）：
        //    名单含 mcp（或不限工具）且归属服务在边界内
        if (!ToolName.isBuiltinRuntime(toolName)) {
            String server = mcpServerOfTool.apply(toolName);
            if (server != null) {
                boolean mcpAllowed = scope.getTools() == null || scope.getTools().contains(ToolName.TOOL_MCP.getName());
                return mcpAllowed && scope.allowsMcp(server) ? null : toolName;
            }

            // 3. 已挂扩展声明的工具（如 goal_update）：不受名单约束，跟随扩展是否挂载
            if (extensionTool.test(toolName)) {
                return null;
            }
        }

        // 4. 内置工具与其它未声明的工具（如宿主自注册）：必须在名单内
        return scope.allowsToolName(toolName) ? null : toolName;
    }
}
