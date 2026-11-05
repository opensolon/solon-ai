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
 * 本类服务于<b>拿不到引擎实例</b>的场景（如宿主侧的静态 trace 工具），使那类代码
 * 不必直接读写 {@code CTX_MODEL_SELECTED} / {@code CTX_AGENT_SELECTED} 裸常量。</p>
 *
 * <p>实现只有一份：引擎实例方法委托到本类，因此两处口径永远一致。</p>
 *
 * @author noear
 * @since 4.2.3
 */
public final class SessionSelections {
    private SessionSelections() {
    }

    /** 会话级模型选择（未设置 / 空串 返回 {@code null}，调用方回退默认模型）。 */
    @Nullable
    public static String modelOf(@Nullable AgentSession session) {
        if (session == null) return null;
        Object value = session.getContext().get(org.noear.solon.ai.harness.HarnessEngine.CTX_MODEL_SELECTED);
        return (value == null || value.toString().trim().isEmpty()) ? null : value.toString();
    }

    /** 会话级子代理选择（未设置 / 空串 返回 {@code null}，表示主代理）。 */
    @Nullable
    public static String agentOf(@Nullable AgentSession session) {
        if (session == null) return null;
        Object value = session.getContext().get(org.noear.solon.ai.harness.HarnessEngine.CTX_AGENT_SELECTED);
        return (value == null || value.toString().trim().isEmpty()) ? null : value.toString();
    }
}
