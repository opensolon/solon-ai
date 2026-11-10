package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSystemPrompt;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import reactor.core.publisher.FluxSink;

import java.util.Locale;
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

    /** 智能体职责描述（用于模型识别角色） */
    private String role;
    /** 智能体指令描述（用于模型识别指令） */
    private String instruction;
    /**
     * 系统提示词模板（支持动态注入上下文）
     */
    private AgentSystemPrompt<SimpleTrace> systemPrompt = SimpleSystemPrompt.getDefault();

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

    public String getRole() {
        return role;
    }

    public String getInstruction() {
        return instruction;
    }

    public Locale getLocale() {
        return systemPrompt.getLocale();
    }

    public String getSystemPromptFor(SimpleTrace trace, FlowContext context) {
        String raw = systemPrompt.getSystemPrompt(trace);
        if (context == null || raw == null) {
            return raw;
        }

        // 动态渲染模板（如解析 #{user_name}）
        String rendered = SnelUtil.render(raw, context.vars());

        return rendered;
    }

    //------------

    /**
     * 设置角色（请求级动态修正）
     */
    public SimpleOptions role(String role) {
        Assert.notNull(role, "role");
        this.role = role;
        return this;
    }

    /**
     * 设置指令（请求级动态修正）
     */
    public SimpleOptions instruction(String instruction) {
        Assert.notNull(instruction, "instruction");
        this.instruction = instruction;
        return this;
    }

    /**
     * 设置系统提示词模板（请求级动态修正；整体替换，非追加）
     */
    public SimpleOptions systemPrompt(AgentSystemPrompt<SimpleTrace> systemPrompt) {
        Assert.notNull(systemPrompt, "systemPrompt");
        this.systemPrompt = systemPrompt;
        return this;
    }

    /**
     * 设置请求级属性（仅框架与拦截器可见，不会并入工具调用参数；与 toolContext 不同）
     *
     * @since 4.2.0
     */
    public SimpleOptions attrPut(String key, Object value) {
        Assert.notNull(key, "key");
        setAttr(key, value);
        return this;
    }

    protected void setRole(String role) {
        this.role = role;
    }

    protected void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    protected void setSystemPrompt(AgentSystemPrompt<SimpleTrace> systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

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

        // putAll 只覆盖父类字段，子类字段必须单独拷贝
        tmp.attrs.putAll(this.attrs);
        tmp.role = this.role;
        tmp.instruction = this.instruction;
        tmp.systemPrompt = this.systemPrompt;

        //tmp.streamSink = streamSink;

        return tmp;
    }
}