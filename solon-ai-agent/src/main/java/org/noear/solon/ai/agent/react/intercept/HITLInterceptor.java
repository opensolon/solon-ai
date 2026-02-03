package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import java.util.*;

/**
 * 工业级人工介入拦截器
 */
public class HITLInterceptor implements ReActInterceptor {

    private final Map<String, InterventionStrategy> strategyMap = new HashMap<>();

    public HITLInterceptor onTool(String toolName, InterventionStrategy strategy) {
        strategyMap.put(toolName, strategy);
        return this;
    }

    public HITLInterceptor onSensitiveTool(String... toolNames) {
        HITLSensitiveStrategy toolDesc = new HITLSensitiveStrategy();
        for (String name : toolNames) onTool(name, toolDesc);
        return this;
    }

    @Override
    public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
        InterventionStrategy strategy = strategyMap.get(toolName);
        if(strategy == null){
            return;
        }

        String comment = strategy.evaluate(trace,args);

        if (comment == null) {
            return;
        }

        // 获取决策实体
        HITLDecision decision = trace.getContext().getAs(HITL.DECISION_PREFIX + toolName);

        // 1. 还没决策：挂起
        if (decision == null) {
            trace.getContext().put(HITL.LAST_INTERVENED, new HITLTask(toolName, new LinkedHashMap<>(args), comment));
            trace.interrupt(comment);
            return;
        }

        // 2. 已经有决策了：根据结果处理
        try {
            if (decision.isApproved()) {
                // 同意：检查是否有参数修正
                if (decision.getModifiedArgs() != null) {
                    args.putAll(decision.getModifiedArgs());
                }
                // 放行，让 Agent 继续执行工具调用
            } else {
                // 拒绝：直接结束或给 Observation
                String msg =decision.getCommentOrDefault();

                // 方案：设为 FinalAnswer 并结束，不执行工具
                trace.setFinalAnswer(msg);
                trace.setRoute(Agent.ID_END);
                // 抛出中断异常，防止工具被调用
                trace.interrupt(msg);
            }
        } finally {
            // 无论同意还是拒绝，执行完这一步都要清理现场
            trace.getContext().remove(HITL.DECISION_PREFIX + toolName);
            trace.getContext().remove(HITL.LAST_INTERVENED);
        }
    }

    @FunctionalInterface
    public interface InterventionStrategy {
        String evaluate(ReActTrace trace, Map<String, Object> args);
    }
}