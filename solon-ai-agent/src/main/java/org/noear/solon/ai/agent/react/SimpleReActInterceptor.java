package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.util.TrConsumer;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowException;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.intercept.FlowInvocation;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class SimpleReActInterceptor implements ReActInterceptor {
    private final Consumer<FlowInvocation> doIntercept;
    private final BiConsumer<FlowContext, Node> onNodeStart;
    private final BiConsumer<FlowContext, Node> onNodeEnd;
    private final BiConsumer<ReActRecord, String> onThought;
    private final TrConsumer<ReActRecord, String, Map<String, Object>> onAction;
    private final BiConsumer<ReActRecord, String> onObservation;

    public SimpleReActInterceptor(Consumer<FlowInvocation> doIntercept,
                                  BiConsumer<FlowContext, Node> onNodeStart,
                                  BiConsumer<FlowContext, Node> onNodeEnd,
                                  BiConsumer<ReActRecord, String> onThought,
                                  TrConsumer<ReActRecord, String, Map<String, Object>> onAction,
                                  BiConsumer<ReActRecord, String> onObservation) {
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
    public void onThought(ReActRecord record, String thought) {
        if (onThought != null) {
            onThought.accept(record, thought);
        }
    }

    @Override
    public void onAction(ReActRecord record, String toolName, Map<String, Object> args) {
        if (onAction != null) {
            onAction.accept(record, toolName, args);
        }
    }

    @Override
    public void onObservation(ReActRecord record, String result) {
        if (onObservation != null) {
            onObservation.accept(record, result);
        }
    }


    /// ///////////////////

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Consumer<FlowInvocation> doIntercept;
        private BiConsumer<FlowContext, Node> onNodeStart;
        private BiConsumer<FlowContext, Node> onNodeEnd;
        private BiConsumer<ReActRecord, String> onThought;
        private TrConsumer<ReActRecord, String, Map<String, Object>> onAction;
        private BiConsumer<ReActRecord, String> onObservation;

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

        public Builder onThought(BiConsumer<ReActRecord, String> onThought) {
            this.onThought = onThought;
            return this;
        }

        public Builder onAction(TrConsumer<ReActRecord, String, Map<String, Object>> onAction) {
            this.onAction = onAction;
            return this;
        }

        public Builder onObservation(BiConsumer<ReActRecord, String> onObservation) {
            this.onObservation = onObservation;
            return this;
        }

        public SimpleReActInterceptor build() {
            return new SimpleReActInterceptor(doIntercept,
                    onNodeStart,
                    onNodeEnd,
                    onThought,
                    onAction,
                    onObservation);
        }
    }
}