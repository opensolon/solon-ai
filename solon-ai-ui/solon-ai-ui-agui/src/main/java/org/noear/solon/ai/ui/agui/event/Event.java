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
import org.noear.solon.core.util.Assert;

/**
 * AG-UI 协议事件抽象基类
 * <p>
 * 所有事件共享基础属性：type（事件类型）、timestamp（时间戳）、rawEvent（原始载荷）。
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/events#base-event-properties">AG-UI Base Event</a>
 */
public abstract class Event {
    /** 事件类型 */
    private final EventType type;
    /** 事件时间戳（毫秒） */
    private long timestamp;
    /** 原始事件载荷 */
    private Object rawEvent;

    protected Event(EventType type) {
        Assert.notNull(type, "EventType must not be null");
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public EventType getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Object getRawEvent() {
        return rawEvent;
    }

    public void setRawEvent(Object rawEvent) {
        this.rawEvent = rawEvent;
    }
}
