/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.command;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.lang.Nullable;

import java.util.List;

/**
 * 通用命令上下文接口
 * <p>
 * 不同端（CLI、Web）提供各自的实现，使命令逻辑与具体环境解耦。
 *
 * @author noear
 * @since 2026.4.28
 */
public interface CommandContext {
    /**
     * 是否为远程上下文
     */
    default boolean isRemote(){
        return false;
    }

    /**
     * 获取当前会话
     */
    AgentSession getSession();

    /**
     * 获取用户原始输入
     */
    String getRawInput();

    /**
     * 获取命令名（不含 / 前缀）
     */
    String getCommandName();

    /**
     * 获取命令参数列表
     */
    List<String> getArgs();

    /**
     * 按索引获取参数，越界返回 null
     */
    default String argAt(int index) {
        List<String> args = getArgs();
        return (args != null && index < args.size()) ? args.get(index) : null;
    }

    /**
     * 参数数量
     */
    default int argCount() {
        List<String> args = getArgs();
        return args != null ? args.size() : 0;
    }

    /**
     * 全部参数拼接为单个字符串
     */
    default String getArgsJoined() {
        List<String> args = getArgs();
        return args != null ? String.join(" ", args) : "";
    }

    /**
     * 输出一行文本（不同端有不同的实现）
     */
    void println(String text);

    /**
     * 是否支持 ANSI 颜色（CLI 返回 true，Web 返回 false）
     */
    default boolean supportsAnsi() {
        return false;
    }

    /**
     * 如果支持 ANSI 则原样返回，否则去除 ANSI 转义序列
     */
    default String color(String text) {
        return supportsAnsi() ? text : text.replaceAll("\033\\[[0-9;]*m", "");
    }

    /**
     * 声明 Agent 任务意图（纯预处理，不在此开流）
     * <p>
     * 命令只负责「改会话状态 + 声明意图」；真正的请求装配由分发器交给引擎完成，
     * 宿主统一经 {@code InputOutcome} 消费，因此三端不必再各自实现回调。
     *
     * @param prompt 任务正文；<b>为空表示续流</b>（如 {@code /continue}）
     * @param model  命令声明的模型（可空，走会话级回退）
     */
    void requestAgentTask(@Nullable String prompt, @Nullable String model);

    /**
     * 声明针对指定会话的 Agent 任务意图（跨会话命令如 {@code /continue <sessionId>}）
     * <p>
     * 默认不支持：只有需要跨会话控制的端实现才覆盖。
     *
     * @param session 目标任务会话
     * @param prompt  任务正文；为空表示续流
     * @param model   命令声明的模型（可空）
     */
    default void requestAgentTask(@Nullable AgentSession session, @Nullable String prompt, @Nullable String model) {
        if (session != null && session != getSession()) {
            throw new UnsupportedOperationException("Cross-session agent task is not supported by this context");
        }
        requestAgentTask(prompt, model);
    }

    /**
     * 已声明的任务意图（未声明时为 null）
     */
    default @Nullable AgentTaskIntent getAgentTaskIntent() {
        return null;
    }

    /**
     * 命令回执文本
     * <p>
     * 采用即时写终端/直接推送的端（如 CLI）无需实现，返回 null 即可；
     * 缓冲式实现应返回已收集的全部回执行。
     */
    default String getCommandOutput() {
        return null;
    }

    /**
     * 消息来源渠道标识（如 Feishu、DingTalk、WeChat、CLI；Web/本地默认为 null）。
     * <p>
     * 由 IM 等远程渠道的上下文实现提供，供 /steer、/queue 等命令透传到
     * 排队/插话元数据。本地上下文不覆盖时返回 null。
     */
    default String getSource() {
        return null;
    }

    /**
     * 来源渠道的用户 ID（远程渠道提供，用于消息归属与审计）。
     */
    default String getSourceUserId() {
        return null;
    }

    /**
     * 回复目标（如 IM 群/会话 ID），续发或回执时定位投递位置。
     */
    default String getReplyTarget() {
        return null;
    }

    /**
     * 触发本条命令的消息 ID（远程渠道提供，用于回复关联与幂等去重）。
     */
    default String getMessageId() {
        return null;
    }
}