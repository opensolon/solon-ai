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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Web 工具干预策略
 *
 * <p>适用于 webfetch、websearch 等网络访问工具。提供域名级权限控制：
 * <ul>
 * <li>内置高风险域名黑名单（社交媒体等）— 直接拒绝</li>
 * <li>委托 {@link PermissionEngine} 按规则 + 模式决策</li>
 * <li>无规则匹配时，DEFAULT 模式返回 ASK</li>
 * </ul>
 * </p>
 *
 * @author noear
 * @since 4.0
 */
public class WebToolStrategy implements HITLInterceptor.InterventionStrategy {

    /** 高风险域名黑名单 */
    private static final Set<String> RISKY_HOSTNAMES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "facebook.com", "twitter.com", "x.com",
        "tiktok.com", "reddit.com", "linkedin.com"
    )));

    private final PermissionEngine permissionEngine = new PermissionEngine();
    private final String toolName;
    private Supplier<PermissionContext> permissionContextSupplier = () -> PermissionContext.create();

    /**
     * @param toolName 工具名称（如 "webfetch", "websearch"）
     */
    public WebToolStrategy(String toolName) {
        this.toolName = toolName;
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
        // 1. 域名级风险检查
        String url = extractUrl(args);
        if (url != null) {
            String hostname = extractHostname(url);
            if (hostname != null && isRiskyHostname(hostname)) {
                return "检测到高风险域名 [" + hostname + "]，拒绝访问。";
            }
        }

        // 2. 委托 PermissionEngine 按规则 + 模式决策
        PermissionContext ctx = resolveContext(trace);
        PermissionDecision decision = permissionEngine.evaluate(toolName, args, ctx);

        switch (decision) {
            case ALLOW:
                return null;  // 放行
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
        for (String risky : RISKY_HOSTNAMES) {
            if (hostname.equals(risky) || hostname.endsWith("." + risky)) {
                return true;
            }
        }
        return false;
    }
}
