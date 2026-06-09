/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.lang.Preview;

/**
 * 上下文压缩状态块：向用户侧推送当前上下文的大小信息
 *
 * <p>在每次推理回合开始前由 {@link ContextCompressionInterceptor} 生成，
 * 让用户侧能感知到当前上下文的规模以及是否发生了压缩。
 *
 * @author noear
 * @since 4.0.0
 */
@Preview("4.0.0")
public class ContextChunk extends AbsAgentChunk {
    /**
     * 当前上下文的总消息数
     */
    private final int messageCount;
    /**
     * 当前上下文的总 token 数（估算）
     */
    private final int tokenCount;
    /**
     * 本次是否触发了压缩
     */
    private final boolean compressed;
    /**
     * 压缩前消息数（未压缩时为 0）
     */
    private final int beforeMessageCount;
    /**
     * 压缩后消息数（未压缩时为 0）
     */
    private final int afterMessageCount;
    /**
     * 压缩前 token 数（估算，未压缩时为 0）
     */
    private final int beforeTokenCount;
    /**
     * 压缩后 token 数（估算，未压缩时为 0）
     */
    private final int afterTokenCount;

    public ContextChunk(ReActTrace trace, int messageCount, int tokenCount,
                        boolean compressed,
                        int beforeMessageCount, int afterMessageCount,
                        int beforeTokenCount, int afterTokenCount) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession(), null);
        this.messageCount = messageCount;
        this.tokenCount = tokenCount;
        this.compressed = compressed;
        this.beforeMessageCount = beforeMessageCount;
        this.afterMessageCount = afterMessageCount;
        this.beforeTokenCount = beforeTokenCount;
        this.afterTokenCount = afterTokenCount;
    }

    /**
     * 获取当前上下文的总消息数
     */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * 获取当前上下文的总 token 数（估算）
     */
    public int getTokenCount() {
        return tokenCount;
    }

    /**
     * 本次是否触发了压缩
     */
    public boolean isCompressed() {
        return compressed;
    }

    /**
     * 获取压缩前消息数（未压缩时为 0）
     */
    public int getBeforeMessageCount() {
        return beforeMessageCount;
    }

    /**
     * 获取压缩后消息数（未压缩时为 0）
     */
    public int getAfterMessageCount() {
        return afterMessageCount;
    }

    /**
     * 获取压缩前 token 数（估算，未压缩时为 0）
     */
    public int getBeforeTokenCount() {
        return beforeTokenCount;
    }

    /**
     * 获取压缩后 token 数（估算，未压缩时为 0）
     */
    public int getAfterTokenCount() {
        return afterTokenCount;
    }
}
