package lab.ai.a2a;

/**
 * 智能体任务处理器
 *
 * @author noear
 * @since 3.5
 */
public interface AgentTaskHandler {
    /**
     * 智能体任务处理
     */
    String handleTask(String message) throws Throwable;
}
