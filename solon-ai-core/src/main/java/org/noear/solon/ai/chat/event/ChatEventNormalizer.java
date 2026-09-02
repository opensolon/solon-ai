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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
/**
 * 聊天事件归一化器
 *
 * <p>串在方言出口之后，把「各方言各自为政的边界表达」收敛为订阅方可依赖的不变量，
 * 使方言只负责翻译、不负责编码语义：</p>
 * <ul>
 *   <li>每个 {@code TEXT_DELTA} / {@code THINKING_DELTA} 一定被对应的 {@code *_START} 与 {@code *_END} 包裹</li>
 *   <li>{@code *_CHUNK}（只返回完整块的方言）自动展开为 {@code START + DELTA + END}</li>
 *   <li>正文与思考交替时，自动关闭上一个未闭合的内容块</li>
 *   <li>{@code STEP_END} / {@code RESPONSE_END} / {@code ABORT} 之前，自动补齐未闭合的内容块</li>
 *   <li>重复的 {@code *_START} 与无配对的 {@code *_END} 被丢弃</li>
 *   <li>{@code TOOL_CALL_ARGS_DELTA} 之前必有 {@code TOOL_CALL_START}，流终止前必有 {@code TOOL_CALL_END}</li>
 *   <li>每个 {@code STEP_START} 在流终止前必有配对的 {@code STEP_END}（异常终止路径同样成立）</li>
 * </ul>
 *
 * <p><b>TEXT/THINKING 与 TOOL_CALL 的处理强度不同</b>：前两组按「块标识（itemId + index）」严格配对，
 * 重复 START 去重、孤立 END 丢弃；TOOL_CALL 组只做<b>宽松补齐</b>——补缺失的 START、补未闭合的 END，
 * 但绝不去重 START、绝不丢弃 END。原因是分片式工具调用协议（OpenAI 系）只在首片携带 id，
 * 后续分片的标识会退化为匿名值，若按严格配对处理，并行工具调用会被误去重或误丢完成信号。</p>
 *
 * <p>本类是纯状态机，不持有 IO 与线程语义，可独立单测。非线程安全：一次流用一个实例。</p>
 *
 * @author noear
 * @since 4.1
 */
public class ChatEventNormalizer {
    /**
     * 匿名工具调用标识（分片协议中后续分片不带 id）
     */
    private static final String ANON_TOOL_KEY = "__anon__";

    private final Map<ContentKey, ContentKey> openBlocks = new LinkedHashMap<>();

    /**
     * 未闭合的工具调用标识（宽松跟踪，不参与 openBlocks 的严格配对）
     */
    private final Set<String> openToolCalls = new LinkedHashSet<>();

    /**
     * 未闭合的步序号
     *
     * <p>异常终止时 {@code STEP_END} 不会由核心正常发出（{@code sink.error} 直接跳过收尾逻辑），
     * 若不在此补齐，「STEP 配平」不变量在失败路径上就不成立，订阅方的分步状态机会永久悬空。</p>
     */
    private final Set<Integer> openSteps = new LinkedHashSet<>();

    /**
     * 最近见过的响应标识与步序号
     *
     * <p>供 {@link #complete(ChatEventEmitter)} 补齐收尾事件时使用——该路径没有参照事件，
     * 若不回落就会产出 responseId 为 null 的事件，破坏「全流一致」不变量。</p>
     */
    private String lastResponseId;
    private int lastStep;

    /**
     * 归一化处理
     *
     * @param event 上游（方言）事件
     * @param out   下游发射器
     */
    public void apply(ChatEvent event, ChatEventEmitter out) {
        if (event == null) {
            return;
        }

        ChatEventType type = event.getType();
        ChatEventGroup group = type.getGroup();
        ChatEventPhase phase = type.getPhase();

        if (event.getResponseId() != null) {
            lastResponseId = event.getResponseId();
            lastStep = event.getStep();
        }

        if (phase == ChatEventPhase.CHUNK) {
            closeOpen(out, event);

            ChatEventType startType = startTypeOf(group);
            ChatEventType deltaType = deltaTypeOf(group);
            ChatEventType endType = endTypeOf(group);

            if (startType == null || deltaType == null || endType == null) {
                out.emit(event);
                return;
            }

            out.emit(rebuild(event, startType, null));
            out.emit(rebuild(event, deltaType, event.getText()));
            out.emit(rebuild(event, endType, null));
            return;
        }

        if (group == ChatEventGroup.TOOL_CALL) {
            applyToolCall(event, type, out);
            return;
        }

        if (isBoundaryGroup(group)) {
            ContentKey key = ContentKey.of(event);
            if (phase == ChatEventPhase.START) {
                if (openBlocks.containsKey(key)) {
                    return;
                }
                closeOtherGroups(out, event, group);
                openBlocks.put(key, key);
                out.emit(event);
                return;
            }

            if (phase == ChatEventPhase.DELTA) {
                ContentKey existing = findKey(key, group);
                if (existing == null) {
                    closeOtherGroups(out, event, group);
                    openBlocks.put(key, key);
                    out.emit(rebuild(event, startTypeOf(group), null));
                }
                out.emit(event);
                return;
            }

            if (phase == ChatEventPhase.END) {
                ContentKey existing = findKey(key, group);
                if (existing == null) {
                    return;
                }
                openBlocks.remove(existing);
                out.emit(event);
                return;
            }

            out.emit(event);
            return;
        }

        if (type == ChatEventType.STEP_START) {
            openSteps.add(event.getStep());
            out.emit(event);
            return;
        }

        if (type == ChatEventType.STEP_END
                || type == ChatEventType.RESPONSE_END
                || type == ChatEventType.ABORT
                || type == ChatEventType.ERROR) {
            closeOpenToolCalls(out, event);
            closeOpen(out, event);

            if (type == ChatEventType.STEP_END) {
                openSteps.remove(event.getStep());
            }
        }

        out.emit(event);
    }

    /**
     * 工具调用分组的宽松补齐
     *
     * <p>只补不删：{@code START} 原样透传（并行调用的多个 START 都要留下），
     * {@code ARGS_DELTA} 在完全没有开启中的工具调用时补一个 {@code START}，
     * {@code END} 永不丢弃。</p>
     */
    private void applyToolCall(ChatEvent event, ChatEventType type, ChatEventEmitter out) {
        if (type == ChatEventType.TOOL_CALL_START) {
            //正文/思考与工具调用交替时，先关掉未闭合的内容块
            closeOpen(out, event);
            openToolCalls.add(toolKeyOf(event));
            out.emit(event);
            return;
        }

        if (type == ChatEventType.TOOL_CALL_ARGS_DELTA) {
            if (openToolCalls.isEmpty()) {
                //第三方方言只发增量、不发开始信号时的安全网
                closeOpen(out, event);
                openToolCalls.add(toolKeyOf(event));
                out.emit(rebuild(event, ChatEventType.TOOL_CALL_START, null));
            }
            out.emit(event);
            return;
        }

        if (type == ChatEventType.TOOL_CALL_END) {
            if (openToolCalls.remove(toolKeyOf(event)) == false) {
                //标识对不上（分片协议末尾才给出 id）：退化为关闭最早开启的那个
                Iterator<String> it = openToolCalls.iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            out.emit(event);
            return;
        }

        //TOOL_RESULT：本地执行结果，不是边界事件，原样透传
        out.emit(event);
    }

    private static String toolKeyOf(ChatEvent event) {
        if (event.getToolCallId() != null) {
            return event.getToolCallId();
        }
        if (event.getItemId() != null) {
            return event.getItemId();
        }
        return ANON_TOOL_KEY;
    }

    private void closeOpenToolCalls(ChatEventEmitter out, ChatEvent ref) {
        if (openToolCalls.isEmpty()) {
            return;
        }

        for (String key : new ArrayList<>(openToolCalls)) {
            openToolCalls.remove(key);

            ChatEventDefault.Builder b = ChatEventDefault.of(ChatEventType.TOOL_CALL_END)
                    .toolCallId(ANON_TOOL_KEY.equals(key) ? null : key);

            if (ref != null) {
                b.responseId(ref.getResponseId()).step(ref.getStep());
            } else {
                b.responseId(lastResponseId).step(lastStep);
            }
            out.emit(b.build());
        }
    }

    /**
     * 流终止时收尾（补齐未闭合的内容块、工具调用与步骤）
     *
     * <p>正常完成与异常终止都要调用：这是「不变量在所有路径成立」的唯一保证点。
     * 幂等——重复调用不会产出重复的补齐事件。</p>
     *
     * @param out 下游发射器
     */
    public void complete(ChatEventEmitter out) {
        closeOpenToolCalls(out, null);
        closeOpen(out, null);
        closeOpenSteps(out);
    }

    /**
     * 补齐未闭合的步骤
     */
    private void closeOpenSteps(ChatEventEmitter out) {
        if (openSteps.isEmpty()) {
            return;
        }

        for (Integer step : new ArrayList<>(openSteps)) {
            openSteps.remove(step);
            out.emit(ChatEventDefault.of(ChatEventType.STEP_END)
                    .responseId(lastResponseId)
                    .step(step == null ? lastStep : step)
                    .build());
        }
    }

    /**
     * 当前是否有未闭合的内容块
     */
    public boolean hasOpen() {
        return openBlocks.isEmpty() == false;
    }

    /**
     * 当前未闭合的内容分组（存在多个块时返回最早打开的块分组）
     */
    public ChatEventGroup getOpenGroup() {
        return openBlocks.isEmpty() ? null : openBlocks.keySet().iterator().next().group;
    }

    /// //////////////////////////

    private void closeOpen(ChatEventEmitter out, ChatEvent ref) {
        if (openBlocks.isEmpty()) {
            return;
        }

        for (ContentKey key : new java.util.ArrayList<>(openBlocks.keySet())) {
            closeBlock(out, ref, key);
        }
    }

    private void closeOtherGroups(ChatEventEmitter out, ChatEvent ref, ChatEventGroup keep) {
        for (ContentKey key : new java.util.ArrayList<>(openBlocks.keySet())) {
            if (key.group != keep) {
                closeBlock(out, ref, key);
            }
        }
    }

    private void closeBlock(ChatEventEmitter out, ChatEvent ref, ContentKey key) {
        openBlocks.remove(key);
        ChatEventType endType = endTypeOf(key.group);
        if (endType == null) {
            return;
        }

        ChatEventDefault.Builder b = ChatEventDefault.of(endType)
                .itemId(key.itemId)
                .index(key.index);

        if (ref != null) {
            b.responseId(ref.getResponseId()).step(ref.getStep());
        } else {
            b.responseId(lastResponseId).step(lastStep);
        }
        out.emit(b.build());
    }

    private ContentKey findKey(ContentKey exact, ChatEventGroup group) {
        if (openBlocks.containsKey(exact)) {
            return exact;
        }
        // 没有块标识的旧方言可与同组唯一块配对；多块时必须要求 id/index 精确匹配。
        if (exact.itemId == null && exact.index < 0) {
            ContentKey found = null;
            for (ContentKey key : openBlocks.keySet()) {
                if (key.group == group) {
                    if (found != null) return null;
                    found = key;
                }
            }
            return found;
        }
        return null;
    }

    private static final class ContentKey {
        private final ChatEventGroup group;
        private final String itemId;
        private final int index;

        private ContentKey(ChatEventGroup group, String itemId, int index) {
            this.group = group;
            this.itemId = itemId;
            this.index = index;
        }

        static ContentKey of(ChatEvent event) {
            return new ContentKey(event.getGroup(), event.getItemId(), event.getIndex());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ContentKey)) return false;
            ContentKey that = (ContentKey) o;
            return index == that.index && group == that.group && Objects.equals(itemId, that.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(group, itemId, index);
        }
    }

    private ChatEvent rebuild(ChatEvent src, ChatEventType type, String text) {
        return ChatEventDefault.of(type)
                .rawType(src.getRawType())
                .subType(src.getSubType())
                .responseId(src.getResponseId())
                .step(src.getStep())
                .itemId(src.getItemId())
                .toolCallId(src.getToolCallId())
                .index(src.getIndex())
                .text(text)
                .toolCall(src.getToolCall())
                .block(src.getBlock())
                .usage(src.getUsage())
                .response(src.getResponse())
                .raw(src.getRaw())
                .attrs(src.getAttrs())
                .build();
    }

    private static boolean isBoundaryGroup(ChatEventGroup group) {
        return group == ChatEventGroup.TEXT || group == ChatEventGroup.THINKING;
    }

    private static ChatEventType startTypeOf(ChatEventGroup group) {
        if (group == ChatEventGroup.TEXT) {
            return ChatEventType.TEXT_START;
        }
        if (group == ChatEventGroup.THINKING) {
            return ChatEventType.THINKING_START;
        }
        if (group == ChatEventGroup.TOOL_CALL) {
            return ChatEventType.TOOL_CALL_START;
        }
        return null;
    }

    private static ChatEventType deltaTypeOf(ChatEventGroup group) {
        if (group == ChatEventGroup.TEXT) {
            return ChatEventType.TEXT_DELTA;
        }
        if (group == ChatEventGroup.THINKING) {
            return ChatEventType.THINKING_DELTA;
        }
        if (group == ChatEventGroup.TOOL_CALL) {
            return ChatEventType.TOOL_CALL_ARGS_DELTA;
        }
        return null;
    }

    private static ChatEventType endTypeOf(ChatEventGroup group) {
        if (group == ChatEventGroup.TEXT) {
            return ChatEventType.TEXT_END;
        }
        if (group == ChatEventGroup.THINKING) {
            return ChatEventType.THINKING_END;
        }
        if (group == ChatEventGroup.TOOL_CALL) {
            return ChatEventType.TOOL_CALL_END;
        }
        return null;
    }
}
