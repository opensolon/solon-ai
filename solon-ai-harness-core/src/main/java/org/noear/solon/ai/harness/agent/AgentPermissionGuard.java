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

import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionEngine;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.harness.scope.ScopeGuardInterceptor;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.lang.Nullable;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * 代理权限守卫：子代理执行工具前按代理定义里的 {@code permissionRules} 判定
 *
 * <p>子代理不挂 HITL（同步 {@code task} 在一次工具调用里跑完，没有机会弹审批卡），所以代理规则对子代理
 * 只能在这里生效：DENY 拒绝；ASK 也拒绝（没人能确认，与 opencode 无人值守时 ask 即 deny 一致）；
 * 没有规则命中时放行（与不写规则时的行为一致）。只用代理自己的规则，不用引擎全局规则
 * （全局规则是给主代理审批用的，其中的 ask 拿到子代理上会把所有调用都拒掉）。</p>
 *
 * <p>shell 按段判定，见 {@link PermissionEngine}；{@code bash_start} 按 {@code bash} 的规则判定；
 * 有 shell 规则时 {@code bash_wait} 不得写入输入（运行中的程序收到什么无从核实，如分页器里的 {@code !cmd}）。
 * 拒绝时预填工具结果，{@code ActionTask} 见到预填结果就不会真正执行。</p>
 *
 * <p>内部组件：由 {@code AgentFactory} 给声明了规则的子代理装配。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class AgentPermissionGuard extends AbsReActInterceptor {
    /** 紧随作用域守卫：越界的工具先由作用域守卫拒绝 */
    static final int INDEX = ScopeGuardInterceptor.INDEX + 100;

    private final PermissionEngine permissionEngine = new PermissionEngine();
    private final PermissionContext context;
    private final boolean hasShellRules;

    AgentPermissionGuard(PermissionContext context) {
        this.context = context;
        boolean shell = false;
        for (PermissionRule rule : context.rules()) {
            String name = rule.toolName();
            if ("*".equals(name) || TerminalTalent.TOOL_BASH.equalsIgnoreCase(name)
                    || TerminalTalent.TOOL_BASH_START.equalsIgnoreCase(name)) {
                shell = true;
                break;
            }
        }
        this.hasShellRules = shell;
    }

    @Override
    public void onActionStart(ReActTrace trace, Collection<ToolExchanger> toolCalls) {
        if (toolCalls == null) {
            return;
        }

        Locale locale = HarnessI18n.localeOf(trace);
        for (ToolExchanger ex : toolCalls) {
            guard(locale, ex);
        }
    }

    @Override
    public void onToolCallStart(ReActTrace trace, ToolExchanger toolExchanger) {
        // 文本模式等不经过批 Start 的路径兜底
        guard(HarnessI18n.localeOf(trace), toolExchanger);
    }

    private void guard(Locale locale, ToolExchanger ex) {
        if (ex.getToolResult() != null) {
            return;
        }

        String reason = denyReason(ex.getToolName(), ex.getArgs(), locale);
        if (reason != null) {
            ex.setToolResult(ToolResult.error(reason));
        }
    }

    /**
     * @return 拒绝时返回给模型的说明；放行时返回 null
     */
    @Nullable
    String denyReason(@Nullable String toolName, @Nullable Map<String, Object> args, @Nullable Locale locale) {
        if (toolName == null) {
            return null;
        }

        if (TerminalTalent.TOOL_BASH_WAIT.equals(toolName) && hasShellRules && args != null) {
            Object chars = args.get(TerminalTalent.PARAM_CHARS);
            if (chars instanceof String && ((String) chars).isEmpty() == false) {
                return HarnessI18n.get(locale, HarnessI18nKeys.PERMISSION_AGENT_WAIT_INPUT);
            }
        }

        String ruleName = TerminalTalent.TOOL_BASH_START.equals(toolName) ? TerminalTalent.TOOL_BASH : toolName;
        PermissionBehavior behavior = permissionEngine.evaluate(ruleName, args, context, PermissionBehavior.ALLOW);
        switch (behavior) {
            case DENY:
                return HarnessI18n.fmt(locale, HarnessI18nKeys.PERMISSION_AGENT_DENY,
                        "tool", toolName, "target", targetOf(args));
            case ASK:
                return HarnessI18n.fmt(locale, HarnessI18nKeys.PERMISSION_AGENT_ASK,
                        "tool", toolName, "target", targetOf(args));
            default:
                return null;
        }
    }

    /** 展示给模型的调用对象：命令或路径，取不到时留空 */
    private static String targetOf(@Nullable Map<String, Object> args) {
        if (args == null) {
            return "";
        }

        for (String key : new String[]{"command", "file_path", "path", "url", "pattern"}) {
            Object v = args.get(key);
            if (v instanceof String && ((String) v).isEmpty() == false) {
                return (String) v;
            }
        }
        return "";
    }
}
