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
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionDecision;
import org.noear.solon.ai.harness.permission.PermissionEngine;
import org.noear.solon.ai.harness.permission.PermissionMode;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 文件写入工具干预策略
 *
 * <p>适用于 write、edit 等文件写入工具。与 {@link BashToolStrategy} 不同的是，
 * 本策略不含 P0 硬编码防御（那是 bash 专属），而是直接委托 {@link PermissionEngine}
 * 按规则 + 模式决策。</p>
 *
 * <p>无规则匹配时，按模式降级：
 * <ul>
 * <li>BYPASS — 放行所有操作</li>
 * <li>ACCEPT_EDITS — 放行写操作</li>
 * <li>READ_ONLY — 拒绝写操作</li>
 * <li>DEFAULT — 人工确认</li>
 * </ul>
 * </p>
 *
 * @author noear
 * @since 4.0
 */
public class WriteToolStrategy implements HITLInterceptor.InterventionStrategy {

    private final PermissionEngine permissionEngine = new PermissionEngine();
    private final String toolName;
    private Supplier<PermissionContext> permissionContextSupplier = () -> PermissionContext.create();

    /**
     * @param toolName 工具名称（如 "write", "edit"）
     */
    public WriteToolStrategy(String toolName) {
        this.toolName = toolName;
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
     * 解析权限上下文（全局上下文 + Agent 级 delta）
     */
    private PermissionContext resolveContext(ReActTrace trace) {
        PermissionContext global = permissionContextSupplier.get();

        if (trace != null) {
            PermissionContext agentCtx = trace.getOptions().getAttrAs(AgentDefinition.ATTR_PERMISSION_CONTEXT);
            if (agentCtx != null) {
                if (agentCtx.mode() != PermissionMode.DEFAULT) {
                    global = global.withMode(agentCtx.mode());
                }
                if (!agentCtx.rules().isEmpty()) {
                    global = global.addRules(agentCtx.rules());
                }
            }
        }

        return global;
    }

    @Override
    public String evaluate(ReActTrace trace, Map<String, Object> args) {
        // P0 硬编码防御：路径回溯和系统敏感目录（不依赖规则引擎，始终生效）
        String filePath = args != null ? (String) args.get("file_path") : null;
        if (filePath != null) {
            if (filePath.contains("../") || filePath.contains("..\\")) {
                return "检测到路径回溯操作，禁止访问工作区外目录。";
            }
            if (filePath.contains("/etc/") || filePath.contains("/var/") || filePath.contains("/root/")
                    || filePath.contains("~/.ssh/") || filePath.contains("~/.bashrc") || filePath.contains("~/.zshrc")) {
                return "禁止访问系统敏感配置文件。";
            }
        }

        PermissionContext ctx = resolveContext(trace);
        PermissionDecision decision = permissionEngine.evaluate(toolName, args, ctx);

        switch (decision) {
            case ALLOW:
                return null;  // 放行
            case DENY:
                return "文件写入操作被权限策略拒绝。";
            case ASK:
                return "文件写入操作，需要人工介入确认。";
            default:
                return null;
        }
    }
}
