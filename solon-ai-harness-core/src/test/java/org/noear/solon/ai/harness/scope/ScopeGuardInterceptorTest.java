package org.noear.solon.ai.harness.scope;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.harness.agent.ToolName;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 作用域守卫：名单展开、MCP 直连 / 中转判断、预填结果
 */
class ScopeGuardInterceptorTest {
    /**
     * 假 MCP 归属：weather_* 属于 weather 服务，crm_* 属于 crm 服务，query 同时属于两者（同名工具）
     */
    private static Collection<String> ownersOf(String server, String name) {
        List<String> owners = new ArrayList<>();
        if (name.startsWith("weather_") || name.equals("query")) owners.add("weather");
        if (name.startsWith("crm_") || name.equals("query")) owners.add("crm");
        if (server != null) {
            return owners.contains(server) ? Collections.singletonList(server) : Collections.emptyList();
        }
        return owners;
    }

    private final ScopeGuardInterceptor guard = new ScopeGuardInterceptor(
            ScopeGuardInterceptorTest::ownersOf, "goal_update"::equals, () -> false);

    /** 中转/直连判定的简写（不带服务名） */
    private String deny(ScopeGuardInterceptor g, InputScope scope, String toolName, String target) {
        return g.denyReason(scope, toolName, null, target);
    }

    @Test
    void expandLogicalAndGroupNames() {
        Set<String> s = ToolName.expandRuntime(Arrays.asList("bash", "skill"));
        assertTrue(s.containsAll(Arrays.asList("bash", "bash_start", "bash_wait", "bash_stop")));
        assertTrue(s.containsAll(Arrays.asList("skillread", "skilllist", "skillsearch", "skillrefresh")));
        assertFalse(s.contains("read"));

        Set<String> full = ToolName.expandRuntime(Collections.singletonList(ToolName.TOOL_ALL_FULL.getName()));
        assertTrue(full.contains("task"));
        assertTrue(full.contains("multitask"));
        assertTrue(full.contains("call_tool"));
        assertTrue(full.contains(ToolName.TOOL_MCP.getName()), "mcp 标记保留，供直连工具判断");

        Set<String> pub = ToolName.expandRuntime(Collections.singletonList(ToolName.TOOL_ALL_PUBLIC.getName()));
        assertFalse(pub.contains("task"), "公有分组不含委派");

        // 未知名按运行时名原样保留（扩展工具）
        assertTrue(ToolName.expandRuntime(Collections.singletonList("goal_update")).contains("goal_update"));
    }

    @Test
    void nullDimensionMeansUnlimited() {
        InputScope scope = InputScope.builder("t1").build();
        assertNull(deny(guard, scope, "bash", null));
        assertNull(deny(guard, scope, "weather_now", null));
        assertNull(deny(guard, scope, "call_tool", "crm_query"));
    }

    @Test
    void toolAllowlistDeniesOthers() {
        InputScope scope = InputScope.builder("t1").tools("read", "skill").build();
        assertNull(deny(guard, scope, "read", null));
        assertNull(deny(guard, scope, "skillread", null));
        assertNull(deny(guard, scope, "now", null), "时钟工具恒允许");
        assertEquals("bash", deny(guard, scope, "bash", null));
        assertEquals("bash_start", deny(guard, scope, "bash_start", null), "bash 分组整体禁止");
        assertEquals("task", deny(guard, scope, "task", null));
        assertNull(deny(guard, scope, "goal_update", null), "已声明的扩展工具不受名单约束（否则 goal 无法完成）");
        assertEquals("host_write", deny(guard, scope, "host_write", null), "未声明的宿主工具按名单判断");
        InputScope withHost = InputScope.builder("t1").tools("read", "host_write").build();
        assertNull(deny(guard, withHost, "host_write", null), "写进名单即可放行");
        assertEquals("weather_now", deny(guard, scope, "weather_now", null), "未授权 mcp 时直连工具拒绝");
    }

    @Test
    void mcpBoundaryAppliesToDirectAndRelayTools() {
        InputScope scope = InputScope.builder("t1").tools("mcp").mcps("weather").build();

        // 直连
        assertNull(deny(guard, scope, "weather_now", null));
        assertEquals("crm_query", deny(guard, scope, "crm_query", null));

        // 中转：按目标工具的归属服务
        assertNull(deny(guard, scope, "call_tool", "weather_now"));
        assertEquals("crm_query", deny(guard, scope, "call_tool", "crm_query"));
        assertEquals("crm_query", deny(guard, scope, "get_tool_detail", "crm_query"));
        assertEquals("unknown_x", deny(guard, scope, "call_tool", "unknown_x"), "无归属的目标按越界处理");

        // 发现工具随 mcp 分组放行（服务边界由网关按 _mcps_allowed 收窄）
        assertNull(deny(guard, scope, "search_tools", null));
        assertNull(deny(guard, scope, "list_server_tools", null), "服务目录模式的下钻工具不能被名单拦掉");
        InputScope noMcp = InputScope.builder("t1").tools("read").build();
        assertEquals("list_server_tools", deny(guard, noMcp, "list_server_tools", null));

        // 空名单 = 全部禁止
        InputScope none = InputScope.builder("t1").mcps(Collections.emptyList()).build();
        assertEquals("weather_now", deny(guard, none, "weather_now", null));
    }

    @Test
    void relayLocatesByServerNameForDuplicateTools() {
        InputScope scope = InputScope.builder("t1").tools("mcp", "call_tool", "get_tool_detail").mcps("weather").build();

        // 点名服务：以给出的服务为准，越界服务的同名工具拒绝
        assertNull(guard.denyReason(scope, "call_tool", "weather", "query"));
        assertEquals("query", guard.denyReason(scope, "call_tool", "crm", "query"), "边界外服务的同名工具不能借名调用");
        assertEquals("query", guard.denyReason(scope, "get_tool_detail", "crm", "query"));
        assertEquals("weather_now", guard.denyReason(scope, "call_tool", "crm", "weather_now"), "服务里没有该工具按越界处理");

        // 不带服务名：边界内有任一归属服务即放行（由网关在边界内解析）
        assertNull(guard.denyReason(scope, "call_tool", null, "query"));
        InputScope crmOnly = InputScope.builder("t1").tools("mcp", "call_tool").mcps("crm").build();
        assertEquals("weather_now", guard.denyReason(crmOnly, "call_tool", null, "weather_now"));

        // 直连同名工具：边界内有任一归属服务即放行（视图已按边界收窄），都不在边界内则拒绝
        assertNull(guard.denyReason(scope, "query", null, null));
        InputScope none = InputScope.builder("t1").tools("mcp").mcps("other").build();
        assertEquals("query", guard.denyReason(none, "query", null, null));
    }

    @Test
    void builtinNameIsNotShadowedByMcpTool() {
        ScopeGuardInterceptor shadow = new ScopeGuardInterceptor((server, name) -> Collections.singletonList("evil"), name -> false, () -> false);
        InputScope scope = InputScope.builder("t1").tools("read").mcps("weather").build();
        assertNull(deny(shadow, scope, "read", null), "内置名优先，同名 MCP 工具不改变判定");
        assertEquals("bash", deny(shadow, scope, "bash", null));
    }

    @Test
    void scopeIsImmutableSnapshot() {
        List<String> skills = new ArrayList<>(Arrays.asList("a"));
        InputScope scope = InputScope.builder("t1").skills(skills).build();
        skills.add("b");
        assertEquals(Collections.singleton("a"), scope.getSkills());
        assertThrows(UnsupportedOperationException.class, () -> scope.getSkills().add("c"));
    }

    @Test
    void requiredScopeMissingDeniesAllButClock() {
        ScopeGuardInterceptor strict = new ScopeGuardInterceptor((server, name) -> Collections.<String>emptyList(), "goal_update"::equals, () -> true);
        ToolExchanger bash = new ToolExchanger("c1", "bash", new HashMap<>());
        ToolExchanger goal = new ToolExchanger("c2", "goal_update", new HashMap<>());
        ToolExchanger now = new ToolExchanger("c3", "now", new HashMap<>());
        strict.onToolCallStart(null, bash);
        strict.onToolCallStart(null, goal);
        strict.onToolCallStart(null, now);
        assertNotNull(bash.getToolResult(), "缺失作用域：内置工具拒绝");
        assertNotNull(goal.getToolResult(), "缺失作用域：扩展工具同样拒绝");
        assertNull(now.getToolResult(), "时钟工具恒允许");

        ToolExchanger loose = new ToolExchanger("c4", "bash", new HashMap<>());
        guard.onToolCallStart(null, loose);
        assertNull(loose.getToolResult(), "未要求作用域时不干预");
    }

    @Test
    void prefilledResultIsLeftUntouched() {
        ToolExchanger ex = new ToolExchanger("c1", "bash", new HashMap<>());
        ex.setResult("prefilled");
        InputScope scope = InputScope.builder("t1").tools("read").build();
        // 经公共入口无法直接注入 trace 属性，这里只验证判定函数与预填约定
        assertEquals("bash", deny(guard, scope, ex.getToolName(), null));
        assertEquals("prefilled", ex.getResult());
    }
}
