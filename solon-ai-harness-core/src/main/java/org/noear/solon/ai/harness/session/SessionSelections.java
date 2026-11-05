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
package org.noear.solon.ai.harness.session;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.lang.Nullable;

/**
 * 会话级选择项（模型 / 子代理）的静态读开口。
 *
 * <p>{@link org.noear.solon.ai.harness.HarnessEngine} 的实例方法
 * {@code getSessionModel/setSessionModel/getSessionAgent/setSessionAgent} 是宿主的主入口；
 * 本类服务于<b>拿不到引擎实例</b>的场景（如宿主侧的静态 trace 工具）。</p>
 *
 * <p>实现只有一份：引擎实例方法与核心内部读写全部委托到本类，存储键是本类的私有实现
 * （写在会话上下文里随快照持久化，<b>键值不可变</b>），宿主无从也无须触达。</p>
 *
 * @author noear
 * @since 4.2.3
 */
public final class SessionSelections {
    /** 会话级模型选择的存储键（会话上下文，随快照持久化；键值不可变） */
    private static final String KEY_MODEL = HarnessAttrs.LEGACY_MODEL_SELECTED;
    /** 会话级子代理选择的存储键（会话上下文，随快照持久化；键值不可变） */
    private static final String KEY_AGENT = HarnessAttrs.LEGACY_AGENT_SELECTED;

    private SessionSelections() {
    }

    /** 会话级模型选择（未设置 / 空串 返回 {@code null}，调用方回退默认模型）。 */
    @Nullable
    public static String modelOf(@Nullable AgentSession session) {
        if (session == null) return null;
        Object value = session.getContext().get(KEY_MODEL);
        return (value == null || value.toString().trim().isEmpty()) ? null : value.toString();
    }

    /** 会话级子代理选择（未设置 / 空串 返回 {@code null}，表示主代理）。 */
    @Nullable
    public static String agentOf(@Nullable AgentSession session) {
        if (session == null) return null;
        Object value = session.getContext().get(KEY_AGENT);
        return (value == null || value.toString().trim().isEmpty()) ? null : value.toString();
    }

    /** 设置会话级模型选择；{@code null}/空 表示清除（回退默认模型）。 */
    public static void setModel(@Nullable AgentSession session, @Nullable String name) {
        put(session, KEY_MODEL, name);
    }

    /** 设置会话级子代理选择；{@code null}/空 表示清除（回退主代理）。 */
    public static void setAgent(@Nullable AgentSession session, @Nullable String name) {
        put(session, KEY_AGENT, name);
    }

    private static void put(@Nullable AgentSession session, String key, @Nullable String name) {
        if (session == null) return;
        if (name == null || name.trim().isEmpty()) {
            session.getContext().remove(key);
        } else {
            session.getContext().put(key, name);
        }
    }
}
