package features.ai.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolGatewaySkill;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ToolGatewaySkillTest {
    private ToolGatewaySkill gatewaySkill;
    private List<FunctionTool> mockTools;

    @BeforeEach
    void setUp() {
        // 模拟一些业务工具
        mockTools = new ArrayList<>();

        // 工具 1: 简易加法器
        mockTools.add(createMockTool("adder", "执行加法计算", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"}}}"));
        // 工具 2: 天气查询
        mockTools.add(createMockTool("weather", "查询城市天气", "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"));

        // 初始化网关
        gatewaySkill = new ToolGatewaySkill(); // 此处传 null，通过 addTool 手动添加
        for (FunctionTool tool : mockTools) {
            gatewaySkill.addTool(tool);
        }
    }

    /**
     * 测试 FULL 模式：工具数量少于阈值，直接返回业务工具定义
     */
    @Test
    void testFullMode() {
        gatewaySkill.dynamicThreshold(5); // 阈值为 5，当前只有 2 个工具

        Collection<FunctionTool> tools = gatewaySkill.getTools(Prompt.of(""));

        // 断言：返回的是业务工具本身，不包含网关管理工具（如 call_tool）
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("adder")));

        String instruction = gatewaySkill.getInstruction(Prompt.of(""));
        assertTrue(instruction.contains("直接按需调用"));
    }

    /**
     * 测试 DYNAMIC 模式：工具数量触发阈值，进入按需发现模式
     */
    @Test
    void testDynamicMode() {
        gatewaySkill.dynamicThreshold(1); // 阈值为 1，触发 DYNAMIC 模式

        Collection<FunctionTool> tools = gatewaySkill.getTools(Prompt.of(""));

        // 断言：getTools 此时返回的是网关内部的管理工具 (get_tool_detail, call_tool, get_tool_list)
        // 注意：AbsSkill 内部的 tools 是通过反射解析当前类的 @ToolMapping 得到的
        assertFalse(tools.stream().anyMatch(t -> t.name().equals("adder")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("call_tool")));

        String instruction = gatewaySkill.getInstruction(Prompt.of(""));
        assertTrue(instruction.contains("开启**按需发现**模式"));
        assertTrue(instruction.contains("call_tool"));
    }

    /**
     * 测试工具详情查询
     */
    @Test
    void testGetToolDetail() {
        String detail = gatewaySkill.getToolDetail("adder");

        assertNotNull(detail);
        assertTrue(detail.contains("### 工具详情: adder"));
        assertTrue(detail.contains("执行加法计算"));
        assertTrue(detail.contains("JSON Schema"));
    }

    /**
     * 测试工具清单查询
     */
    @Test
    void testGetToolList() {
        List<Map<String, String>> list = gatewaySkill.getToolList();

        assertEquals(2, list.size());
        assertEquals("adder", list.get(0).get("tool_name"));
    }

    /**
     * 测试通过 call_tool 代理执行业务工具
     */
    @Test
    void testCallTool() {
        Map<String, Object> args = new HashMap<>();
        args.put("a", 10);

        ToolResult result = gatewaySkill.callTool("adder", args);

        // 断言：由于 mockTool 的 call 返回了字符串，这里校验结果
        assertEquals("adder_executed_with_10", result.getContent());
    }

    /**
     * 测试调用不存在的工具
     */
    @Test
    void testCallNonExistentTool() {
        Object result = gatewaySkill.callTool("unknown_api", new HashMap<>());
        assertTrue(result.toString().contains("未找到工具"));
    }

    // --- 辅助方法：快速创建一个模拟工具 ---
    private FunctionTool createMockTool(String name, String desc, String schema) {
        return new FunctionToolDesc(name)
                .description(desc)
                .inputSchema(schema)
                .doHandle(args -> {
                    // 模拟执行逻辑：返回 工具名 + 参数值
                    Object val = args.getOrDefault("a", args.getOrDefault("city", "none"));
                    return name + "_executed_with_" + val;
                });

    }
}