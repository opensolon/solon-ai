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

import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.Utils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 聊天流上下文实现
 *
 * <p>每一步（每轮模型调用）一个实例；{@link ChatStreamSession} 跨步共享。</p>
 *
 * @author noear
 * @since 4.1
 */
public class ChatStreamContextDefault implements ChatStreamContext {
    private final ChatConfig config;
    private final ChatRequest request;
    private final ChatAccumulator acc;
    private final ChatStreamSession session;
    private final ChatEventEmitter emitter;
    private final int step;

    //供应商响应标识：首帧提取一次，之后本步所有事件自动预填（方言无需逐处手写）
    private String providerResponseId;

    public ChatStreamContextDefault(ChatConfig config, ChatRequest request, ChatAccumulator acc,
                                    ChatStreamSession session, int step, ChatEventEmitter emitter) {
        this.config = config;
        this.request = request;
        this.acc = acc;
        this.session = session;
        this.step = step;
        this.emitter = emitter;
    }

    @Override
    public String getProviderResponseId() {
        return providerResponseId;
    }

    /**
     * 记录供应商响应标识：null / 空将被忽略（不覆盖已记录值）。
     * 之后本上下文创建的事件自动预填，方言无需逐处手写。
     */
    @Override
    public void setProviderResponseId(String id) {
        if (Utils.isNotEmpty(id)) {
            this.providerResponseId = id;
        }
    }

    /**
     * 创建「不发事件」的上下文
     *
     * <p>供方言 / 解析器单测直接调解析入口：{@code emit} 为空操作，其余行为与正常上下文一致。
     * 生产路径一律使用带 emitter 的上下文，不要用它顶替。</p>
     *
     * <p><b>注意</b>：走这个上下文的解析路径不会有任何事件产出，事件会被静默丢弃且不报错
     * ——这正是命名为 {@code ofNoEmit} 而非 {@code ofLegacy} 的原因：降级要显性。</p>
     *
     * @param acc 响应累积器
     * @since 4.1
     */
    public static ChatStreamContext ofNoEmit(ChatAccumulator acc) {
        return ofNoEmit(null, acc);
    }

    /**
     * 创建「不发事件」的上下文（带聊天配置）
     *
     * @param config 聊天配置
     * @param acc    响应累积器
     * @since 4.1
     */
    public static ChatStreamContext ofNoEmit(ChatConfig config, ChatAccumulator acc) {
        return new ChatStreamContextDefault(config, acc.getRequest(), acc, null, 0, null);
    }

    @Override
    public ChatConfig getConfig() {
        return config;
    }

    @Override
    public ChatRequest getRequest() {
        return request;
    }

    @Override
    public ChatAccumulator getAccumulator() {
        return acc;
    }

    @Override
    public boolean isStream() {
        return acc.isStream();
    }

    @Override
    public String getResponseId() {
        return session == null ? null : session.getResponseId();
    }

    @Override
    public int getStep() {
        return step;
    }

    @Override
    public void emit(ChatEvent event) {
        if (emitter != null && event != null) {
            emitter.emit(event);
        }
    }

    @Override
    public ChatEventDefault.Builder event(ChatEventType type) {
        return ChatEventDefault.of(type)
                .responseId(getResponseId())
                .providerResponseId(providerResponseId)
                .step(step);
    }

    /// //////////////////////////

    private final Map<String, Object> attrs = new HashMap<>();

    @Override
    public <T> T attrAs(String name) {
        return (T) attrs.get(name);
    }

    @Override
    public void attrPut(String name, Object val) {
        attrs.put(name, val);
    }

    @Override
    public <T> T attrIfAbsent(String name, Function<String, T> function) {
        return (T) attrs.computeIfAbsent(name, function);
    }

    @Override
    public <T> T attrRemove(String name) {
        return (T) attrs.remove(name);
    }
}
