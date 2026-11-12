package features.ai.talent.gateway.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.ai.talents.gateway.ToolGatewayTalent;
import org.noear.solon.ai.talents.gateway.openapi.ApiTool;

import java.util.Locale;

/**
 * 没有请求上下文的入口跟随默认语言；默认分组按默认语言登记、按请求语言展示
 */
public class GatewayI18nScenarioTest {
    @AfterEach
    public void restore() {
        AiI18n.setDefaultLocale(Locale.CHINESE);
    }

    @Test
    public void testDefaultLocaleEn() throws Throwable {
        AiI18n.setDefaultLocale(Locale.ENGLISH);

        ToolGatewayTalent tool = GatewayRenderGoldenTest.toolGateway(4);
        GoldenFiles.verify("gw_tool_description_en", tool.description(null));
        GoldenFiles.verify("gw_tool_search_blank_en", String.valueOf(tool.searchTools("")));
        GoldenFiles.verify("gw_tool_detail_missing_en", tool.getToolDetail("nope"));

        McpGatewayTalent mcp = GatewayRenderGoldenTest.mcpGateway(3);
        GoldenFiles.verify("gw_mcp_description_en", mcp.description(null));
        GoldenFiles.verify("gw_mcp_search_blank_en", String.valueOf(mcp.searchTools("")));
        GoldenFiles.verify("gw_mcp_detail_missing_en", mcp.getToolDetail("nope"));

        Assertions.assertEquals("General APIs", new ApiTool().getCategory());
        GoldenFiles.verify("gw_api_description_en",
                GatewayRenderGoldenTest.openApiGateway(GatewayRenderGoldenTest.plainApis(3)).description(null));
    }

    /**
     * 默认分组在默认语言为英文时登记，中文请求仍显示中文
     */
    @Test
    public void testDefaultCategoryFollowsRequest() throws Throwable {
        AiI18n.setDefaultLocale(Locale.ENGLISH);
        ToolGatewayTalent tool = GatewayRenderGoldenTest.toolGateway(2);
        AiI18n.setDefaultLocale(Locale.CHINESE);

        GoldenFiles.verify("gw_tool_instruction_2_zh", tool.getInstruction(GatewayRenderGoldenTest.prompt(null)));
        GoldenFiles.verify("gw_tool_instruction_2_en", tool.getInstruction(GatewayRenderGoldenTest.prompt("en")));
    }
}
