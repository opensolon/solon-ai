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

/**
 * 聊天事件分组
 *
 * <p>这是一个 <b>闭集</b>（9 个）。订阅方应优先 switch 在分组上，而不是具体的
 * {@link ChatEventType}：新增事件类型不会引入新的分组，因此基于分组的分派天然向前兼容。</p>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
public enum ChatEventGroup {
    /**
     * 生命周期（响应开始、状态、心跳、响应结束、中止）
     */
    LIFECYCLE,
    /**
     * 步骤（一次模型调用为一步，自动工具调用会产生多步）
     */
    STEP,
    /**
     * 正文文本
     */
    TEXT,
    /**
     * 思考（推理）内容
     */
    THINKING,
    /**
     * 客户端工具调用（由本地执行）
     */
    TOOL_CALL,
    /**
     * 服务端工具调用（由模型服务方执行，如联网搜索、代码执行）
     */
    SERVER_TOOL,
    /**
     * 引用与媒体
     */
    MEDIA,
    /**
     * 安全（拒答、内容过滤）
     */
    SAFETY,
    /**
     * 元信息（用量、错误、原始事件、自定义）
     */
    META;
}
