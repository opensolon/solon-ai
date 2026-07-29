package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.lang.Preview;
import reactor.core.publisher.FluxSink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单智能体运行选项
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SimpleOptions extends ModelOptionsAmend<SimpleOptions, SimpleInterceptor> {
    private final Map<String, Object> attrs = new ConcurrentHashMap<>();

    /**
     * @since 4.0.4
     */
    public Map<String, Object> getAttrs() {
        return attrs;
    }

    public Object getAttr(String key) {
        return attrs.get(key);
    }

    public <T> T getAttrAs(String key) {
        return (T) attrs.get(key);
    }

    public void setAttr(String key, Object val) {
        attrs.put(key, val);
    }

    //------------

    private transient FluxSink<AgentEvent> streamSink;

    protected void setStreamSink(FluxSink<AgentEvent> streamSink) {
        this.streamSink = streamSink;
    }

    public FluxSink<AgentEvent> getStreamSink() {
        return streamSink;
    }

    protected SimpleOptions copy() {
        SimpleOptions tmp = new SimpleOptions();
        tmp.putAll(this);

        //tmp.streamSink = streamSink;

        return tmp;
    }
}