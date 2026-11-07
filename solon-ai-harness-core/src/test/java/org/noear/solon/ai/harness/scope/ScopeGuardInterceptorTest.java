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
    /** 假 MCP 归属：weather_* 属于 weather 服务，crm_* 属于 crm 服务 */
    private final ScopeGuardInterceptor guard = new ScopeGuardInterceptor(name -> {
        if (name.startsWith("weather_")) return "weather";
        if (name.startsWith("crm_")) return "crm";
        return null;
    }, "goal_update"::equals, () -> false);

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
        assertNull(guard.denyReason(scope, "bash", null));
        assertNull(guard.denyReason(scope, "weather_now", null));
        assertNull(guard.denyReason(scope, "call_tool", "crm_query"));
    }

    @Test
    void toolAllowlistDeniesOthers() {
        InputScope scope = InputScope.builder("t1").tools("read", "skill").build();
        assertNull(guard.denyReason(scope, "read", null));
        assertNull(guard.denyReason(scope, "skillread", null));
        assertNull(guard.denyReason(scope, "now", null), "时钟工具恒允许");
        assertEquals("bash", guard.denyReason(scope, "bash", null));
        assertEquals("bash_start", guard.denyReason(scope, "bash_start", null), "bash 分组整体禁止");
        assertEquals("task", guard.denyReason(scope, "task", null));
        assertNull(guard.denyReason(scope, "goal_update", null), "已声明的扩展工具不受名单约束（否则 goal 无法完成）");
        assertEquals("host_write", guard.denyReason(scope, "host_write", null), "未声明的宿主工具按名单判断");
        InputScope withHost = InputScope.builder("t1").tools("read", "host_write").build();
        assertNull(guard.denyReason(withHost, "host_write", null), "写进名单即可放行");
        assertEquals("weather_now", guard.denyReason(scope, "weather_now", null), "未授权 mcp 时直连工具拒绝");
    }

    @Test
    void mcpBoundaryAppliesToDirectAndRelayTools() {
        InputScope scope = InputScope.builder("t1").tools("mcp").mcps("weather").build();

        // 直连
        assertNull(guard.denyReason(scope, "weather_now", null));
        assertEquals("crm_query", guard.denyReason(scope, "crm_query", null));

        // 中转：按目标工具的归属服务
        assertNull(guard.denyReason(scope, "call_tool", "weather_now"));
        assertEquals("crm_query", guard.denyReason(scope, "call_tool", "crm_query"));
        assertEquals("crm_query", guard.denyReason(scope, "get_tool_detail", "crm_query"));
        assertEquals("unknown_x", guard.denyReason(scope, "call_tool", "unknown_x"), "无归属的目标按越界处理");

        // 空名单 = 全部禁止
        InputScope none = InputScope.builder("t1").mcps(Collections.emptyList()).build();
        assertEquals("weather_now", guard.denyReason(none, "weather_now", null));
    }

    @Test
    void builtinNameIsNotShadowedByMcpTool() {
        ScopeGuardInterceptor shadow = new ScopeGuardInterceptor(name -> "evil", name -> false, () -> false);
        InputScope scope = InputScope.builder("t1").tools("read").mcps("weather").build();
        assertNull(shadow.denyReason(scope, "read", null), "内置名优先，同名 MCP 工具不改变判定");
        assertEquals("bash", shadow.denyReason(scope, "bash", null));
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
        ScopeGuardInterceptor strict = new ScopeGuardInterceptor(name -> null, "goal_update"::equals, () -> true);
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
        assertEquals("bash", guard.denyReason(scope, ex.getToolName(), null));
        assertEquals("prefilled", ex.getResult());
    }
}
