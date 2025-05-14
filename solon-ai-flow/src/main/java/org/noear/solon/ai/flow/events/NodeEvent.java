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
package org.noear.solon.ai.flow.events;

import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 节点事件
 *
 * @author noear
 * @since 3.3
 */
public class NodeEvent {
    private final FlowContext context;
    private final Node node;

    public NodeEvent(FlowContext context, Node node) {
        this.context = context;
        this.node = node;
    }

    public FlowContext getContext() {
        return context;
    }

    public Node getNode() {
        return node;
    }
}
