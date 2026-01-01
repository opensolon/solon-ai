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
package org.noear.solon.ai.agent.react;

import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowException;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.intercept.FlowInvocation;
import org.noear.solon.lang.Preview;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ReAct 拦截器简单实现
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class SimpleReActInterceptor implements ReActInterceptor {
    private final Consumer<FlowInvocation> doIntercept;
    private final BiConsumer<FlowContext, Node> onNodeStart;
    private final BiConsumer<FlowContext, Node> onNodeEnd;
    private final BiConsumer<ReActTrace, String> onThought;
    private final TrConsumer<ReActTrace, String, Map<String, Object>> onAction;
    private final BiConsumer<ReActTrace, String> onObservation;

    public SimpleReActInterceptor(Consumer<FlowInvocation> doIntercept,
                                  BiConsumer<FlowContext, Node> onNodeStart,
                                  BiConsumer<FlowContext, Node> onNodeEnd,
                                  BiConsumer<ReActTrace, String> onThought,
                                  TrConsumer<ReActTrace, String, Map<String, Object>> onAction,
                                  BiConsumer<ReActTrace, String> onObservation) {
        this.doIntercept = doIntercept;
        this.onNodeStart = onNodeStart;
        this.onNodeEnd = onNodeEnd;
        this.onThought = onThought;
        this.onAction = onAction;
        this.onObservation = onObservation;
    }

    @Override
    public void doIntercept(FlowInvocation invocation) throws FlowException {
        if (doIntercept != null) {
            doIntercept.accept(invocation);
        } else {
            invocation.invoke();
        }
    }

    @Override
    public void onNodeStart(FlowContext context, Node node) {
        if (onNodeStart != null) {
            onNodeStart.accept(context, node);
        }
    }

    @Override
    public void onNodeEnd(FlowContext context, Node node) {
        if (onNodeEnd != null) {
            onNodeEnd.accept(context, node);
        }
    }


    @Override
    public void onThought(ReActTrace record, String thought) {
        if (onThought != null) {
            onThought.accept(record, thought);
        }
    }

    @Override
    public void onAction(ReActTrace record, String toolName, Map<String, Object> args) {
        if (onAction != null) {
            onAction.accept(record, toolName, args);
        }
    }

    @Override
    public void onObservation(ReActTrace record, String result) {
        if (onObservation != null) {
            onObservation.accept(record, result);
        }
    }
}