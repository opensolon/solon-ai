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
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.function.Function;

/**
 * 聊天流上下文
 *
 * <p>方言解析响应数据时的统一上下文。取代旧解析入口的
 * {@code boolean} 返回值：方言不再需要用返回值区分「有内容」与「解析失败」——
 * 有内容就 {@link #emit(ChatEvent)}，出错就 {@code getResp().setError(...)}，
 * 已消费但无内容则什么都不做。</p>
 *
 * <p>流式与非流式共用同一条解析路径，通过 {@link #isStream()} 区分。</p>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
public interface ChatStreamContext {
    /**
     * 聊天配置
     */
    ChatConfig getConfig();

    /**
     * 聊天请求
     */
    ChatRequest getRequest();

    /**
     * 当前步的响应累积器（方言可变工作台）
     */
    ChatAccumulator getAccumulator();

    /**
     * 是否为流式
     */
    boolean isStream();

    /**
     * 响应标识（全流一致）
     */
    String getResponseId();

    /**
     * 当前步序号
     */
    int getStep();

    /**
     * 发射事件
     */
    void emit(ChatEvent event);

    /**
     * 供应商响应标识（如 {@code resp_xxx} / {@code msg_xxx}；服务未提供则为 null）
     *
     * <p>与本接口的 {@link #getResponseId()} 不同：那个是客户端生成、全流一致的内部标识；
     * 这个是供应商在响应帧里给出的原始 id，用于关联供应商侧日志排障，随当前步的模型调用变化
     * （自动工具调用每轮是一次新的模型调用，各轮 id 不同）。</p>
     */
    @Nullable
    String getProviderResponseId();

    /**
     * 记录供应商响应标识
     *
     * <p>方言在首个携带 id 的帧处调用（如 {@code message_start.message.id}、
     * {@code response.created.response.id}）；之后的 {@link #event(ChatEventType)}
     * 会自动预填到事件上，无需逐处手写。</p>
     *
     * @param id 供应商响应标识；null 或空将被忽略（不覆盖已记录值）
     */
    void setProviderResponseId(String id);

    /**
     * 创建事件构建器（已预填 responseId 与 step）
     *
     * @param type 事件类型
     */
    ChatEventDefault.Builder event(ChatEventType type);

    /// //////////////////////////

    /**
     * 取方言私有状态
     */
    <T> T attrAs(String name);

    /**
     * 存方言私有状态
     */
    void attrPut(String name, Object val);

    /**
     * 取方言私有状态（不存在则计算）
     */
    <T> T attrIfAbsent(String name, Function<String, T> function);

    /**
     * 移除方言私有状态
     */
    <T> T attrRemove(String name);
}
