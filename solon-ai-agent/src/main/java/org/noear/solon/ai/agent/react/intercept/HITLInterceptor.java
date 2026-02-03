package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.Agent;
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
        if (strategy == null || !strategy.shouldIntervene(trace, args)) {
            return;
        }

        String specificKey = HITL.KEY_PREFIX + toolName;
        String rejectKey = HITL.REJECT_PREFIX + toolName; // 获取拒绝 Key

        Boolean isApproved = trace.getContext().getAs(specificKey);
        Boolean isRejected = trace.getContext().getAs(rejectKey);

        // 1. 处理拒绝逻辑
        if (Boolean.TRUE.equals(isRejected)) {
            // 给 AI 一个反馈，让它知道被拒绝了，它会据此回复用户
            trace.setFinalAnswer("操作被拒绝：人工审批未通过。");
            trace.setRoute(Agent.ID_END);
            trace.interrupt("操作被拒绝：人工审批未通过。");

            // 清理现场
            trace.getContext().remove(rejectKey);
            trace.getContext().remove(HITL.LAST_INTERVENED);

            // 注意：这里不需要 interrupt，直接让流程继续走，AI 会看到 Observation

            return;
        }

        // 2. 处理挂起逻辑
        if (isApproved == null || !isApproved) {
            trace.getContext().put(HITL.LAST_INTERVENED, new HITLTask(toolName, new LinkedHashMap<>(args)));
            trace.interrupt("REQUIRED_HUMAN_APPROVAL:" + toolName);
        }
        // 3. 处理审批通过逻辑
        else {
            Map<String, Object> modifiedArgs = trace.getContext().getAs(HITL.ARGS_PREFIX + toolName);
            if (modifiedArgs != null) {
                args.putAll(modifiedArgs);
                trace.getContext().remove(HITL.ARGS_PREFIX + toolName);
            }

            trace.getContext().remove(specificKey);
            trace.getContext().remove(HITL.LAST_INTERVENED);
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