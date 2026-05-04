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

import java.util.Map;

/**
 * AG-UI 协议中断描述，当 Agent 需要用户输入或确认时暂停运行
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/interrupts">AG-UI Interrupts</a>
 */
public class Interrupt {
    /** 中断唯一标识，用于关联恢复、幂等和审计 */
    private String id;
    /** 中断原因分类（如 "tool_call"、"input_required"、"confirmation"） */
    private String reason;
    /** 人类可读的提示信息（可选） */
    private String message;
    /** 关联的工具调用标识（可选） */
    private String toolCallId;
    /** 期望恢复载荷的 JSON Schema（可选） */
    private Object responseSchema;
    /** 过期时间，ISO-8601 格式（可选） */
    private String expiresAt;
    /** 框架自定义的附加元数据（可选） */
    private Map<String, Object> metadata;

    public Interrupt() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public Object getResponseSchema() {
        return responseSchema;
    }

    public void setResponseSchema(Object responseSchema) {
        this.responseSchema = responseSchema;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
