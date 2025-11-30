package features.agent;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

public class Tools {
    @ToolMapping(description = "求和工具")
    public int sum(@Param int a, @Param int b) {
        return a + b;
    }
}