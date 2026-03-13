package features.ai.chat.tool;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Mapping;

import java.time.LocalDateTime;

/**
 *
 * @author noear 2026/3/13 created
 *
 */
public class TimeTool {
    @ToolMapping(name = "currentTime", description = "获取系统当前时间")
    public String currentTime(){
        return LocalDateTime.now().toString();
    }
}
