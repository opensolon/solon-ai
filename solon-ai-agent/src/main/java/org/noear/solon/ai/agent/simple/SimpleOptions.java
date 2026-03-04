package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.lang.Preview;
import reactor.core.publisher.FluxSink;

/**
 * 简单智能体运行选项
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SimpleOptions extends ModelOptionsAmend<SimpleOptions, SimpleInterceptor> {
    private transient FluxSink<AgentChunk> streamSink;

    protected void setStreamSink(FluxSink<AgentChunk> streamSink) {
        this.streamSink = streamSink;
    }

    public FluxSink<AgentChunk> getStreamSink() {
        return streamSink;
    }

    protected SimpleOptions copy() {
        SimpleOptions tmp = new SimpleOptions();
        tmp.putAll(this);

        //tmp.streamSink = streamSink;

        return tmp;
    }
}