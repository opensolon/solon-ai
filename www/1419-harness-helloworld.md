---
title: "harness - helloworld"
---

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness</artifactId>
</dependency>
```


理论上可能内置到任意 Java 项目中。具体开发时，可以直接使用，或者参考它的实现代码。//后续会进一步丰富文档


### 1、示意代码


代码配置类：

* HarnessEngine.Builder，用于配置智能体马具特性，具体可参考：[《config.yml 配置详解》](/article/1407)
* AgentDefinition，用于定义子代理（主要涉及系统提示词，与工具权限）


```java

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.talents.mount.MountType;

public class DemoApp {
    public static void main(String[] arg) throws Throwable {
        //--- 1. 初始化
        HarnessEngine engine = HarnessEngine.of("/data/work/", ".tmp")
                .systemPrompt("xxx")
                .sessionProvider(InMemoryAgentSession::of)
                .toolsAdd(ToolPermission.TOOL_ALL_FULL) //设定工具权限
                .disallowedToolsAdd(ToolPermission.TOOL_ALL_FULL)
                .mountAdd(MountDir.builder()
                        .alias("@global-agents")
                        .type(MountType.AGENTS)
                        .path("~/.soloncode/agents/")
                        .primary(true)
                        .build())
                .modelAdd(new ChatConfig().then(slf -> {
                    slf.setApiUrl("https://api.deepseek.com");
                    slf.setApiKey("sk-***");
                    slf.setModel("deepseek-v4-flash");
                })) //设定大模型配置
                .extensionAdd((name, builder) -> {
                    //...
                })
                .build();

        //engine.addMcpServer("name", ...);

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
                    //o.chatModel(engine.getMainModel());

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
```
