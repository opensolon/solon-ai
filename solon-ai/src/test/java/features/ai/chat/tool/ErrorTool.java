package features.ai.chat.tool;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;

import java.time.LocalDateTime;

/**
 *
 * @author noear 2026/3/13 created
 *
 */
public class ErrorTool extends AbsToolProvider {
    @ToolMapping(name = "currentTime", description = "获取系统当前时间")
    public String currentTime(){
        throw new RuntimeException("模拟异常");
    }
}
