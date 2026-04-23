package features.ai.toolgateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.skills.toolgateway.ToolGatewaySkill;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具网关技能单元测试
 * 覆盖：FULL模式、SUMMARY模式、LIST模式、SEARCH模式、搜索引导、代理执行
 */
public class ToolGatewaySkillTest {
    private ToolGatewaySkill gatewaySkill;
    private List<FunctionTool> mockTools;

    @BeforeEach
    void setUp() {
        mockTools = new ArrayList<>();
        // 准备 3 个基础工具用于日常测试
        mockTools.add(createMockTool("adder", "执行加法计算", "{\"type\":\"object\"}"));
        mockTools.add(createMockTool("weather_report", "查询城市天气预报", "{\"type\":\"object\"}"));
        mockTools.add(createMockTool("stock_price", "查询实时股票价格", "{\"type\":\"object\"}"));

        gatewaySkill = new ToolGatewaySkill();
        for (FunctionTool tool : mockTools) {
            gatewaySkill.addTool(tool);
        }
    }

    @Test
    @DisplayName("测试 FULL 模式：工具少于阈值，直接平铺定义")
    void testFullMode() {
        gatewaySkill.dynamicThreshold(5); // 3 < 5

        Collection<FunctionTool> tools = gatewaySkill.getTools(Prompt.of(""));

        // 断言：FULL模式下直接暴露业务工具
        assertEquals(3, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("adder")));

        String instruction = gatewaySkill.getInstruction(Prompt.of(""));
        assertTrue(instruction.contains("直接调用"), "FULL模式指令应提示直接调用");
    }

    @Test
    @DisplayName("测试 SUMMARY 模式：工具较多，展示摘要清单并开启代理")
    void testSummaryMode() {
        gatewaySkill.dynamicThreshold(1);  // 3 > 1，超过 dynamicThreshold
        gatewaySkill.listThreshold(5);     // 3 < 5，未超过 listThreshold → SUMMARY 模式

        Collection<FunctionTool> tools = gatewaySkill.getTools(Prompt.of(""));

        // 断言：业务工具被物理屏蔽，只剩下网关管理工具
        assertFalse(tools.stream().anyMatch(t -> t.name().equals("adder")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("call_tool")));
        // SUMMARY 模式下不过滤 search_tools，但 SUMMARY 不提供它（因为清单已展示）
        assertFalse(tools.stream().anyMatch(t -> t.name().equals("search_tools")),
                "SUMMARY 模式下应过滤掉 search_tools");

        String instruction = gatewaySkill.getInstruction(Prompt.of(""));
        assertTrue(instruction.contains("可用业务工具清单"), "SUMMARY模式应展示工具清单摘要");
        assertTrue(instruction.contains("动态发现 (摘要)"), "应标明动态发现摘要模式");
        assertTrue(instruction.contains("get_tool_detail"), "应引导AI走详情查询流程");
    }

    @Test
    @DisplayName("测试 LIST 模式：工具规模较大，仅展示名字列表")
    void testListMode() {
        gatewaySkill.dynamicThreshold(1);  // 3 > 1
        gatewaySkill.listThreshold(2);     // 3 > 2，超过 listThreshold
        gatewaySkill.searchThreshold(5);   // 3 < 5，未超过 searchThreshold → LIST 模式

        Collection<FunctionTool> tools = gatewaySkill.getTools(Prompt.of(""));

        // 断言：LIST 模式提供完整中转工具集（包含 search_tools）
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("call_tool")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("search_tools")),
                "LIST 模式应提供 search_tools");

        String instruction = gatewaySkill.getInstruction(Prompt.of(""));
        assertTrue(instruction.contains("动态发现 (列表)"), "LIST 模式应标明动态发现列表模式");
        assertTrue(instruction.contains("search_tools"), "应引导使用 search_tools");
        assertTrue(instruction.contains("get_tool_detail"), "应引导走详情查询流程");
    }

    @Test
    @DisplayName("测试 SEARCH 模式：工具海量，折叠清单强制搜索")
    void testSearchMode() {
        gatewaySkill.dynamicThreshold(1);
        gatewaySkill.listThreshold(2);
        gatewaySkill.searchThreshold(2); // 3 > 2，触发 SEARCH 模式

        String instruction = gatewaySkill.getInstruction(Prompt.of(""));

        // 断言：清单被折叠
        assertTrue(instruction.contains("搜索发现"), "SEARCH模式应提示搜索发现");
        assertTrue(instruction.contains("search_tools"), "应强调使用搜索工具");
        assertTrue(instruction.contains("清单已折叠"), "SEARCH模式应提示清单已折叠");
    }

    @Test
    @DisplayName("测试模糊搜索功能：关键词匹配")
    void testSearchTools() {
        // 搜索 "weather" 应该匹配到 "weather_report"
        Object result = gatewaySkill.searchTools("weather");

        assertTrue(result instanceof List);
        List<Map<String, String>> list = (List<Map<String, String>>) result;
        assertEquals(1, list.size());
        assertEquals("weather_report", list.get(0).get("tool_name"));

        // 搜索不存在的关键词
        Object emptyResult = gatewaySkill.searchTools("unknown_keyword");
        assertTrue(emptyResult instanceof String);
        assertTrue(emptyResult.toString().contains("未找到"), "搜索为空时应返回未找到提示");
    }

    @Test
    @DisplayName("测试工具详情查询")
    void testGetToolDetail() {
        String detail = gatewaySkill.getToolDetail("adder");

        assertNotNull(detail);
        assertTrue(detail.contains("业务工具详情: adder"), "详情标题应包含业务工具详情");
        assertTrue(detail.contains("执行加法计算"));
        assertTrue(detail.contains("```json"), "详情应包含 JSON 代码块引导");
    }

    @Test
    @DisplayName("测试代理执行业务工具")
    void testCallTool() {
        Map<String, Object> args = new HashMap<>();
        args.put("a", 100);

        ToolResult result = gatewaySkill.callTool("adder", args);

        assertEquals("adder_executed_with_100", result.getContent());
    }

    @Test
    @DisplayName("测试调用逻辑：大小写不敏感")
    void testCaseInsensitivity() {
        // 注册时是小写，调用时用大写
        ToolResult result = gatewaySkill.callTool("ADDER", new HashMap<>());
        assertFalse(result.getContent().contains("未找到工具"));
    }

    // --- 辅助方法 ---
    private FunctionTool createMockTool(String name, String desc, String schema) {
        return new FunctionToolDesc(name)
                .description(desc)
                .inputSchema(schema)
                .doHandle(args -> {
                    Object val = args.getOrDefault("a", "none");
                    return name + "_executed_with_" + val;
                });
    }
}
