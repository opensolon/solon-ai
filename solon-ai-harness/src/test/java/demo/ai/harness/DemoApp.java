package demo.ai.harness;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessProperties;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.skills.lsp.LspServerParameters;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DemoApp {
    public static void main(String[] arg) throws Throwable {
        //--- 1. 初始化
        HarnessProperties harnessProps = new HarnessProperties(".tmp/");
        harnessProps.addTools(ToolPermission.TOOL_ALL_FULL); //设定工具权限
        harnessProps.addModel(null); //设定大模型配置
        harnessProps.addExtension((name, builder) -> {
            //...
        });

        //--- 配置 LSP 服务器（按需启用，提供代码智能补全、跳转定义、诊断等能力）
        harnessProps.addLspServer("java", new LspServerParameters(
                Arrays.asList("jdtls", "-data", ".solon/lsp/java-workspace"),
                Arrays.asList(".java")
        ));
        harnessProps.addLspServer("typescript", new LspServerParameters(
                Arrays.asList("typescript-language-server", "--stdio"),
                Arrays.asList(".ts", ".tsx", ".js", ".jsx")
        ));
        harnessProps.addLspServer("go", new LspServerParameters(
                Arrays.asList("gopls"),
                Arrays.asList(".go")
        ));
        harnessProps.addLspServer("python", new LspServerParameters(
                Arrays.asList("pylsp"),
                Arrays.asList(".py", ".pyi")
        ));
        harnessProps.addLspServer("rust", new LspServerParameters(
                Arrays.asList("rust-analyzer"),
                Arrays.asList(".rs")
        ));
        harnessProps.addLspServer("clangd", new LspServerParameters(
                Arrays.asList("clangd", "--background-index"),
                Arrays.asList(".c", ".cpp", ".cc", ".h", ".hpp")
        ));

        AgentSessionProvider sessionProvider = new AgentSessionProvider() {
            private Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

            @Override
            public AgentSession getSession(String instanceId) {
                return sessionMap.computeIfAbsent(instanceId, k -> InMemoryAgentSession.of(k));
            }
        };

        HarnessEngine engine = HarnessEngine.of(harnessProps)
                .sessionProvider(sessionProvider)
                .build();

        //--- 用主代理执行
        csae1(engine, "hello");

        //--- 动态创建子代理执行（好处理可以动态创建不同的工具权限）
        case2(engine, "hello");

    }

    private static void csae1(HarnessEngine engine, String prompt) throws Throwable {
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

    private static void case2(HarnessEngine engine, String prompt) throws Throwable {
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