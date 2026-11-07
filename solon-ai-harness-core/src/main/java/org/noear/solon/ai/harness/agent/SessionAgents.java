/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * 会话级子代理（只对创建它的会话可见）
 *
 * <p>{@code generate(saveToFile=false)} 的产物存放在这里：会话上下文（随快照持久化，重启后同一会话仍可调用），
 * 不进引擎级 {@link AgentManager}，因此同引擎的其它会话 / 用户看不到也调不到。会话删除时随会话一起消失。</p>
 *
 * <p>存的是 markdown 文本（{@link AgentDefinition#toMarkdown()}），不把复杂对象写进快照。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class SessionAgents {
    /** 每会话上限（防止撑大快照） */
    public static final int MAX_PER_SESSION = 16;

    private static final String KEY = HarnessAttrs.SESSION_AGENTS;

    private SessionAgents() {
    }

    /**
     * 登记会话级子代理（同名不覆盖）
     *
     * @return false = 本会话已有同名，或已达上限
     */
    public static boolean putIfAbsent(AgentSession session, AgentDefinition def, int max) {
        Objects.requireNonNull(session, "session");
        synchronized (session.attrs()) {
            Map<String, String> map = new LinkedHashMap<>(mapOf(session));
            if (map.containsKey(def.getName()) || map.size() >= max) {
                return false;
            }
            map.put(def.getName(), def.toMarkdown());
            session.getContext().put(KEY, map);
        }
        return true;
    }

    /** 本会话的子代理定义（无则 null） */
    @Nullable
    public static AgentDefinition get(@Nullable AgentSession session, @Nullable String name) {
        if (session == null || name == null) {
            return null;
        }
        String md = mapOf(session).get(name);
        if (md == null) {
            return null;
        }
        AgentDefinition def = AgentDefinition.fromParsedMarkdown(
                MarkdownUtil.resolve(Arrays.asList(md.split("\\R", -1)), true));
        def.getMetadata().setName(name);
        return def;
    }

    /** 本会话的子代理名（按创建顺序） */
    public static Collection<String> names(@Nullable AgentSession session) {
        if (session == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableCollection(new ArrayList<>(mapOf(session).keySet()));
    }

    /** 删除本会话的某个子代理 */
    public static boolean remove(@Nullable AgentSession session, @Nullable String name) {
        if (session == null || name == null) {
            return false;
        }
        synchronized (session.attrs()) {
            Map<String, String> map = new LinkedHashMap<>(mapOf(session));
            if (map.remove(name) == null) {
                return false;
            }
            if (map.isEmpty()) {
                session.getContext().remove(KEY);
            } else {
                session.getContext().put(KEY, map);
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> mapOf(AgentSession session) {
        Object v = session.getContext().get(KEY);
        if (!(v instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }
}
