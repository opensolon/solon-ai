package demo.ai.harness;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.lsp.LspServerParameters;

import java.util.Arrays;

public class DemoApp {
    public static void main(String[] arg) throws Throwable {
        //--- 1. 初始化
        HarnessEngine engine = HarnessEngine.of("/data/work/", ".tmp")
                .systemPrompt("xxx")
                .sessionProvider(InMemoryAgentSession::of)
                .toolsAdd(ToolPermission.TOOL_ALL_FULL) //设定工具权限
                .disallowedToolsAdd(ToolPermission.TOOL_ALL_FULL)
                .mountAdd(new MountDir("@global-agents", MountType.SUBAGENTS, "~/.soloncode/agents/", true))
                .modelAdd(new ChatConfig().then(slf -> {
                    slf.setApiUrl("https://api.deepseek.com");
                    slf.setApiKey("sk-***");
                    slf.setModel("deepseek-v4-flash");
                })) //设定大模型配置
                .extensionAdd((name, builder) -> {
                    //...
                })
                .mcpServerAdd("xxx", null)
                //--- 配置 LSP 服务器（按需启用，提供代码智能补全、跳转定义、诊断等能力）
                .lspServerAdd("java", new LspServerParameters(
                        Arrays.asList("jdtls", "-data", ".solon/lsp/java-workspace"),
                        Arrays.asList(".java")
                ))
                .lspServerAdd("typescript", new LspServerParameters(
                        Arrays.asList("typescript-language-server", "--stdio"),
                        Arrays.asList(".ts", ".tsx", ".js", ".jsx")
                ))
                .lspServerAdd("go", new LspServerParameters(
                        Arrays.asList("gopls"),
                        Arrays.asList(".go")
                ))
                .lspServerAdd("python", new LspServerParameters(
                        Arrays.asList("pylsp"),
                        Arrays.asList(".py", ".pyi")
                ))
                .lspServerAdd("rust", new LspServerParameters(
                        Arrays.asList("rust-analyzer"),
                        Arrays.asList(".rs")
                ))
                .lspServerAdd("clangd", new LspServerParameters(
                        Arrays.asList("clangd", "--background-index"),
                        Arrays.asList(".c", ".cpp", ".cc", ".h", ".hpp")
                ))
                .build();

        //engine.getMcpGatewayTalent().addMcpServer(...);

        //--- 用主代理执行
        useCsae1(engine, "hello");

        //--- 动态创建子代理执行（好处理可以动态创建不同的工具权限）
        useCase2(engine, "hello");

    }

    private static void dynamicUpdate1(HarnessEngine engine) {
        //动态更新示例
    }

    private static void useCsae1(HarnessEngine engine, String prompt) throws Throwable {
        AgentSession session = engine.getSession("default");

        //--- 用主代理模式
        engine.prompt(prompt)
                .session(session) //没有，则为临时会话
                .options(o -> {

                    //切换大模型
                    o.chatModel(engine.getMainModel());

                    //按需，动态指定工作区（没有，则为默认工作区）
                    o.toolContextPut(HarnessEngine.ATTR_CWD, "xxx");
                })
                .call();
    }

    private static void useCase2(HarnessEngine engine, String prompt) throws Throwable {
        AgentSession session = engine.getSession("default");

        //动态定义智能体
        AgentDefinition definition = new AgentDefinition();
        definition.setSystemPrompt("xxx"); //系统提示词
        definition.getMetadata().addTools(ToolPermission.TOOL_BASH); //工具权限

        ReActAgent subagent = engine.createSubagent(definition).build();
        subagent.prompt(prompt)
                .session(session) //没有，则为临时会话
                .options(o -> {
                    //按需，动态指定工作区（没有，则为默认工作区）
                    o.toolContextPut(HarnessEngine.ATTR_CWD, "xxx");
                })
                .call();
    }
}