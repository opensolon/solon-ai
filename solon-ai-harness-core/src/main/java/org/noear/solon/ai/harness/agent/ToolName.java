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

import java.util.*;

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
        return expandGroup(name);
    }

    // ========== 逻辑名 → 运行时工具名（唯一来源：AgentFactory 装配终端工具与作用域守卫都读这里）==========
    // Talent 提供的工具名变化时必须同步（ToolNameRuntimeDriftTest 守护）。

    private static final Map<String, List<String>> RUNTIME = new HashMap<>();
    private static final Set<String> BUILTIN_RUNTIME = new HashSet<>();
    /** 不受作用域名单约束的无害工具（时钟） */
    private static final Set<String> ALWAYS_ALLOWED = Collections.singleton("now");

    static {
        runtime("read", "read");
        runtime("write", "write");
        runtime("edit", "edit");
        runtime("glob", "glob");
        runtime("grep", "grep");
        runtime("ls", "ls");
        runtime("list", "ls");
        runtime("bash", "bash", "bash_start", "bash_wait", "bash_stop");
        runtime("skill", "skilllist", "skillsearch", "skillread", "skillrefresh");
        runtime("todo", "todoread", "todowrite");
        runtime("todoread", "todoread");
        runtime("todowrite", "todowrite");
        runtime("webfetch", "webfetch");
        runtime("websearch", "websearch");
        runtime("codesearch", "codesearch");
        runtime("memory", "memory_search", "memory_recall", "memory_extract", "memory_prune", "memory_consolidate");
        runtime("task", "task", "multitask");
        runtime("subagent", "task", "multitask");
        runtime("generate", "generate");
        runtime("mcp", "search_tools", "get_tool_detail", "call_tool");
        runtime("openapi", "search_apis", "get_api_detail", "call_api");
        runtime("restapi", "search_apis", "get_api_detail", "call_api");
        runtime("lsp", "lsp");
        runtime("code"); // 只有指令，无工具
        runtime("hitl"); // 拦截器，非工具
    }

    private static void runtime(String logical, String... runtimeNames) {
        RUNTIME.put(logical, Collections.unmodifiableList(Arrays.asList(runtimeNames)));
        BUILTIN_RUNTIME.addAll(Arrays.asList(runtimeNames));
    }

    /** 逻辑名对应的运行时工具名（未知名称原样返回单元素列表，视为运行时名） */
    public static List<String> runtimeNamesOf(String name) {
        List<String> list = RUNTIME.get(name);
        return list == null ? Collections.singletonList(name) : list;
    }

    /** 是否是内置 Talent 提供的运行时工具名（含时钟） */
    public static boolean isBuiltinRuntime(String runtimeName) {
        return BUILTIN_RUNTIME.contains(runtimeName) || ALWAYS_ALLOWED.contains(runtimeName);
    }

    /** 是否是不受作用域约束的无害工具 */
    public static boolean isAlwaysAllowed(String runtimeName) {
        return ALWAYS_ALLOWED.contains(runtimeName);
    }

    /**
     * 展开为运行时工具名：分组（pi / * / **）→ 逻辑名 → 运行时名；保留逻辑名 {@code mcp} 作为标记，
     * 供守卫判断 MCP 直连工具。
     */
    public static Set<String> expandRuntime(Collection<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : names) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }

            for (String name : expandGroup(raw.trim())) {
                if (TOOL_MCP.name.equals(name)) {
                    out.add(TOOL_MCP.name);
                }
                out.addAll(runtimeNamesOf(name));
            }
        }
        return out;
    }

    private static List<String> expandGroup(String name) {
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