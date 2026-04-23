package features.ai.skills.toolgateway;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.ai.skills.toolgateway.ToolGatewaySkill;
import org.noear.solon.annotation.Param;

public class ToolGatewaySkillTest {
    @Test
    public void case1() throws Exception {
        String rst = LlmUtil.getChatModel().prompt("杭州今天气如何？")
                .options(o -> {
                    o.skillAdd(new ToolGatewaySkill().addTool(new WeatherTool()).dynamicThreshold(0));
                    o.toolContextPut("oc", 12);
                }).call().getContent();

        System.out.println(rst);
        assert rst.contains("12");
    }

    public static class WeatherTool extends AbsToolProvider {
        @ToolMapping(description = "天气查询")
        public String weather(@Param(description = "城市") String city, int oc) {
            return city + " " + oc + "度";
        }
    }
}