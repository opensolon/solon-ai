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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 文件写入工具干预策略
 *
 * <p>适用于 write、edit 等文件写入工具。内置规则通过 {@link PermissionRule} 表达
 * （含自定义匹配器），包括路径回溯和敏感文件防御规则。
 * 内置规则优先于用户配置规则执行。规则未命中时委托 {@link PermissionEngine} 按规则决策。</p>
 *
 * @author noear
 * @since 4.0
 */
public class WriteToolStrategy implements HITLStrategy {

    private static final int INTERNAL_PRIORITY = 100;

    private final PermissionEngine permissionEngine = new PermissionEngine();
    private final String toolName;
    private final List<PermissionRule> internalRules = new ArrayList<>();
    private boolean internalRulesEnabled = true;
    private Supplier<PermissionContext> permissionContextSupplier = () -> PermissionContext.create();
    private PermissionDecision defaultDecision = PermissionDecision.ALLOW;

    /**
     * @param toolName 工具名称（如 "write", "edit"）
     */
    public WriteToolStrategy(String toolName) {
        this.toolName = toolName;

        // 规则1：路径回溯防御
        internalRules.add(PermissionRule.of(toolName, PermissionBehavior.DENY,
                (tn, args) -> {
                    String filePath = (String) args.get("file_path");
                    return filePath != null && (filePath.contains("../") || filePath.contains("..\\"));
                },
                "检测到路径回溯操作，禁止访问工作区外目录。", INTERNAL_PRIORITY));

        // 规则2：敏感系统文件
        internalRules.add(PermissionRule.of(toolName, PermissionBehavior.DENY,
                (tn, args) -> {
                    String filePath = (String) args.get("file_path");
                    return filePath != null && (filePath.contains("/etc/") || filePath.contains("/var/") || filePath.contains("/root/")
                            || filePath.contains("~/.ssh/") || filePath.contains("~/.bashrc") || filePath.contains("~/.zshrc"));
                },
                "禁止访问系统敏感配置文件。", INTERNAL_PRIORITY));
    }

    /**
     * 设置权限上下文提供者
     */
    public WriteToolStrategy permissionContextSupplier(Supplier<PermissionContext> supplier) {
        if (supplier != null) {
            this.permissionContextSupplier = supplier;
        }
        return this;
    }

    /**
     * 启用/禁用内置安全规则（路径回溯、敏感文件防御）。默认启用。
     * 禁用后仅保留用户配置的规则（通过 alwaysAllow/alwaysBlock 注册）。
     */
    public WriteToolStrategy internalRulesEnabled(boolean enabled) {
        this.internalRulesEnabled = enabled;
        return this;
    }

    /**
     * 设置无规则匹配时的默认决策
     */
    public WriteToolStrategy defaultDecision(PermissionDecision decision) {
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
                if (rule.hasMatcher() && rule.matcher().get().test(toolName, args)) {
                    return toResult(rule);
                }
            }
        }

        // 阶段2：用户配置规则 + 内置规则合并，委托引擎评估
        PermissionContext merged = ctx.addRules(internalRules);
        PermissionDecision decision = permissionEngine.evaluate(toolName, args, merged, this.defaultDecision);

        return toDefaultResult(decision);
    }

    /**
     * 将内置规则的匹配结果转换为策略返回值
     */
    private String toResult(PermissionRule rule) {
        switch (rule.behavior()) {
            case ALLOW:
                return null;
            case DENY:
                return rule.prompt() != null ? rule.prompt() : "文件写入操作被权限策略拒绝。";
            case ASK:
                return "文件写入操作，需要人工介入确认。";
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
                return "文件写入操作被权限策略拒绝。";
            case ASK:
                return "文件写入操作，需要人工介入确认。";
            default:
                return null;
        }
    }
}
