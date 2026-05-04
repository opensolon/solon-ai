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
import org.noear.solon.ai.ui.agui.State;

/**
 * AG-UI 状态快照事件，传递 Agent 当前状态的完整表示以进行全量同步
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/events#statesnapshot">AG-UI StateSnapshot</a>
 */
public class StateSnapshotEvent extends Event {
    /** Agent 当前状态 */
    private State state;

    public StateSnapshotEvent() {
        super(EventType.STATE_SNAPSHOT);
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }
}
