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
package org.noear.solon.ai.harness.runtime;

import lombok.Getter;

/**
 * 单轮执行结果（loop 与 goal 共用）。
 *
 * <p>不含任何业务判定语义，仅承载一次执行的客观事实：是否完成、
 * 是否有工具调用证据、消耗的 token、最终文本。完成与否的解释权在使用方。</p>
 *
 * @author noear
 * @since 3.9.4
 */
@Getter
public class RoundResult {
    private final boolean completed;
    private final boolean hasToolCalls;
    private final long tokensUsed;
    private final String finalResult;

    private RoundResult(boolean completed, boolean hasToolCalls, long tokensUsed, String finalResult) {
        this.completed = completed;
        this.hasToolCalls = hasToolCalls;
        this.tokensUsed = tokensUsed;
        this.finalResult = finalResult;
    }

    /** 从执行结果文本构建（无真实 token / 工具证据时的启发式降级）。 */
    public static RoundResult ofText(String text) {
        boolean hasToolCalls = text != null && text.length() > 20
                && !text.startsWith("error:") && !text.equals("ok");
        long tokens = text != null ? Math.max(1, text.length() / 4) : 0;
        return new RoundResult(text != null, hasToolCalls, tokens, text);
    }

    /** 使用真实工具调用与 token 信息构建。 */
    public static RoundResult of(boolean hasToolCalls, long tokensUsed, String text) {
        return new RoundResult(text != null, hasToolCalls, tokensUsed, text);
    }
}
