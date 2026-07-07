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
package org.noear.solon.ai.harness.hitl;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLStrategy;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionDecision;
import org.noear.solon.ai.harness.permission.PermissionEngine;
import org.noear.solon.ai.harness.permission.PermissionRule;

import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bash 工具干预策略
 *
 * <p>专注于对 bash 等高危指令进行安全审计。
 * 内置规则通过 {@link PermissionRule} 表达（含自定义匹配器），包括：空命令放行、
 * 注入检测、系统特权防御、路径回溯防御、敏感文件防御、以及只读命令自动分类。
 * 内置规则优先于用户配置规则执行。规则未命中时委托 {@link PermissionEngine} 按规则决策。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * BashToolStrategy strategy = new BashToolStrategy()
 *     .permissionContextSupplier(options::getPermissionContext);
 * }</pre>
 *
 * @author noear
 * @since 3.9.1
 */
public class BashToolStrategy implements HITLStrategy {

    private static final int INTERNAL_PRIORITY = 100; // 内置规则优先级（高于用户配置规则）

    private final PermissionEngine permissionEngine = new PermissionEngine();
    private final BashCommandClassifier commandClassifier = new BashCommandClassifier();
    private final List<PermissionRule> internalRules = new ArrayList<>();
    private boolean internalRulesEnabled = true;
    private Supplier<PermissionContext> permissionContextSupplier = () -> PermissionContext.create();
    private PermissionDecision defaultDecision = PermissionDecision.ALLOW;

    public BashToolStrategy() {
        // 规则1：空命令放行
        internalRules.add(PermissionRule.of("bash", PermissionBehavior.ALLOW,
                (toolName, args) -> Assert.isEmpty((String) args.get("command")),
                null, INTERNAL_PRIORITY));

        // 规则2：注入与子 Shell 防御
        internalRules.add(PermissionRule.of("bash", PermissionBehavior.DENY,
                (toolName, args) -> {
                    String cmd = ((String) args.get("command")).trim();
                    return cmd.contains("`") || cmd.contains("$(") || cmd.contains("/dev/");
                },
                "检测到潜在的命令注入或设备重定向风险。", INTERNAL_PRIORITY));

        // 规则3：系统级安全（绝对黑名单）
        internalRules.add(PermissionRule.of("bash", PermissionBehavior.DENY,
                (toolName, args) -> {
                    String cmd = ((String) args.get("command")).trim();
                    return cmd.matches(".*\\b(sudo|su|chown|chmod|chgrp|passwd|visudo)\\b.*");
                },
                null, INTERNAL_PRIORITY)); // prompt=null，evaluate 中会生成包含命令的动态消息

        // 规则4：路径边界检查
        internalRules.add(PermissionRule.of("bash", PermissionBehavior.DENY,
                (toolName, args) -> {
                    String cmd = ((String) args.get("command")).trim();
                    return cmd.contains("../") || cmd.contains("..\\");
                },
                "检测到路径回溯操作，禁止访问工作区外目录。", INTERNAL_PRIORITY));

        // 规则5：敏感系统文件
        internalRules.add(PermissionRule.of("bash", PermissionBehavior.DENY,
                (toolName, args) -> {
                    String cmd = ((String) args.get("command")).trim();
                    return cmd.contains("/etc/") || cmd.contains("/var/") || cmd.contains("/root/")
                            || cmd.contains("~/.ssh/") || cmd.contains("~/.bashrc") || cmd.contains("~/.zshrc");
                },
                "禁止访问系统敏感配置文件。", INTERNAL_PRIORITY));

        // 规则6a：只读命令自动放行
        internalRules.add(PermissionRule.of("bash", PermissionBehavior.ALLOW,
                (toolName, args) -> {
                    String cmd = (String) args.get("command");
                    return cmd != null && commandClassifier.isSearchOrReadCommand(cmd.trim());
                },
                null, INTERNAL_PRIORITY));

        // 规则6b：不完整命令拒绝
        internalRules.add(PermissionRule.of("bash", PermissionBehavior.DENY,
                (toolName, args) -> {
                    String cmd = (String) args.get("command");
                    return cmd != null && commandClassifier.isIncompleteCommand(cmd.trim());
                },
                "检测到不完整的命令或空命令。", INTERNAL_PRIORITY));
    }

    /**
     * 设置权限上下文提供者
     */
    public BashToolStrategy permissionContextSupplier(Supplier<PermissionContext> supplier) {
        if (supplier != null) {
            this.permissionContextSupplier = supplier;
        }
        return this;
    }

    /**
     * 启用/禁用内置安全规则。默认启用。
     * 禁用后仅保留用户配置的规则（通过 alwaysAllow/alwaysBlock 注册）。
     */
    public BashToolStrategy internalRulesEnabled(boolean enabled) {
        this.internalRulesEnabled = enabled;
        return this;
    }

    /**
     * 设置无规则匹配时的默认决策
     */
    public BashToolStrategy defaultDecision(PermissionDecision decision) {
        if (decision != null) {
            this.defaultDecision = decision;
        }
        return this;
    }

    /**
     * 解析权限上下文（全局上下文 + Agent 级 delta）
     */
    private PermissionContext resolveContext(ReActTrace trace) {
        PermissionContext global = permissionContextSupplier.get();

        if (trace != null) {
            PermissionContext agentCtx = trace.getOptions().getAttrAs(AgentDefinition.ATTR_PERMISSION_CONTEXT);
            if (agentCtx != null && !agentCtx.rules().isEmpty()) {
                global = global.addRules(agentCtx.rules());
            }
        }

        return global;
    }

    @Override
    public String evaluate(ReActTrace trace, Map<String, Object> args) {
        PermissionContext ctx = resolveContext(trace);

        // 阶段1：内置规则优先匹配（仅在启用时生效）
        if (internalRulesEnabled) {
            for (PermissionRule rule : internalRules) {
                if (rule.hasMatcher() && rule.matcher().get().test("bash", args)) {
                    return toResult(rule, args);
                }
            }
        }

        // 阶段2：用户配置规则 + 内置规则合并，委托引擎评估
        PermissionContext merged = ctx.addRules(internalRules);
        PermissionDecision decision = permissionEngine.evaluate("bash", args, merged, this.defaultDecision);

        return toDefaultResult(decision);
    }

    /**
     * 将内置规则的匹配结果转换为策略返回值
     */
    private String toResult(PermissionRule rule, Map<String, Object> args) {
        switch (rule.behavior()) {
            case ALLOW:
                return null;
            case DENY:
                String cmd = (String) args.get("command");
                if (rule.prompt() != null) {
                    return rule.prompt();
                }
                // 无 prompt 时生成动态消息（如系统特权指令包含命令内容）
                return "检测到系统特权指令 [" + (cmd != null ? cmd.trim() : "") + "]。";
            case ASK:
                return "高危操作，需要人工介入确认。";
            default:
                return null;
        }
    }

    /**
     * 将引擎决策结果转换为策略返回值（使用通用提示）
     */
    private String toDefaultResult(PermissionDecision decision) {
        switch (decision) {
            case ALLOW:
                return null;
            case DENY:
                return "操作被权限策略拒绝。";
            case ASK:
                return "高危操作，需要人工介入确认。";
            default:
                return null;
        }
    }
}
