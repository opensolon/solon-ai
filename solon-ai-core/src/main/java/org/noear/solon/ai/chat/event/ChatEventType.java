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

import org.noear.solon.lang.Preview;

import static org.noear.solon.ai.chat.event.ChatEventGroup.*;
import static org.noear.solon.ai.chat.event.ChatEventPhase.*;

/**
 * 聊天事件类型
 *
 * <p>每个类型静态绑定一个 {@link ChatEventGroup} 与 {@link ChatEventPhase}。绑定关系在枚举构造期确定，
 * 实现类不可覆写，因此 {@code getType()}、{@code getGroup()}、{@code getPhase()} 三者永不漂移。</p>
 *
 * <p><b>向前兼容约定</b>：订阅方应 switch 在 {@link #getGroup()} 上，并保留 default 分支。
 * 后续版本新增具体类型时，只会落入既有的 9 个分组，不会破坏已写的分派逻辑。</p>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
public enum ChatEventType {
    /**
     * 一次模型响应开始（全流仅一次，在第一步之前）
     */
    RESPONSE_START(LIFECYCLE, START),
    /**
     * 服务端状态推进（如 queued / in_progress）
     */
    STATUS(LIFECYCLE, NONE),
    /**
     * 心跳（默认不投递，需通过事件过滤器显式开启）
     */
    HEARTBEAT(LIFECYCLE, NONE),
    /**
     * 一次模型响应结束（全流恰好一次，携带不可变终态聚合与汇总用量）
     */
    RESPONSE_END(LIFECYCLE, END),
    /**
     * 服务端中止（如 response.incomplete）；订阅方主动取消走 Reactor 原生 cancel 语义
     */
    ABORT(LIFECYCLE, END),

    /**
     * 一步开始（一次模型调用）
     */
    STEP_START(STEP, START),
    /**
     * 一步结束（携带该步的分步聚合）
     */
    STEP_END(STEP, END),

    /**
     * 正文开始
     */
    TEXT_START(TEXT, START),
    /**
     * 正文增量
     */
    TEXT_DELTA(TEXT, DELTA),
    /**
     * 正文结束
     */
    TEXT_END(TEXT, END),

    /**
     * 思考开始
     */
    THINKING_START(THINKING, START),
    /**
     * 思考增量
     */
    THINKING_DELTA(THINKING, DELTA),
    /**
     * 思考结束
     */
    THINKING_END(THINKING, END),

    /**
     * 思考签名（Claude thinking signature / Gemini thought signature / Responses encrypted_content）
     *
     * <p><b>合并语义是幂等赋值，不是追加</b>：尽管它落在 THINKING 分组内，签名每个思考块只有一个，
     * 收到即覆盖同一块的旧值。不要沿用 {@code *_DELTA} 的拼接习惯——签名被拼接后在下一轮回传时
     * 会被服务端判为无效，导致多轮思考链断裂。</p>
     */
    THINKING_SIGNATURE(THINKING, NONE),
    /**
     * 被脱敏的思考块（Anthropic redacted_thinking）
     */
    THINKING_REDACTED(THINKING, NONE),

    /**
     * 客户端工具调用开始
     */
    TOOL_CALL_START(TOOL_CALL, START),
    /**
     * 客户端工具调用参数增量
     */
    TOOL_CALL_ARGS_DELTA(TOOL_CALL, DELTA),
    /**
     * 客户端工具调用完成（参数已完整）
     */
    TOOL_CALL_END(TOOL_CALL, END),
    /**
     * 客户端工具调用整块
     */
    TOOL_CALL_CHUNK(TOOL_CALL, CHUNK),
    /**
     * 客户端工具执行结果（本地执行完毕）
     */
    TOOL_RESULT(TOOL_CALL, NONE),

    /**
     * 服务端工具调用开始（类别见 {@code getSubType()}）
     */
    SERVER_TOOL_START(SERVER_TOOL, START),
    /**
     * 服务端工具调用参数增量
     */
    SERVER_TOOL_ARGS_DELTA(SERVER_TOOL, DELTA),
    /**
     * 服务端工具调用结果
     */
    SERVER_TOOL_RESULT(SERVER_TOOL, END),

    /**
     * 引用（来源文档 / URL）
     */
    CITATION(MEDIA, NONE),
    /**
     * 媒体增量（如图片渐进生成）
     */
    MEDIA_PARTIAL(MEDIA, DELTA),
    /**
     * 媒体完成
     */
    MEDIA_DONE(MEDIA, END),

    /**
     * 拒答增量
     */
    REFUSAL_DELTA(SAFETY, DELTA),
    /**
     * 内容被安全策略过滤
     */
    CONTENT_FILTER(SAFETY, NONE),

    /**
     * 用量（每步可有；汇总值见 RESPONSE_END）
     *
     * <p><b>合并规则分两层，混用会造成静默的计费错误</b>：</p>
     * <ul>
     *   <li><b>步内覆盖</b>：同一步中方言给出的 usage 是「整条消息的累计快照」而非增量
     *   （如 Anthropic {@code message_delta.usage} 的 output_tokens 为累计值），后到的覆盖先到的，
     *   绝不相加。方言若只在部分帧携带输入侧字段（Anthropic 的 input_tokens / cache_* 仅见于
     *   {@code message_start}），须按字段合并而非整体覆盖，否则输入侧计费数据会丢失。</li>
     *   <li><b>步间累加</b>：自动工具调用的每一轮都是独立的一次模型调用，各步 usage 相加才是本次
     *   {@code stream()} 的真实消耗。该累加由核心在 {@code STEP_END} 时完成，方言不必参与。</li>
     * </ul>
     *
     * <p>因此 {@code RESPONSE_END} 携带的是全流汇总值，可能大于任何单个 {@code USAGE} 事件。</p>
     */
    USAGE(META, NONE),
    /**
     * 错误
     */
    ERROR(META, NONE),
    /**
     * 未建模的原始事件（默认不投递，需通过事件过滤器显式开启）
     */
    RAW(META, NONE),
    /**
     * 自定义事件（供扩展方言使用）
     */
    CUSTOM(META, NONE);

    private final ChatEventGroup group;
    private final ChatEventPhase phase;

    ChatEventType(ChatEventGroup group, ChatEventPhase phase) {
        this.group = group;
        this.phase = phase;
    }

    /**
     * 所属分组
     */
    public ChatEventGroup getGroup() {
        return group;
    }

    /**
     * 所处阶段
     */
    public ChatEventPhase getPhase() {
        return phase;
    }

    /**
     * 是否为增量事件
     */
    public boolean isDelta() {
        return phase == DELTA;
    }

    /**
     * 是否为整块事件（需归一化展开）
     */
    public boolean isChunk() {
        return phase == CHUNK;
    }

    /**
     * 是否为主干内容事件（正文 / 思考 / 工具调用的开始与增量）
     *
     * <p>这是个很窄的判定，专为「本帧是否已经把内容以事件形态表达完了」这个门控而设，
     * 必须与「真正载有内容主干」严格一致。两类事件存心排除在外：</p>
     * <ul>
     *   <li><b>旁路元数据</b>（{@code CITATION} / {@code THINKING_SIGNATURE} /
     *   {@code THINKING_REDACTED} / {@code MEDIA_*}）：它们虽然落在内容分组里，却常与正文
     *   <b>同帧</b>出现（如 Gemini 的 groundingMetadata 与 content.parts 同居一个 candidate）。
     *   若计入门控，那一帧的正文会被整体静默丢弃，同帧的工具调用也不会被登记。</li>
     *   <li><b>结束相位</b>（{@code *_END}）：只关闭块，自身不载内容。</li>
     * </ul>
     */
    public boolean isMainContent() {
        return (group == TEXT || group == THINKING || group == TOOL_CALL)
                && (phase == START || phase == DELTA || phase == CHUNK);
    }

    /**
     * 是否为终止事件
     */
    public boolean isTerminal() {
        return this == RESPONSE_END || this == ABORT || this == ERROR;
    }
}
