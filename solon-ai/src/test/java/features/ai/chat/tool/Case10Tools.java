package features.ai.chat.tool;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;

/**
 * @author noear 2025/5/15 created
 */
public class Case10Tools extends AbsToolProvider {
    @ToolMapping(description = "杭州的假日景点介绍")
    public String spotIntro() {
        return "西湖，良渚遗址";
    }
}
