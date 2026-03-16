package features.ai.chat.tool;

import org.noear.solon.ai.annotation.ToolMapping;

import java.time.LocalDateTime;

/**
 *
 * @author noear 2026/3/13 created
 *
 */
public class ErrorTool {
    @ToolMapping(name = "currentTime", description = "获取系统当前时间")
    public String currentTime(){
        throw new RuntimeException("模拟异常");
    }
}
