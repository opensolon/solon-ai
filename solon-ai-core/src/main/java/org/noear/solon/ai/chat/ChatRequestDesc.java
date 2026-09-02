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
package org.noear.solon.ai.chat;

import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventFilter;
import org.noear.solon.ai.chat.event.ChatEvents;
import org.noear.solon.lang.Preview;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 聊天请求描述
 *
 * @author noear
 * @since 3.1
 * @since 3.3
 */
@Preview("3.1")
public interface ChatRequestDesc {
    /**
     * 会话设置
     *
     * @param session 会话
     * @since 3.8.4
     */
    ChatRequestDesc session(ChatSession session);

    /**
     * 角色
     *
     * @since 4.0.4
     */
    ChatRequestDesc role(String role);

    /**
     * 指令
     *
     * @since 4.0.4
     */
    ChatRequestDesc instruction(String instruction);

    /**
     * 系统提示词
     *
     * @since 4.0.4
     */
    ChatRequestDesc systemPrompt(String systemPrompt);

    /**
     * 选项设置
     *
     * @param options 选项
     * @deprecated 4.0.4
     */
    @Deprecated
    ChatRequestDesc options(ChatOptions options);

    /**
     * 选项配置
     *
     * @param optionsBuilder 选项构建器
     */
    ChatRequestDesc options(Consumer<ChatOptions> optionsBuilder);

    /**
     * 事件投递过滤器
     *
     * <p>默认为 {@link ChatEventFilter#DEFAULT}（挡掉 {@code HEARTBEAT} 与 {@code RAW}）。
     * 网关透传、协议诊断等需要全量事件的场景用
     * {@code eventFilter(ChatEventFilter.all())} 显式开启。</p>
     *
     * <p>无论如何过滤，{@code LIFECYCLE} 与 {@code STEP} 两个分组一律放行：它们是
     * {@link #stream()} 的不变量载体（{@code RESPONSE_END} 携带终态聚合，
     * {@link ChatEvents#reduce} 依赖它）。若允许被挡掉，一个看似无害的
     * {@code of(TEXT_DELTA)} 会让归约静默返回 null。</p>
     *
     * @param filter 过滤器
     * @since 4.1
     */
    ChatRequestDesc eventFilter(ChatEventFilter filter);

    /**
     * 调用
     */
    ChatResponse call() throws IOException;

    /**
     * 事件流响应
     *
     * <p>一次模型响应的全部语义事件，也是流式的<b>唯一</b>订阅面：标准 Reactor 操作符
     * （filter / map / buffer / window / timeout / publishOn / retryWhen）全部可用。
     * 非响应式风格用 {@code stream().subscribe(...)}。</p>
     *
     * <p>订阅方应 switch 在 {@link ChatEvent#getGroup()} 上并保留 default 分支，
     * 这样后续版本新增具体事件类型时已写的分派逻辑不会漏事件。</p>
     *
     * <p><b>不变量</b>：{@code RESPONSE_START} 与 {@code RESPONSE_END} 全流各恰好一次
     * （含异常终止路径）；每轮模型调用对应一对 {@code STEP_START} / {@code STEP_END}；
     * 每个 {@code TEXT_DELTA} / {@code THINKING_DELTA} 一定被对应的 START/END 包裹；
     * 每个 {@code TOOL_CALL_ARGS_DELTA} 之前必有 {@code TOOL_CALL_START}，流终止前必有 {@code TOOL_CALL_END}。</p>
     *
     * <p><b>失败语义（双通道）</b>：流失败时订阅方会以两种方式收到同一个失败：
     * 先收到一个携带已完成部分聚合的 {@code ERROR} 事件，随后流以同一异常 {@code onError} 终止。
     * 两者由同一异常派生，不会出现先后不一致的失败描述。失败处理逻辑只需写在其中一处：
     * 要展示部分结果或终止 UI，就消费 {@code ERROR} 事件（{@code getResponse()} 可打捞已完成内容，
     * {@code getUsage()} 可取已累计用量）；要用 {@code retryWhen} / {@code onErrorResume} 等操作符恢复，
     * 就依赖 {@code onError} 信号。注意失败时<b>不会</b>再发 {@code RESPONSE_END}——
     * 「收到 RESPONSE_END 即视为成功」的判定是安全的。</p>
     *
     * <p>{@code HEARTBEAT} 与 {@code RAW} 默认不投递，见 {@link #eventFilter(ChatEventFilter)}。</p>
     *
     * @since 4.1 返回类型由 {@code Flux<ChatResponse>} 改为 {@code Flux<ChatEvent>}
     */
    Flux<ChatEvent> stream();
}
