package org.noear.solon.ai.skills.sys;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import java.util.Collections;

public class NodejsSkill extends AbsProcessSkill {
    public NodejsSkill(String workDir) { super(workDir); }

    @Override public String name() { return "nodejs_executor"; }
    @Override public String description() { return "Node.js 专家：执行 JS 代码，处理 JSON 或 Web 逻辑。"; }

    @Override
    public boolean isSupported(Prompt prompt) {
        return prompt.getUserContent().toLowerCase().matches(".*(nodejs|javascript|js|npm).*");
    }

    @ToolMapping(name = "execute_js", description = "执行 Node.js 代码")
    public String execute(@Param("code") String code) {
        return runCode(code, "node", ".js", Collections.singletonMap("NODE_SKIP_PLATFORM_CHECK", "1"));
    }
}