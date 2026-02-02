package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import java.util.*;

/**
 * 工业级人工介入拦截器
 * <p>支持策略路由、动态风险评估与上下文注入</p>
 */
public class HITLInterceptor implements ReActInterceptor {

    private final Map<String, InterventionStrategy> strategyMap = new HashMap<>();
    private final String approvalStatusKey = "_hitl_approved_";

    /**
     * 为特定工具注册介入策略
     */
    public HITLInterceptor onTool(String toolName, InterventionStrategy strategy) {
        strategyMap.put(toolName, strategy);
        return this;
    }

    /**
     * 快捷注册：高危工具（始终拦截）
     */
    public HITLInterceptor onSensitiveTool(String... toolNames) {
        for (String name : toolNames) {
            onTool(name, (trace, args) -> true);
        }
        return this;
    }

    @Override
    public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
        InterventionStrategy strategy = strategyMap.get(toolName);

        // 1. 匹配策略：如果没有配置或策略判断无需介入，则直接放行
        if (strategy == null || !strategy.shouldIntervene(trace, args)) {
            return;
        }

        // 2. 检查审批状态 (支持基于 ToolName 的精细化审批)
        String specificKey = approvalStatusKey + toolName;
        Boolean isApproved = trace.getContext().getAs(specificKey);

        if (isApproved == null || !isApproved) {
            // 3. 构建介入上下文（供 UI 或审计使用）
            // 模仿 AgentSpace：不仅中断，还需保留当时的参数快照
            trace.getContext().put("_last_intervened_tool_", toolName);
            trace.getContext().put("_last_intervened_args_", args);

            // 4. 执行挂起
            // 这里的 reason 可以是一个 JSON，包含更丰富的元数据
            trace.interrupt("REQUIRED_HUMAN_APPROVAL:" + toolName);
        } else {
            // 5. 审批通过后的清理工作
            Map<String, Object> modifiedArgs = trace.getContext().getAs("_modified_args_" + toolName);
            if (modifiedArgs != null) {
                args.putAll(modifiedArgs); // 用人工修改的参数覆盖 AI 生成的参数
                trace.getContext().remove("_modified_args_" + toolName);
            }

            trace.getContext().remove(specificKey);
            trace.getContext().remove("_last_intervened_tool_");
            trace.getContext().remove("_last_intervened_args_");
        }
    }

    /**
     * 策略接口：支持动态判定
     * 例如：转账工具，金额 > 1000 才拦截
     */
    @FunctionalInterface
    public interface InterventionStrategy {
        boolean shouldIntervene(ReActTrace trace, Map<String, Object> args);
    }
}