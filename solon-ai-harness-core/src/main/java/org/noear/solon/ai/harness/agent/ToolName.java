/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.agent;

import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具名（代理定义 {@code tools} / {@code disallowedTools} 的取值）
 *
 * <p>含三个分组名：{@code pi}、{@code *}（公有）、{@code **}（完整，含委派等私有工具）。
 * 分组展开的唯一来源是 {@link #expand(String)}。</p>
 *
 * @author noear 2026/7/6 created
 * @since 4.0.4
 */
@Preview("4.0.4")
public enum ToolName {
    TOOL_HITL("hitl"),
    TOOL_GENERATE("generate"),

    TOOL_OPENAPI("openapi"),
    TOOL_MCP("mcp"),
    TOOL_LSP("lsp"),
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
    TOOL_MEMORY("memory"),

    TOOL_PI("pi"), //代表四个终端工具：read,write,edit,bash
    TOOL_ALL_PUBLIC("*"), //全部公有的
    TOOL_ALL_FULL("**"), // 全部完整的（包括公有，私有）
    ;

    private final String name;

    public String getName() {
        return name;
    }

    ToolName(String name) {
        this.name = name;
    }

    /** 是否为分组名（pi / * / **） */
    public boolean isGroup() {
        return this == TOOL_PI || this == TOOL_ALL_PUBLIC || this == TOOL_ALL_FULL;
    }

    /** 四个终端工具 */
    private static final ToolName[] GROUP_PI = {TOOL_READ, TOOL_WRITE, TOOL_EDIT, TOOL_BASH};

    /** 公有工具（不含委派 task/generate 等私有工具） */
    private static final ToolName[] GROUP_PUBLIC = {TOOL_READ, TOOL_WRITE, TOOL_EDIT, TOOL_GLOB, TOOL_GREP, TOOL_LS,
            TOOL_BASH, TOOL_SKILL, TOOL_TODO, TOOL_CODE, TOOL_CODESEARCH, TOOL_WEBSEARCH, TOOL_WEBFETCH, TOOL_LSP};

    /** 完整工具（公有 + 私有） */
    private static final ToolName[] GROUP_FULL = {TOOL_READ, TOOL_WRITE, TOOL_EDIT, TOOL_GLOB, TOOL_GREP, TOOL_LS,
            TOOL_BASH, TOOL_SKILL, TOOL_TODO, TOOL_CODE, TOOL_CODESEARCH, TOOL_WEBSEARCH, TOOL_WEBFETCH,
            TOOL_TASK, TOOL_GENERATE, TOOL_MCP, TOOL_OPENAPI, TOOL_HITL, TOOL_LSP, TOOL_MEMORY};

    /**
     * 把工具名展开为具体工具名列表（分组名按固定顺序展开；其它名称原样返回单元素列表）。
     */
    public static List<String> expand(String name) {
        ToolName[] group;
        if (TOOL_ALL_FULL.name.equals(name)) {
            group = GROUP_FULL;
        } else if (TOOL_ALL_PUBLIC.name.equals(name)) {
            group = GROUP_PUBLIC;
        } else if (TOOL_PI.name.equals(name)) {
            group = GROUP_PI;
        } else {
            return Collections.singletonList(name);
        }

        List<String> names = new ArrayList<>(group.length);
        for (ToolName t : group) {
            names.add(t.name);
        }
        return names;
    }
}