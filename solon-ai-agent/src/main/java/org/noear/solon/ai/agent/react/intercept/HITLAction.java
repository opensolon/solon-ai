/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.lang.Nullable;

/**
 * HITL 审批决策动作（宿主边界词汇）
 * <p>
 * 与 {@link HITLDecision} 的关系：HITLDecision 是策略/拦截器管线内的可变实体
 * （携带 modifiedArgs、alwaysAllow 等执行细节）；本枚举只是宿主向引擎声明
 * 「这一步怎么处置」的动作词，不携带任何执行细节。
 *
 * @author noear 2026/10/30 created
 * @since 4.2.0
 */
public enum HITLAction {
    /** 批准执行 */
    APPROVE,
    /** 拒绝执行 */
    REJECT,
    /** 跳过本次调用（按被跳过处理，不执行工具） */
    SKIP;

    /**
     * 线上协议值到枚举的边界解析
     * <p>
     * 空值返回 null（表示本次输入不是审批决策）；不识别的值抛
     * {@link IllegalArgumentException}，由宿主捕获后回错误 ——
     * 避免脏值被静默当作拒绝执行。
     * <p>
     * 线上契约是小写（approve / reject / skip），不做大小写容错：
     * 此前字符串时代 {@code "approve".equals(action)} 会把 "Approve" 静默判为拒绝，
     * 容错等于把这类 bug 放回来。
     *
     * @param value 协议值（可为空）
     */
    public static @Nullable HITLAction of(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        switch (value) {
            case "approve":
                return APPROVE;
            case "reject":
                return REJECT;
            case "skip":
                return SKIP;
            default:
                throw new IllegalArgumentException("Unknown HITL action: " + value);
        }
    }
}
