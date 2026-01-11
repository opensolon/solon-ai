package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;

import java.util.Collection;
import java.util.Map;

/**
 *
 * @author noear
 * @since 3.8.1
 */
public class ReActOptionsAmend {
    private final ReActOptions options;

    public ReActOptionsAmend(ReActOptions options) {
        this.options = options;
    }

    /**
     * 添加单个功能工具
     */
    public ReActOptionsAmend toolAdd(FunctionTool... tools) {
        options.addTool(tools);
        return this;
    }

    /**
     * 批量添加功能工具
     */
    public ReActOptionsAmend toolAdd(Collection<FunctionTool> tools) {
        options.addTool(tools);
        return this;
    }

    /**
     * 通过 ToolProvider 注入工具集
     */
    public ReActOptionsAmend toolAdd(ToolProvider toolProvider) {
        options.addTool(toolProvider);
        return this;
    }

    /**
     * 添加工具调用上下文
     */
    public ReActOptionsAmend toolsContext(Map<String, Object> toolsContext) {
        options.addToolsContext(toolsContext);
        return this;
    }

    /**
     * 配置重试策略
     *
     * @param maxRetries   最大重试次数
     * @param retryDelayMs 重试延迟时间（毫秒）
     */
    public ReActOptionsAmend retryConfig(int maxRetries, long retryDelayMs) {
        options.setRetryConfig(maxRetries, retryDelayMs);
        return this;
    }

    public ReActOptionsAmend outputKey(String val) {
        options.setOutputKey(val);
        return this;
    }

    public ReActOptionsAmend outputSchema(String val) {
        options.setOutputSchema(val);
        return this;
    }

    /**
     * 设置历史消息窗口大小
     *
     * @param historyWindowSize 回溯的消息条数（建议设置为奇数以保持对话轮次完整）
     */
    public ReActOptionsAmend historyWindowSize(int historyWindowSize) {
        options.setHistoryWindowSize(historyWindowSize);
        return this;
    }

    public ReActOptionsAmend maxSteps(int val) {
        options.setMaxSteps(val);
        return this;
    }

    /**
     * 添加拦截器
     */
    public ReActOptionsAmend interceptorAdd(ReActInterceptor val) {
        options.addInterceptor(val);
        return this;
    }

    /**
     * 添加拦截器并指定优先级
     */
    public ReActOptionsAmend interceptorAdd(ReActInterceptor val, int index) {
        options.addInterceptor(val, index);
        return this;
    }
}