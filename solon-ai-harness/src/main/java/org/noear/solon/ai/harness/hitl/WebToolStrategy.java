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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.function.Supplier;

/**
 * Web 工具干预策略
 *
 * <p>适用于 webfetch、websearch 等网络访问工具。内置高风险域名黑名单防御规则，
 * 通过 {@link PermissionRule} 表达（含自定义匹配器）。
 * 内置规则优先于用户配置规则执行。规则未命中时委托 {@link PermissionEngine} 按规则决策。</p>
 *
 * @author noear
 * @since 4.0
 */
public class WebToolStrategy implements HITLStrategy {

    private static final int INTERNAL_PRIORITY = 100;

    /** 高风险域名黑名单 */
    private final Set<String> riskyHostnames = new HashSet<String>();

    private final PermissionEngine permissionEngine = new PermissionEngine();
    private final String toolName;
    private final List<PermissionRule> internalRules = new ArrayList<>();
    private boolean internalRulesEnabled = true;
    private Supplier<PermissionContext> permissionContextSupplier = () -> PermissionContext.create();
    private PermissionDecision defaultDecision = PermissionDecision.ALLOW;

    /**
     * @param toolName 工具名称（如 "webfetch", "websearch"）
     */
    public WebToolStrategy(String toolName) {
        this.toolName = toolName;

        // 规则1：高风险域名黑名单
        internalRules.add(PermissionRule.of(toolName, PermissionBehavior.DENY,
                (tn, args) -> {
                    String url = extractUrl(args);
                    if (url != null) {
                        String hostname = extractHostname(url);
                        if (hostname != null && isRiskyHostname(hostname)) {
                            return true;
                        }
                    }
                    return false;
                },
                null, INTERNAL_PRIORITY)); // prompt=null，由 toResult 动态生成含域名的消息
    }

    /**
     * 设置权限上下文提供者
     */
    public WebToolStrategy permissionContextSupplier(Supplier<PermissionContext> supplier) {
        if (supplier != null) {
            this.permissionContextSupplier = supplier;
        }
        return this;
    }

    public WebToolStrategy riskyHostname(String hostName){
        riskyHostnames.add(hostName);
        return this;
    }

    public WebToolStrategy riskyHostnames(Collection<String> hostNames){
        riskyHostnames.addAll(hostNames);
        return this;
    }

    /**
     * 启用/禁用内置安全规则（高风险域名黑名单）。默认启用。
     * 禁用后仅保留用户配置的规则（通过 alwaysAllow/alwaysBlock 注册）。
     */
    public WebToolStrategy internalRulesEnabled(boolean enabled) {
        this.internalRulesEnabled = enabled;
        return this;
    }

    /**
     * 设置无规则匹配时的默认决策
     */
    public WebToolStrategy defaultDecision(PermissionDecision decision) {
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
                    return toResult(rule, args);
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
    private String toResult(PermissionRule rule, Map<String, Object> args) {
        switch (rule.behavior()) {
            case ALLOW:
                return null;
            case DENY:
                if (rule.prompt() != null) {
                    return rule.prompt();
                }
                // 无 prompt 时生成动态消息（包含域名信息）
                String url = extractUrl(args);
                String hostname = (url != null) ? extractHostname(url) : "未知";
                return "检测到高风险域名 [" + hostname + "]，拒绝访问。";
            case ASK:
                return "网络访问操作，需要人工介入确认。";
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
                return "网络访问操作被权限策略拒绝。";
            case ASK:
                return "网络访问操作，需要人工介入确认。";
            default:
                return null;
        }
    }

    /**
     * 从工具参数中提取 URL
     */
    private String extractUrl(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        for (String field : new String[]{"url", "link", "address"}) {
            Object val = args.get(field);
            if (val instanceof String) {
                return (String) val;
            }
        }
        return null;
    }

    /**
     * 从 URL 中提取主机名
     */
    private String extractHostname(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host != null) {
                host = host.toLowerCase();
                // 去掉 www. 前缀
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
            }
            return host;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 判断是否为高风险域名
     */
    private boolean isRiskyHostname(String hostname) {
        for (String risky : riskyHostnames) {
            if (hostname.equals(risky) || hostname.endsWith("." + risky)) {
                return true;
            }
        }
        return false;
    }
}
