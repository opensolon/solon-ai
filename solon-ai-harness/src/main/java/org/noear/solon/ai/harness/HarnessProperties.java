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
package org.noear.solon.ai.harness;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.ai.skills.lsp.LspServerParameters;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.*;

/**
 * 马具配置属性
 *
 * @author noear
 * @since 3.10.0
 */
@Getter
@Setter
@Preview("3.10")
public class HarnessProperties implements Serializable {
    //马具目录
    private final String harnessHome;

    //默认工作区
    private String workspace = "work";

    //系统提示词
    private String systemPrompt;

    //主代理工具权限
    private List<String> tools = new ArrayList<>();

    // 禁用工具（全局）
    private List<String> disallowedTools = new ArrayList<>();

    //最大步数
    private int maxSteps = 30;
    //是否自动续步
    private boolean maxStepsAutoExtensible = false;

    private int sessionWindowSize = 8;
    private int summaryWindowSize = 30;
    private int summaryWindowToken = 30000;
    private String summaryModel; //摘要大模型

    private boolean sandboxMode = true;
    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;

    //api 重试次数
    private int apiRetries = 3;
    //Mcp 重试次数
    private int mcpRetries = 3;
    //模型重试次数
    private int modelRetries = 3;

    //大模型
    private List<ChatConfig> models = new ArrayList<>();

    //扩展
    private List<HarnessExtension> extensions = new ArrayList<>();

    //技能池
    private Map<String, String> skillPools = new LinkedHashMap<>();
    //代理池
    private List<String> agentPools = new ArrayList<>();
    //mcp集
    private Map<String, McpServerParameters> mcpServers = new LinkedHashMap<>();
    //api集
    private Map<String, ApiSource> apiServers = new LinkedHashMap<>();
    //lsp集
    private Map<String, LspServerParameters> lspServers = new LinkedHashMap<>();

    public HarnessProperties(String harnessHome) {
        if (Assert.isEmpty(harnessHome)) {
            harnessHome = ".solon/";
        } else if (harnessHome.endsWith("/") == false) {
            harnessHome = harnessHome + "/";
        }

        this.harnessHome = harnessHome;
    }

    /**
     * 添加扩展
     */
    public void addExtension(HarnessExtension extension){
        this.extensions.add(extension);
    }

    /**
     * 添加接口源
     */
    public void addApiSource(String name, ApiSource apiSource) {
        getApiServers().put(name, apiSource);
    }

    /**
     * 添加 mcp 服务
     */
    public void addMcpServer(String name, McpServerParameters mcpParameters) {
        getMcpServers().put(name, mcpParameters);
    }

    /**
     * 添加 lsp 服务
     */
    public void addLspServer(String name, LspServerParameters lspParameters) {
        getLspServers().put(name, lspParameters);
    }

    /**
     * 添加技能池
     *
     * @param alias 必须 @ 开头
     */
    public void addSkillPool(String alias, String path) {
        getSkillPools().put(alias, path);
    }

    /**
     * 添加工具权限
     */
    public void addTools(ToolPermission... toolPermissions) {
        for (ToolPermission p1 : toolPermissions) {
            tools.add(p1.getName());
        }
    }

    /**
     * 添加模型配置
     */
    public void addModel(ChatConfig chatConfig) {
        models.add(chatConfig);
    }

    /**
     * 移除模型
     */
    public void removeModel(String modelName) {
        models.removeIf(m -> m.getNameOrModel().equals(modelName));
    }

    public ChatConfig getModelOrNil(String modelName) {
        if (models.isEmpty()) {
            return null;
        }

        if (Assert.isEmpty(modelName)) {
            return models.get(0);
        }

        for (ChatConfig c : models) {
            if (c.getNameOrModel().equals(modelName)) {
                return c;
            }
        }

        return null;
    }

    public ChatConfig getModelOrDef(String modelName) {
        if (models.isEmpty()) {
            return null;
        }

        if (Assert.isEmpty(modelName)) {
            return models.get(0);
        }

        for (ChatConfig c : models) {
            if (c.getNameOrModel().equals(modelName)) {
                return c;
            }
        }

        return models.get(0);
    }

    /**
     * 马具主目录
     */
    public final String getHarnessHome() {
        return harnessHome;
    }

    /**
     * 马具会话存放区
     */
    public final String getHarnessSessions() {
        return getHarnessHome() + "sessions/";
    }

    /**
     * 马具技能存放区
     */
    public final String getHarnessSkills() {
        return getHarnessHome() + "skills/";
    }

    /**
     * 马具子代理描述存放区
     */
    public final String getHarnessAgents() {
        return getHarnessHome() + "agents/";
    }

    /**
     * 马具记忆存放区
     */
    public final String getHarnessMemory() {
        return getHarnessHome() + "memory/";
    }

    /**
     * 马具下载存放区
     */
    public final String getHarnessDownload() {
        return getHarnessHome() + "download/";
    }
}