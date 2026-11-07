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
package org.noear.solon.ai.harness.hitl;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话级权限记忆（人工审批「总是允许」写在这里，只对本会话生效）
 *
 * <p>记在会话内存属性上（不进快照）：会话重新加载后需要重新审批，宁可多问一次，不跨会话、不跨用户放行。
 * 每个会话最多 {@link #MAX_RULES} 条，超出淘汰最早的一条。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class SessionPermissions {
    static final String ATTR_KEY = "harness.permission.remembered";
    public static final int MAX_RULES = 100;

    private SessionPermissions() {
    }

    /** 记住一条规则（去重；超出上限淘汰最早的） */
    @SuppressWarnings("unchecked")
    public static void remember(AgentSession session, PermissionRule rule) {
        if (session == null || rule == null) {
            return;
        }

        synchronized (session.attrs()) {
            List<PermissionRule> rules = (List<PermissionRule>) session.attrs().get(ATTR_KEY);
            List<PermissionRule> next = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
            if (next.contains(rule)) {
                return;
            }
            if (next.size() >= MAX_RULES) {
                next.remove(0);
            }
            next.add(rule);
            // 写时复制：读取方无需加锁
            session.attrs().put(ATTR_KEY, Collections.unmodifiableList(next));
        }
    }

    /** 本会话记住的规则（无则空列表） */
    @SuppressWarnings("unchecked")
    public static List<PermissionRule> of(@Nullable AgentSession session) {
        if (session == null) {
            return Collections.emptyList();
        }
        Object v = session.attrs().get(ATTR_KEY);
        return v instanceof List ? (List<PermissionRule>) v : Collections.emptyList();
    }

    /** 在给定上下文上叠加本轮会话记住的规则（供各工具策略合并） */
    public static PermissionContext merge(PermissionContext ctx, @Nullable ReActTrace trace) {
        if (trace == null) {
            return ctx;
        }
        List<PermissionRule> rules = of(trace.getSession());
        return rules.isEmpty() ? ctx : ctx.addRules(rules);
    }
}
