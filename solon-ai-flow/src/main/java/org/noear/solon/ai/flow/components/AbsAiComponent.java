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
package org.noear.solon.ai.flow.components;

import org.noear.solon.ai.flow.events.Events;
import org.noear.solon.ai.flow.events.NodeEvent;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * Ai 虚拟组件（基类）
 *
 * @author noear
 * @since 3.3
 */
public abstract class AbsAiComponent implements AiComponent {
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        context.eventBus().send(Events.EVENT_FLOW_NODE_START, new NodeEvent(context, node));
        doRun(context, node);
        context.eventBus().send(Events.EVENT_FLOW_NODE_END, new NodeEvent(context, node));
    }

    protected abstract void doRun(FlowContext context, Node node) throws Throwable;
}
