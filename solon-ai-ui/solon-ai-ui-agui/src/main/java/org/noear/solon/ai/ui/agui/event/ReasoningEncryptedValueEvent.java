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
package org.noear.solon.ai.ui.agui.event;

import org.noear.solon.ai.ui.agui.EventType;

/**
 * AG-UI 推理加密值事件，将加密的思维链附加到消息或工具调用上，用于隐私合规
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/reasoning#reasoning-events">AG-UI ReasoningEncryptedValue</a>
 */
public class ReasoningEncryptedValueEvent extends Event {
    /** 子类型标识 */
    private String subtype;
    /** 关联的实体标识（消息或工具调用） */
    private String entityId;
    /** 加密后的思维链值 */
    private String encryptedValue;

    public ReasoningEncryptedValueEvent() {
        super(EventType.REASONING_ENCRYPTED_VALUE);
    }

    public String getSubtype() {
        return subtype;
    }

    public void setSubtype(String subtype) {
        this.subtype = subtype;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getEncryptedValue() {
        return encryptedValue;
    }

    public void setEncryptedValue(String encryptedValue) {
        this.encryptedValue = encryptedValue;
    }
}
