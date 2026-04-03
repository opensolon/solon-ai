package org.noear.solon.ai.harness.permission;

/**
 * 工具权限
 *
 * @author noear 2026/4/3 created
 */
public enum ToolPermission {
    TOOL_HITL("hitl"),
    TOOL_GENERATE("generate"),
    TOOL_RESTAPI("restapi"),
    TOOL_MCP("mcp"),
    TOOL_CODE("code"),

    TOOL_CODESEARCH("codesearch"),
    TOOL_WEBSEARCH("websearch"),
    TOOL_WEBFETCH("webfetch"),
    TOOL_TODO("todo"),
    TOOL_SKILL("skill"),
    TOOL_TASK("task"),

    TOOL_BASH("bash"),
    TOOL_LS("ls"),
    TOOL_GREP("grep"),
    TOOL_GLOB("glob"),
    TOOL_EDIT("edit"),
    TOOL_READ("read"),

    TOOL_ALL_PUBC("*"), //全部公有的
    TOOL_ALL_FULL("**"), // 全部（包括公有，私有）
    ;

    private final String name;

    public String getName() {
        return name;
    }

    ToolPermission(String name) {
        this.name = name;
    }
}