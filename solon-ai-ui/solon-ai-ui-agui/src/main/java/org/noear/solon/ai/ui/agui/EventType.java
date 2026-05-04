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
package org.noear.solon.ai.ui.agui;

/**
 * AG-UI 协议事件类型枚举
 * <p>
 * 事件按用途分为：生命周期事件、文本消息事件、工具调用事件、状态管理事件、特殊事件、推理过程事件和思考过程事件（已废弃）。
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/events">AG-UI Events</a>
 */
public enum EventType {
    TEXT_MESSAGE_START("TEXT_MESSAGE_START"),
    TEXT_MESSAGE_CONTENT("TEXT_MESSAGE_CONTENT"),
    TEXT_MESSAGE_END("TEXT_MESSAGE_END"),
    TEXT_MESSAGE_CHUNK("TEXT_MESSAGE_CHUNK"),
    TOOL_CALL_START("TOOL_CALL_START"),
    TOOL_CALL_ARGS("TOOL_CALL_ARGS"),
    TOOL_CALL_END("TOOL_CALL_END"),
    TOOL_CALL_CHUNK("TOOL_CALL_CHUNK"),
    TOOL_CALL_RESULT("TOOL_CALL_RESULT"),
    REASONING_START("REASONING_START"),
    REASONING_END("REASONING_END"),
    REASONING_MESSAGE_START("REASONING_MESSAGE_START"),
    REASONING_MESSAGE_CONTENT("REASONING_MESSAGE_CONTENT"),
    REASONING_MESSAGE_END("REASONING_MESSAGE_END"),
    REASONING_MESSAGE_CHUNK("REASONING_MESSAGE_CHUNK"),
    REASONING_ENCRYPTED_VALUE("REASONING_ENCRYPTED_VALUE"),
    /** @deprecated 已被 {@link #REASONING_START} 取代 */
    @Deprecated
    THINKING_START("THINKING_START"),
    /** @deprecated 已被 {@link #REASONING_END} 取代 */
    @Deprecated
    THINKING_END("THINKING_END"),
    /** @deprecated 已被 {@link #REASONING_MESSAGE_START} 取代 */
    @Deprecated
    THINKING_TEXT_MESSAGE_START("THINKING_TEXT_MESSAGE_START"),
    /** @deprecated 已被 {@link #REASONING_MESSAGE_CONTENT} 取代 */
    @Deprecated
    THINKING_TEXT_MESSAGE_CONTENT("THINKING_TEXT_MESSAGE_CONTENT"),
    /** @deprecated 已被 {@link #REASONING_MESSAGE_END} 取代 */
    @Deprecated
    THINKING_TEXT_MESSAGE_END("THINKING_TEXT_MESSAGE_END"),
    STATE_SNAPSHOT("STATE_SNAPSHOT"),
    STATE_DELTA("STATE_DELTA"),
    MESSAGES_SNAPSHOT("MESSAGES_SNAPSHOT"),
    RAW("RAW"),
    CUSTOM("CUSTOM"),
    RUN_STARTED("RUN_STARTED"),
    RUN_FINISHED("RUN_FINISHED"),
    RUN_ERROR("RUN_ERROR"),
    STEP_STARTED("STEP_STARTED"),
    STEP_FINISHED("STEP_FINISHED");

    /** 事件类型编码 */
    private final String code;

    EventType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
