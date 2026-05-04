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

import java.util.HashMap;
import java.util.Map;

/**
 * AG-UI 协议键值状态容器，用于在 Agent 与 UI 之间同步状态
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/events#statesnapshot">AG-UI State Management</a>
 */
public class State {
    /** 状态键值存储 */
    private final Map<String, Object> stateMap;

    public State() {
        this.stateMap = new HashMap<>();
    }

    public Object get(String key) {
        return stateMap.get(key);
    }

    public void set(String key, Object value) {
        stateMap.put(key, value);
    }

    public Map<String, Object> getState() {
        return stateMap;
    }
}
