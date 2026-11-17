/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.gateway;

import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 网关的服务注册表（包内）：连接管理、按「服务 → 工具」的工具索引、握手信息快照与服务描述、工具定位。
 *
 * <p>不涉及请求级的视野与边界（由网关在调用处传入），也不涉及渲染。
 *
 * @author noear
 * @since 4.2.0
 */
final class McpServerRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(McpServerRegistry.class);

    // 服务使用说明（instructions）的最大字符数
    static final int INSTRUCTIONS_MAX = 2000;
    // instructions 取首句时，英文句点至少要在这么多字符之后才切（避开开头的缩写）
    static final int FIRST_SENTENCE_MIN = 20;

    // 工具索引：服务名 -> (工具名小写 -> 工具)。服务是定位工具的第一级，不同服务允许有同名工具。
    // 每个服务的工具表构建完即只读，整表替换（不做原地修改）。
    private final Map<String, Map<String, FunctionTool>> serverTools = new ConcurrentHashMap<>();

    // MCP 连接管理（含停用的服务）
    private final Map<String, McpClientProvider> providerMap = new ConcurrentHashMap<>();

    // 服务端握手信息快照：服务名 -> 最近一次读到的握手描述与使用说明。
    // MCP 客户端出错后会清掉连接，到下次重连之前读不到握手信息；工具表却还在，没有快照时目录与指令会跟着跳变
    private final Map<String, ServerMeta> serverMetas = new ConcurrentHashMap<>();

    /** 握手信息快照（记下来源 provider：同名服务重新添加后，旧快照不再作数） */
    static final class ServerMeta {
        final McpClientProvider provider;
        final String description;
        final String instructions;

        ServerMeta(McpClientProvider provider, String description, String instructions) {
            this.provider = provider;
            this.description = description;
            this.instructions = instructions;
        }
    }

    /**
     * 点名使用的定位结果：要么定位到唯一工具，要么是有多个候选服务（需要模型指定 server_name）
     */
    static final class Target {
        final String server;
        final FunctionTool tool;
        final List<String> candidates;

        Target(String server, FunctionTool tool, List<String> candidates) {
            this.server = server;
            this.tool = tool;
            this.candidates = candidates;
        }
    }

    // ========== 增删与刷新 ==========

    /**
     * 添加服务；同名已存在时替换并关闭旧连接。停用的服务只登记，不进工具索引。
     *
     * <p>先用新连接拉取工具（含握手），再把连接与工具表一起换上：握手期间旧服务的工具仍可见、可调用，
     * 不会出现「服务暂时消失」的窗口。拉取失败时新连接照样登记（工具为空，稍后可 refresh），
     * 旧服务同样被替换——与「按名替换」的语义一致。
     */
    void add(String name, McpClientProvider provider) {
        Map<String, FunctionTool> tools = Collections.emptyMap();
        if (provider.isEnabled()) {
            try {
                tools = loadTools(provider);
            } catch (Throwable e) {
                LOG.error("Mcp server {}: loading failed", name, e);
            }
        }

        McpClientProvider old;
        synchronized (this) {
            old = providerMap.put(name, provider);
            serverMetas.remove(name);
            putTools(name, tools);
        }

        if (provider.isEnabled()) {
            // 拉取工具通常已完成握手，顺手留下握手信息快照
            serverMeta(name);
            LOG.info("Mcp server {}: added ({} tools)", name, tools.size());
        } else {
            LOG.info("Mcp server '{}': is disabled, registered to management only", name);
        }

        if (old != null && old != provider) {
            close(name, old);
        }
    }

    /** 移除服务并关闭连接（只移除该服务自己的工具，其它服务的同名工具不受影响） */
    void remove(String name) {
        Map<String, FunctionTool> tools;
        McpClientProvider provider;
        synchronized (this) {
            tools = serverTools.remove(name);
            serverMetas.remove(name);
            provider = providerMap.remove(name);
        }

        if (provider != null) {
            close(name, provider);
        }

        if (tools != null && !tools.isEmpty()) {
            LOG.info("Mcp server {}: removed ({} tools)", name, tools.size());
        }
    }

    private static void close(String name, McpClientProvider provider) {
        try {
            provider.close();
        } catch (Exception e) {
            LOG.warn("Mcp server {}: close failed", name, e);
        }
    }

    /** 重新拉取工具表并整表替换（停用的只从索引移除、不关连接；拉取失败保留原表） */
    void refresh(String name) {
        McpClientProvider provider = providerMap.get(name);
        if (provider == null) {
            return;
        }

        if (provider.isEnabled() == false) {
            Map<String, FunctionTool> tools;
            synchronized (this) {
                if (providerMap.get(name) != provider) {
                    return;
                }
                tools = serverTools.remove(name);
            }
            LOG.info("Mcp server {}: is disabled, removed {} tools from index", name,
                    tools != null ? tools.size() : 0);
            return;
        }

        final Map<String, FunctionTool> tools;
        try {
            tools = loadTools(provider);
        } catch (Throwable e) {
            LOG.error("Mcp server {}: loading failed", name, e);
            return;
        }

        synchronized (this) {
            // 拉取期间服务被移除或替换时，不把旧连接的工具写回去
            if (providerMap.get(name) != provider) {
                return;
            }
            putTools(name, tools);
        }
        serverMeta(name);

        LOG.info("Mcp server {}: refreshed ({} tools)", name, tools.size());
    }

    /** 拉取服务的工具表（工具名小写为键，保持服务端顺序） */
    private static Map<String, FunctionTool> loadTools(McpClientProvider provider) {
        Map<String, FunctionTool> tools = new LinkedHashMap<>();
        for (FunctionTool tool : provider.getToolsActivated()) {
            tools.put(tool.name().toLowerCase(), tool);
        }
        return Collections.unmodifiableMap(tools);
    }

    /** 整表替换某服务的工具（空表即移除，不留空分组） */
    private void putTools(String name, Map<String, FunctionTool> tools) {
        if (tools.isEmpty()) {
            serverTools.remove(name);
        } else {
            serverTools.put(name, tools);
        }
    }

    // ========== 查询 ==========

    /** 已登记的服务名（含停用的；只读视图） */
    Set<String> names() {
        return Collections.unmodifiableSet(providerMap.keySet());
    }

    @Nullable
    McpClientProvider provider(String name) {
        return providerMap.get(name);
    }

    boolean has(String name) {
        return providerMap.containsKey(name);
    }

    /** 有工具的服务及其工具表（只读视图；停用、加载失败的服务不在其中） */
    Map<String, Map<String, FunctionTool>> serverTools() {
        return Collections.unmodifiableMap(serverTools);
    }

    /** 某服务的工具表（规范服务名；没有时返回 null） */
    @Nullable
    Map<String, FunctionTool> toolsOf(String server) {
        return serverTools.get(server);
    }

    /**
     * 服务名规范化，只在有工具的服务里找（精确优先，否则大小写不敏感的唯一匹配）
     */
    @Nullable
    String canonicalServer(String serverName) {
        return canonicalName(serverTools.keySet(), serverName);
    }

    /**
     * 定位工具的归属服务（结果按服务名排序）
     *
     * @param serverName 给出时只在该服务内找，结果至多一个；为空时返回拥有该工具的全部服务
     */
    List<String> serversOfTool(@Nullable String serverName, @Nullable String toolName) {
        if (Utils.isBlank(toolName)) {
            return Collections.emptyList();
        }

        String key = toolName.trim().toLowerCase();

        if (Utils.isBlank(serverName) == false) {
            String server = canonicalServer(serverName);
            if (server != null) {
                Map<String, FunctionTool> tools = serverTools.get(server);
                if (tools != null && tools.containsKey(key)) {
                    return Collections.singletonList(server);
                }
            }
            return Collections.emptyList();
        }

        List<String> servers = new ArrayList<>();
        for (Map.Entry<String, Map<String, FunctionTool>> entry : serverTools.entrySet()) {
            if (entry.getValue().containsKey(key)) {
                servers.add(entry.getKey());
            }
        }
        Collections.sort(servers);
        return servers;
    }

    /**
     * 定位点名使用（get_tool_detail / call_tool）的目标，只在强制边界内找。
     *
     * <p>给了服务名：只在该服务内找。没给服务名：边界内只有一个服务有这个工具就自动补上（兼容旧写法）；
     * 有多个时返回候选，由调用方提示指定。越界与不存在不加区分（返回 null），不泄露边界外的服务。
     *
     * @param allowed 强制边界（null 表示不限制）
     */
    @Nullable
    Target resolveTarget(@Nullable String serverName, String toolName, @Nullable List<String> allowed) {
        List<String> servers = new ArrayList<>();
        for (String server : serversOfTool(serverName, toolName)) {
            if (allowed == null || allowed.contains(server)) {
                servers.add(server);
            }
        }

        if (servers.isEmpty()) {
            return null;
        }

        if (servers.size() > 1) {
            return new Target(null, null, servers);
        }

        String server = servers.get(0);
        Map<String, FunctionTool> tools = serverTools.get(server);
        FunctionTool tool = tools == null ? null : tools.get(toolName.trim().toLowerCase());
        return tool == null ? null : new Target(server, tool, null);
    }

    // ========== 服务描述与使用说明 ==========

    /**
     * 服务描述（服务名大小写不敏感，在全部已登记服务里找）：配置的描述 &gt; 握手上报的 description &gt; instructions 的第一句。
     * 都没有或服务不存在时返回 null。
     */
    @Nullable
    String description(@Nullable String name) {
        if (Utils.isBlank(name)) {
            return null;
        }

        String server = canonicalName(providerMap.keySet(), name);
        McpClientProvider provider = server == null ? null : providerMap.get(server);
        if (provider == null) {
            return null;
        }

        String description = provider.getDescription();
        if (Utils.isNotBlank(description)) {
            return description.trim();
        }

        ServerMeta meta = serverMeta(server);
        if (meta == null) {
            return null;
        }

        if (Utils.isNotBlank(meta.description)) {
            return meta.description.trim();
        }

        if (Utils.isNotBlank(meta.instructions)) {
            String sentence = firstSentence(meta.instructions);
            if (sentence.isEmpty() == false) {
                return sentence;
            }
        }

        return null;
    }

    /**
     * 服务端握手信息：已连接时读实时值并更新快照；读不到（尚未连接，或出错后连接已被清掉）时用同一 provider 的快照。
     *
     * <p>已完成握手的服务一定上报 serverInfo（MCP 规范必填），所以 serverInfo 与 instructions 都为 null 就是「读不到」，
     * 不是「服务没有描述」。不会为此建立连接。
     *
     * @param server 规范服务名
     */
    @Nullable
    ServerMeta serverMeta(String server) {
        McpClientProvider provider = providerMap.get(server);
        if (provider == null) {
            return null;
        }

        try {
            McpSchema.Implementation info = provider.getServerInfo();
            String instructions = provider.getServerInstructions();
            if (info != null || instructions != null) {
                ServerMeta meta = new ServerMeta(provider, info == null ? null : info.description(), instructions);
                serverMetas.put(server, meta);
                return meta;
            }
        } catch (Throwable e) {
            LOG.debug("Mcp server {}: read server info failed", server, e);
        }

        ServerMeta meta = serverMetas.get(server);
        return meta != null && meta.provider == provider ? meta : null;
    }

    /**
     * 服务端 instructions 全文（保留换行结构，去掉行尾空白与多余空行；超过 {@value #INSTRUCTIONS_MAX} 字截断，
     * 截断时以 … 结尾并在末尾追加 truncatedNote）。没有时返回 null。
     *
     * @param server 规范服务名
     */
    @Nullable
    String instructions(String server, String truncatedNote) {
        ServerMeta meta = serverMeta(server);
        String text = meta == null ? null : meta.instructions;
        if (Utils.isBlank(text)) {
            return null;
        }

        String block = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (block.length() > INSTRUCTIONS_MAX) {
            block = GatewaySupport.cut(block, INSTRUCTIONS_MAX).trim() + "…\n" + truncatedNote;
        }
        return block;
    }

    /**
     * 服务是否有需要单独给出的使用说明。说明只有一句、就是服务描述，而且描述是完整给出的（不超过 shownMax，没被截断）时不算；
     * 描述被截断时（如整段没有句末标点的长说明）仍要给全文。
     *
     * @param shownMax 服务描述在模型已看到的位置里的截断长度
     */
    boolean hasDistinctInstructions(String server, int shownMax) {
        ServerMeta meta = serverMeta(server);
        if (meta == null || Utils.isBlank(meta.instructions)) {
            return false;
        }

        String description = description(server);
        if (description == null) {
            return true;
        }

        String text = GatewaySupport.oneLine(meta.instructions, Integer.MAX_VALUE);
        return text.length() > shownMax || text.equals(GatewaySupport.oneLine(description, Integer.MAX_VALUE)) == false;
    }

    /**
     * 取 instructions 的第一句当描述：只看第一段（空行之前），空白压成一行、去掉开头的 Markdown 标题符；
     * 遇到中文句末标点就切，英文只在「句末标点 + 空格 + 大写字母」处切，且前 {@value #FIRST_SENTENCE_MIN} 个字符内不切
     * （避开 e.g. / Inc. 这类缩写）。找不到切点时返回整段（指令里另有长度截断）。
     */
    static String firstSentence(String text) {
        String paragraph = text.trim().split("\\n[ \\t]*\\n", 2)[0];
        String line = paragraph.replaceAll("\\s+", " ").trim().replaceFirst("^#+\\s*", "");
        int len = line.length();
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (c == '\u3002' || c == '\uff01' || c == '\uff1f') {
                return line.substring(0, i + 1);
            }

            if ((c == '.' || c == '!' || c == '?') && i + 1 >= FIRST_SENTENCE_MIN && i + 2 < len
                    && line.charAt(i + 1) == ' ' && Character.isUpperCase(line.charAt(i + 2))) {
                return line.substring(0, i + 1);
            }
        }
        return line;
    }

    /** 名字规范化：精确匹配优先，否则取大小写不敏感的唯一匹配（多个或没有返回 null） */
    @Nullable
    static String canonicalName(Set<String> names, String serverName) {
        String name = serverName.trim();
        if (names.contains(name)) {
            return name;
        }

        String found = null;
        for (String server : names) {
            if (server.equalsIgnoreCase(name)) {
                if (found != null) {
                    return null;
                }
                found = server;
            }
        }
        return found;
    }
}
