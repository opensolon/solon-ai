package features.ai.talents.toolgateway;

import demo.ai.talents.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.ai.talents.gateway.ToolGatewayTalent;
import org.noear.solon.annotation.Param;

public class ToolGatewayTalentTest {
    @Test
    public void case1() throws Exception {
        String rst = LlmUtil.getChatModel().prompt("杭州今天气如何？")
                .options(o -> {
                    o.talentAdd(new ToolGatewayTalent().addTool(new WeatherTool()).dynamicThreshold(0));
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