/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.chat.event;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天流会话
 *
 * <p>一次 {@code stream()} 订阅创建一个，贯穿自动工具调用的全部递归轮次。它保证以下跨轮不变量：</p>
 * <ul>
 *   <li>{@code responseId} 全流一致</li>
 *   <li>{@code step} 每轮模型调用递增，不因递归重入而复位</li>
 *   <li>{@link ChatEventType#RESPONSE_START} 与 {@link ChatEventType#RESPONSE_END} 全流各恰好一次</li>
 * </ul>
 *
 * @author noear
 * @since 4.1
 */
public class ChatStreamSession {
    private final String responseId;
    private final AtomicBoolean responseStarted = new AtomicBoolean(false);
    private final AtomicBoolean responseEnded = new AtomicBoolean(false);

    private AiUsage totalUsage;
    private final List<ONode> usageSources = new ArrayList<>();

    /**
     * 累计各模型步骤的用量
     *
     * <p>用量合并分两层，混用会造成静默的计费错误（无异常、无日志）：</p>
     * <ul>
     *   <li><b>步内覆盖（不在本方法）</b>：方言在一步内多次给出的 usage 是整条消息的累计快照，
     *   由 {@code ChatAccumulator#setUsage} 覆盖式持有（需按字段合并的方言自行处理，如 Anthropic 的
     *   input_tokens / cache_* 仅见于 message_start）。</li>
     *   <li><b>步间累加（本方法）</b>：每一步是一次独立的模型调用，各步相加才是本次 {@code stream()}
     *   的真实消耗，否则 {@code RESPONSE_END} 只携带最后一轮用量。</li>
     * </ul>
     *
     * <p>调用约束：每步恰好调用一次（在 {@code STEP_END} 发射处）。重复调用会重复计数。</p>
     */
    public synchronized AiUsage accumulateUsage(AiUsage usage) {
        if (usage == null) {
            return totalUsage;
        }
        if (usage.getSource() != null) {
            usageSources.add(ONode.ofJson(usage.getSource().toJson()));
        }

        if (totalUsage == null) {
            totalUsage = usage;
        } else {
            totalUsage = totalUsage.toBuilder()
                    .promptTokens(totalUsage.promptTokens() + usage.promptTokens())
                    .thinkTokens(totalUsage.thinkTokens() + usage.thinkTokens())
                    .completionTokens(totalUsage.completionTokens() + usage.completionTokens())
                    .totalTokens(totalUsage.totalTokens() + usage.totalTokens())
                    .cacheCreationInputTokens(totalUsage.cacheCreationInputTokens() + usage.cacheCreationInputTokens())
                    .cacheReadInputTokens(totalUsage.cacheReadInputTokens() + usage.cacheReadInputTokens())
                    // TTL 明细与汇总同为步间累加项；漏掉这两项会让多步流的缓存成本明细静默归零
                    .cacheCreation5mInputTokens(totalUsage.cacheCreation5mInputTokens() + usage.cacheCreation5mInputTokens())
                    .cacheCreation1hInputTokens(totalUsage.cacheCreation1hInputTokens() + usage.cacheCreation1hInputTokens())
                    // 服务端内置工具按「次」计费，与 token 同为累加项
                    .webSearchRequests(totalUsage.webSearchRequests() + usage.webSearchRequests())
                    .webFetchRequests(totalUsage.webFetchRequests() + usage.webFetchRequests())
                    // 档位/区域是标签不是计数，累加没有意义；取首个非空值（同一次 stream 内各步通常一致，
                    // 万一各步不同，逐步原值仍完整保留在 source.steps[] 里）
                    .serviceTier(firstNonEmpty(totalUsage.serviceTier(), usage.serviceTier()))
                    .inferenceGeo(firstNonEmpty(totalUsage.inferenceGeo(), usage.inferenceGeo()))
                    .source(aggregateSource())
                    .build();
        }
        return totalUsage;
    }

    private static String firstNonEmpty(String prev, String curr) {
        return Utils.isEmpty(prev) ? curr : prev;
    }

    /**
     * 聚合各步骤的原始 usage。
     *
     * <p>单步时保持原始节点形态；多步时使用 {@code steps} 数组完整保留每一轮的
     * provider-specific 字段，避免把不同模型调用的快照错误地做 max 或静默覆盖。</p>
     */
    private ONode aggregateSource() {
        if (usageSources.isEmpty()) {
            return totalUsage == null ? null : totalUsage.getSource();
        }

        if (usageSources.size() == 1) {
            return usageSources.get(0);
        }

        ONode result = ONode.ofJson("{\"steps\":[]}");
        result.get("steps").getArray().addAll(usageSources);
        return result;
    }

    public synchronized AiUsage getTotalUsage() {
        return totalUsage;
    }


    private int step = -1;

    public ChatStreamSession() {
        this.responseId = Utils.uuid();
    }

    /**
     * 响应标识（全流一致）
     */
    public String getResponseId() {
        return responseId;
    }

    /**
     * 当前步序号
     */
    public int getStep() {
        return step < 0 ? 0 : step;
    }

    /**
     * 进入下一步（返回新的步序号）
     */
    public int nextStep() {
        return ++step;
    }

    /**
     * 标记响应已开始（仅首次返回 true）
     */
    public boolean markResponseStarted() {
        return responseStarted.compareAndSet(false, true);
    }

    /**
     * 标记响应已结束（仅首次返回 true）
     */
    public boolean markResponseEnded() {
        return responseEnded.compareAndSet(false, true);
    }

    /**
     * 响应是否已结束
     */
    public boolean isResponseEnded() {
        return responseEnded.get();
    }
}
