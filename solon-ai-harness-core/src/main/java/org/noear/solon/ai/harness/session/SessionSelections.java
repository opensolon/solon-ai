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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 会话级选择项（模型 / 子代理 / 技能视野 / MCP 视野）的静态读开口。
 *
 * <p>{@link org.noear.solon.ai.harness.HarnessEngine} 的实例方法
 * {@code getSessionModel/setSessionModel/getSessionAgent/setSessionAgent} 与
 * {@code getSessionSkills/setSessionSkills/getSessionMcps/setSessionMcps} 是宿主的主入口；
 * 本类服务于<b>拿不到引擎实例</b>的场景（如宿主侧的静态 trace 工具）。</p>
 *
 * <p>实现只有一份：引擎实例方法与核心内部读写全部委托到本类，存储键是本类的私有实现
 * （写在会话上下文里随快照持久化，<b>键值不可变</b>），宿主无从也无须触达。</p>
 *
 * @author noear
 * @since 4.2.0
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

    // ========== 技能 / MCP 视野（与模型、子代理同级，供每轮请求回退）==========

    /**
     * 会话级技能视野（未设置 / 空 返回 {@code null}，表示不限制）。
     *
     * <p>读侧对存储形式宽容：早期写入的是逗号分隔字符串，快照重载后是字符串数组，两者都能读。</p>
     */
    @Nullable
    public static List<String> skillsOf(@Nullable AgentSession session) {
        return listOf(session, HarnessAttrs.SESSION_SKILLS);
    }

    /**
     * 会话级 MCP 视野（未设置 / 空 返回 {@code null}，表示不限制）。
     */
    @Nullable
    public static List<String> mcpsOf(@Nullable AgentSession session) {
        return listOf(session, HarnessAttrs.SESSION_MCPS);
    }

    /** 设置会话级技能视野；{@code null}/空 表示清除（回退目标智能体的声明，再回退不限制）。 */
    public static void setSkills(@Nullable AgentSession session, @Nullable Collection<String> names) {
        putList(session, HarnessAttrs.SESSION_SKILLS, names);
    }

    /** 设置会话级 MCP 视野；{@code null}/空 表示清除（回退目标智能体的声明，再回退不限制）。 */
    public static void setMcps(@Nullable AgentSession session, @Nullable Collection<String> names) {
        putList(session, HarnessAttrs.SESSION_MCPS, names);
    }

    @Nullable
    private static List<String> listOf(@Nullable AgentSession session, String key) {
        if (session == null) return null;

        Object value = session.getContext().get(key);
        if (value == null) return null;

        List<String> names = new ArrayList<>();
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                addName(names, item == null ? null : item.toString());
            }
        } else {
            // 兼容早期以逗号分隔字符串写入的存档
            for (String item : value.toString().split(",")) {
                addName(names, item);
            }
        }

        return names.isEmpty() ? null : Collections.unmodifiableList(names);
    }

    private static void addName(List<String> names, @Nullable String name) {
        if (name != null && name.trim().isEmpty() == false) {
            names.add(name.trim());
        }
    }

    private static void putList(@Nullable AgentSession session, String key, @Nullable Collection<String> names) {
        if (session == null) return;

        List<String> normalized = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                addName(normalized, name);
            }
        }

        if (normalized.isEmpty()) {
            session.getContext().remove(key);
        } else {
            session.getContext().put(key, normalized);
        }
    }

    /**
     * 会话主体（租户 / 用户；未设置为 null）。写入走 {@code HarnessEngine#setSessionPrincipal}（设置后不可改）。
     *
     * @since 4.2.0
     */
    @Nullable
    public static String principalOf(@Nullable AgentSession session) {
        if (session == null) return null;
        Object value = session.getContext().get(HarnessAttrs.SESSION_PRINCIPAL);
        return (value == null || value.toString().trim().isEmpty()) ? null : value.toString();
    }

    /** 引擎内部使用：宿主走 {@code HarnessEngine#setSessionPrincipal}（含不可改校验） */
    public static void setPrincipal(@Nullable AgentSession session, @Nullable String principal) {
        put(session, HarnessAttrs.SESSION_PRINCIPAL, principal);
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
