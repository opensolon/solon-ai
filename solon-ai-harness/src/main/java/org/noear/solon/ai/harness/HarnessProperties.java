package org.noear.solon.ai.harness;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ResourceUtil;

import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 马具配置属性
 *
 * @author noear
 * @since 3.10.0
 */
@Getter
@Setter
public class HarnessProperties implements Serializable {
    //马具目录
    private final String harnessHome;

    //默认工作区
    private String workspace = "work";

    //主代理工具权限
    private List<String> tools = new ArrayList<>();

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

    //大模型
    private List<ChatConfig> models = new ArrayList<>();
    //技能池
    private Map<String, String> skillPools = new LinkedHashMap<>();
    //mcp集
    private Map<String, McpServerParameters> mcpServers = new LinkedHashMap<>();
    //api集
    private Map<String, ApiSource> apiServers = new LinkedHashMap<>();

    public HarnessProperties(String harnessHome) {
        if (Assert.isEmpty(harnessHome)) {
            harnessHome = ".solon/";
        } else if (harnessHome.endsWith("/") == false) {
            harnessHome = harnessHome + "/";
        }

        this.harnessHome = harnessHome;
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
     * 当前目录
     */
    public static String getUserDir() {
        return System.getProperty("user.dir");
    }

    /**
     * 用户主目录
     */
    public static String getUserHome() {
        return System.getProperty("user.home");
    }

    public URL getConfigUrl() throws MalformedURLException {
        //1. 资源文件（一般开发时）
        URL tmp = ResourceUtil.getResource(HarnessEngine.NAME_CONFIG_YML);
        if (tmp != null) {
            return tmp;
        }

        //2. 工作区配置
        Path path = Paths.get(HarnessProperties.getUserDir(), getHarnessHome(), HarnessEngine.NAME_CONFIG_YML);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //3. 用户目录区配置
        path = Paths.get(HarnessProperties.getUserHome(), getHarnessHome(), HarnessEngine.NAME_CONFIG_YML);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //4. 程序边上的配置文件
        tmp = ResourceUtil.getResourceByFile(HarnessEngine.NAME_CONFIG_YML);
        if (tmp != null) {
            return tmp;
        }

        return null;
    }

    public URL getAgentsUrl() throws MalformedURLException {
        //1. 工作区配置
        Path path = Paths.get(getWorkspace(), getHarnessHome(), HarnessEngine.NAME_AGENTS_MD);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //2. 用户目录区配置
        path = Paths.get(HarnessProperties.getUserHome(), getHarnessHome(), HarnessEngine.NAME_AGENTS_MD);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //3. 程序边上的配置文件
        URL tmp = ResourceUtil.getResourceByFile(HarnessEngine.NAME_AGENTS_MD);
        if (tmp != null) {
            return tmp;
        }

        return null;
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