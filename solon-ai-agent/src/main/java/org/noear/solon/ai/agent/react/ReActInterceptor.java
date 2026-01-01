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
import org.noear.solon.flow.Node;
import org.noear.solon.flow.intercept.FlowInterceptor;
import org.noear.solon.flow.intercept.FlowInvocation;
import org.noear.solon.lang.Preview;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ReAct 拦截器
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface ReActInterceptor extends FlowInterceptor {
    /**
     * 思考时触发
     */
    default void onThought(ReActTrace record, String thought) {
    }

    /**
     * 调用工具前触发
     */
    default void onAction(ReActTrace record, String toolName, Map<String, Object> args) {
    }

    /**
     * 工具返回结果后触发
     */
    default void onObservation(ReActTrace record, String result) {
    }


    /// ///////////////////////

    /**
     * 创建一个默认的拦截器
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Consumer<FlowInvocation> doIntercept;
        private BiConsumer<FlowContext, Node> onNodeStart;
        private BiConsumer<FlowContext, Node> onNodeEnd;
        private BiConsumer<ReActTrace, String> onThought;
        private TrConsumer<ReActTrace, String, Map<String, Object>> onAction;
        private BiConsumer<ReActTrace, String> onObservation;

        public Builder doIntercept(Consumer<FlowInvocation> doIntercept) {
            this.doIntercept = doIntercept;
            return this;
        }

        public Builder onNodeStart(BiConsumer<FlowContext, Node> onNodeStart) {
            this.onNodeStart = onNodeStart;
            return this;
        }

        public Builder onNodeEnd(BiConsumer<FlowContext, Node> onNodeEnd) {
            this.onNodeEnd = onNodeEnd;
            return this;
        }

        public Builder onThought(BiConsumer<ReActTrace, String> onThought) {
            this.onThought = onThought;
            return this;
        }

        public Builder onAction(TrConsumer<ReActTrace, String, Map<String, Object>> onAction) {
            this.onAction = onAction;
            return this;
        }

        public Builder onObservation(BiConsumer<ReActTrace, String> onObservation) {
            this.onObservation = onObservation;
            return this;
        }

        public ReActInterceptor build() {
            return new SimpleReActInterceptor(doIntercept,
                    onNodeStart,
                    onNodeEnd,
                    onThought,
                    onAction,
                    onObservation);
        }
    }


    @FunctionalInterface
    public static interface TrConsumer<T, U, X> {
        void accept(T t, U u, X x);

        default TrConsumer<T, U, X> andThen(TrConsumer<? super T, ? super U, ? super X> after) {
            Objects.requireNonNull(after);

            return (l, r, x) -> {
                accept(l, r, x);
                after.accept(l, r, x);
            };
        }
    }
}