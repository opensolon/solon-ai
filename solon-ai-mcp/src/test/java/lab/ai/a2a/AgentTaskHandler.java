package lab.ai.a2a;

import org.noear.solon.annotation.Param;

/**
 * @author noear 2025/8/31 created
 */
public interface AgentTaskHandler {
    String handleTask(@Param(name = "message") String message) throws Throwable;
}
