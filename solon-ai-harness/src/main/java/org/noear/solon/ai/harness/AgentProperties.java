package org.noear.solon.ai.harness;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.core.util.ResourceUtil;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Cli 配置
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
@Setter
public class AgentProperties implements Serializable {
    private ChatConfig chatModel;
    private String workDir = "work";
    private String tools = "**";
    private int maxSteps = 30;
    private boolean maxStepsAutoExtensible = false;
    private String uiType = "old";
    private int sessionWindowSize = 8;
    private int summaryWindowSize = 15;
    private int summaryWindowToken = 15000;
    private boolean sandboxMode = true;
    private boolean thinkPrinted = false;
    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;
    private boolean cliEnabled = true;
    private boolean cliPrintSimplified = true;
    private boolean webEnabled = false;
    private String webEndpoint = "/cli";
    private boolean acpEnabled = false;
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";

    private Map<String, String> skillPools;

    private Map<String, McpServerParameters> mcpServers;
    private Map<String, ApiSource> restApis;

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

    public static URL getConfigUrl() throws MalformedURLException {
        //1. 资源文件（一般开发时）
        URL tmp = ResourceUtil.getResource(AgentRuntime.NAME_CONFIG);
        if (tmp != null) {
            return tmp;
        }

        //2. 工作区配置
        Path path = Paths.get(AgentProperties.getUserDir(), AgentRuntime.SOLONCODE, AgentRuntime.NAME_CONFIG);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //3. 用户目录区配置
        path = Paths.get(AgentProperties.getUserHome(), AgentRuntime.SOLONCODE_BIN, AgentRuntime.NAME_CONFIG);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //4. 程序边上的配置文件
        tmp = ResourceUtil.getResourceByFile(AgentRuntime.NAME_CONFIG);
        if (tmp != null) {
            return tmp;
        }

        return null;
    }

    public URL getAgentsUrl() throws MalformedURLException {
        //1. 工作区配置
        Path path = Paths.get(getWorkDir(), AgentRuntime.SOLONCODE, AgentRuntime.NAME_AGENTS);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //2. 用户目录区配置
        path = Paths.get(AgentProperties.getUserHome(), AgentRuntime.SOLONCODE_BIN, AgentRuntime.NAME_AGENTS);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //3. 程序边上的配置文件
        URL tmp = ResourceUtil.getResourceByFile(AgentRuntime.NAME_CONFIG);
        if (tmp != null) {
            return tmp;
        }

        return null;
    }
}