/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.noear.solon.ai.ui.agui.event;

import org.noear.solon.ai.ui.agui.EventType;

import java.util.ArrayList;
import java.util.List;

/**
 * AG-UI 状态增量事件，使用 JSON Patch 操作提供增量状态变更。
 */
public class StateDeltaEvent extends Event {
    /** RFC 6902 JSON Patch 操作列表，对应协议中的 delta 字段。 */
    private List<JsonPatchOperation> delta;

    public StateDeltaEvent() {
        super(EventType.STATE_DELTA);
    }

    public StateDeltaEvent(List<JsonPatchOperation> delta) {
        this();
        setDelta(delta);
    }

    public List<JsonPatchOperation> getDelta() {
        return delta;
    }

    public void setDelta(List<JsonPatchOperation> delta) {
        this.delta = delta;
    }

    public StateDeltaEvent add(JsonPatchOperation operation) {
        if (delta == null) {
            delta = new ArrayList<>();
        }
        if (operation != null) {
            delta.add(operation);
        }
        return this;
    }
}
