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
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionDecision;
import org.noear.solon.ai.harness.permission.PermissionEngine;
import org.noear.solon.core.util.Assert;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Bash 工具干预策略
 *
 * <p>专注于对 bash 等高危指令进行安全审计。
 * 核心逻辑委托给 {@link PermissionEngine} 处理，仅保留注入检测、系统特权和路径回溯作为
 * P0 硬编码防御（不依赖规则引擎，始终生效）。</p>
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
public class BashToolStrategy implements HITLInterceptor.InterventionStrategy {

    private final PermissionEngine permissionEngine = new PermissionEngine();
    private final BashCommandClassifier commandClassifier = new BashCommandClassifier();
    private Supplier<PermissionContext> permissionContextSupplier = () -> PermissionContext.create();

    /**
     * 设置权限上下文提供者
     */
    public BashToolStrategy permissionContextSupplier(Supplier<PermissionContext> supplier) {
        if (supplier != null) {
            this.permissionContextSupplier = supplier;
        }
        return this;
    }

    @Override
    public String evaluate(ReActTrace trace, Map<String, Object> args) {
        String cmd = (String) args.get("command");
        if (Assert.isEmpty(cmd)) {
            return null;
        }

        cmd = cmd.trim();

        // ========== P0 硬编码防御（不依赖规则引擎，始终生效） ==========

        // A. 注入与子 Shell 防御（最高优先级）
        if (cmd.contains("`") || cmd.contains("$(") || cmd.contains("/dev/")) {
            return "检测到潜在的命令注入或设备重定向风险。";
        }

        // B. 系统级安全（绝对黑名单）
        if (cmd.matches(".*\\b(sudo|su|chown|chmod|chgrp|passwd|visudo)\\b.*")) {
            return "检测到系统特权指令 [" + cmd + "]。";
        }

        // C. 路径边界检查（严格限制）
        if (cmd.contains("../") || cmd.contains("..\\")) {
            return "检测到路径回溯操作，禁止访问工作区外目录。";
        }
        if (cmd.contains("/etc/") || cmd.contains("/var/") || cmd.contains("/root/")
                || cmd.contains("~/.ssh/") || cmd.contains("~/.bashrc") || cmd.contains("~/.zshrc")) {
            return "禁止访问系统敏感配置文件。";
        }

        // ========== 内置只读命令分类（不触发人工确认） ==========

        PermissionDecision classifierDecision = commandClassifier.classify(cmd);
        if (classifierDecision == PermissionDecision.ALLOW) {
            return null;  // 只读命令，直接放行
        }
        if (classifierDecision == PermissionDecision.DENY) {
            return "检测到不完整的命令或空命令。";
        }

        // ========== 委托 PermissionEngine 按规则 + 模式决策 ==========

        PermissionContext ctx = permissionContextSupplier.get();
        PermissionDecision decision = permissionEngine.evaluate("bash", args, ctx);

        switch (decision) {
            case ALLOW:
                return null;  // 放行
            case DENY:
                return "操作被权限策略拒绝。";
            case ASK:
                return "高危操作，需要人工介入确认。";
            default:
                return null;
        }
    }
}
