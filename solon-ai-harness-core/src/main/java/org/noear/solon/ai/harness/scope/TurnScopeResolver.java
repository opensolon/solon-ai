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
package org.noear.solon.ai.harness.scope;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 作用域解析器（引擎级，{@code Builder.scopeResolver(...)}）
 *
 * <p>每次受理的输入都会调用一次（用户输入、HITL 续流、命令产物、goal / loop / queue 的程序输入），
 * 输入已显式携带作用域时不调用。典型实现：从会话属性读出创建会话时写入的租户 / 角色，再查授权表。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
@FunctionalInterface
public interface TurnScopeResolver {
    /**
     * @return 本轮作用域；null 表示不限制
     */
    @Nullable
    TurnScope resolve(@Nullable AgentSession session, HarnessInput input);
}
