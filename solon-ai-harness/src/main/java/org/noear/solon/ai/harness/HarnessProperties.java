package org.noear.solon.ai.harness;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatConfig;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 马具属性
 *
 * @author noear
 */
@Getter
@Setter
public class HarnessProperties implements Serializable {
    private ChatConfig chatModel;

    private final String harnessHome;

    private String workspace = "work";

    private String tools = "**";

    private int maxSteps = 30;
    private boolean maxStepsAutoExtensible = false;

    private int sessionWindowSize = 8;
    private int summaryWindowSize = 15;
    private int summaryWindowToken = 15000;

    private boolean sandboxMode = true;
    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;

    private Map<String, String> skillPools = new LinkedHashMap<>();

    private Map<String, McpServerParameters> mcpServers = new LinkedHashMap<>();
    private Map<String, ApiSource> restApis = new LinkedHashMap<>();

    public HarnessProperties(String harnessHome) {
        if (Assert.isEmpty(harnessHome)) {
            harnessHome = ".solon/";
        } else if (harnessHome.endsWith("/") == false) {
            harnessHome = harnessHome + "/";
        }

        this.harnessHome = harnessHome;
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
     * 马具主记忆存放区
     */
    public final String getHarnessMemory() {
        return getHarnessHome() + "memory/";
    }
}