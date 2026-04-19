/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
    TOOL_TASK("task"),

    TOOL_BASH("bash"),
    TOOL_LS("ls"),
    TOOL_GREP("grep"),
    TOOL_GLOB("glob"),
    TOOL_EDIT("edit"),
    TOOL_READ("read"),
    TOOL_WRITE("write"),

    TOOL_SKILL("skill"),

    TOOL_PI("pi"), //代表四个终端工具：read,write,edit,bash
    TOOL_ALL_PUBLIC("*"), //全部公有的
    TOOL_ALL_FULL("**"), // 全部完整的（包括公有，私有）
    ;

    private final String name;

    public String getName() {
        return name;
    }

    ToolPermission(String name) {
        this.name = name;
    }
}