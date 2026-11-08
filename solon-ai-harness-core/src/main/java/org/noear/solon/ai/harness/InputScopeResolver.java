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
package org.noear.solon.ai.harness;

import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.session.SessionSelections;
import java.util.function.Function;
import java.util.Objects;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 作用域解析器（引擎级，{@code Builder.scopeResolver(...)}）
 *
 * <p>每次受理的输入都会调用一次（用户输入、HITL 续流、命令产物、goal / loop / queue 的程序输入），
 * 输入已显式携带作用域时不调用。典型实现：读出会话主体（{@code HarnessEngine#setSessionPrincipal}），再查授权表，
 * 见 {@link #byPrincipal}。</p>
 *
 * <p><b>调用时机</b>：受理之前（会话归属检查之后、会话锁之外），此时扩展的 {@code onInput} 尚未执行——
 * 解析器只应依赖会话与输入本身。会话忙、输入进积压时会预检一次，续发时再解析一次（权限变更立即生效）；
 * 授权查询较重时建议按主体做短期缓存（5–30 秒）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
@FunctionalInterface
public interface InputScopeResolver {
    /**
     * @return 本轮作用域；null 表示不限制
     */
    @Nullable
    InputScope resolve(@Nullable AgentSession session, HarnessInput input);

    /**
     * 按会话主体解析：会话没有主体（或无会话）时返回 null（{@code scopeRequired} 下即拒绝）。
     *
     * <pre>{@code
     * .scopeResolver(InputScopeResolver.byPrincipal(principal -> grants.scopeOf(principal)))
     * }</pre>
     *
     * @param lookup 主体 → 作用域（返回 null 表示无授权）
     * @since 4.2.0
     */
    static InputScopeResolver byPrincipal(Function<String, InputScope> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return (session, input) -> {
            String principal = SessionSelections.principalOf(session);
            return principal == null ? null : lookup.apply(principal);
        };
    }
}
