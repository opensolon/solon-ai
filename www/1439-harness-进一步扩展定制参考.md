---
title: "harness - 进一步扩展定制参考"
---

`solon-ai-harness` 提供了灵活的扩展能力，允许开发者通过代码动态定制 Web API 接入、业务工具集成以及系统提示词。

### 1、主代理（Agent）定制扩展


* 动态添加 Web API (Rest API) 数据源

除了静态配置外，您可以通过代码动态注册外部 API 接口文档（如 Swagger/OpenAPI），使 Agent 具备调用外部业务系统的能力。


```java
HarnessEngine engine = HarnessEngine.of(...)
        .sessionProvider(sessionProvider)
        .build();
        
// 注册业务 API 数据源：以文档地址（docUrl）为唯一标识
engine.addApiServer(new ApiSource().then(s->{
            s.setDocUrl("http://xx.xx.xx/doc");
            s.setApiBaseUrl("http://xx.xx.xx/");
        }));
```

* 注册自定义业务工具 (Tools)

如果现有的 API 无法满足逻辑需求，可以直接将 Java 编写的业务类注册为 Agent 工具，实现复杂的计算或数据库操作。

```java
HarnessEngine engine = HarnessEngine.of(...)
                .sessionProvider(sessionProvider)
                .extensionAdd((agentName, agentBuilder) -> {
                    // 注册默认工具实例
                    agentBuilder.defaultToolAdd(new BizTool());
                })
                .build();
```

* 灵活配置系统提示词 (System Prompt)

Agent 的行为准则通常由 `{workspace}/{harnessHome}/AGENTS.md` 文件定义。在没有配置文件或需要动态生成提示词的场景下，可以使用代码进行覆盖：

```java
HarnessEngine engine = HarnessEngine.of(...)
                .sessionProvider(sessionProvider)
                .extensionAdd((agentName, agentBuilder) -> {
                    if("main".equals(agentName)) {
                        // 动态设置系统提示词逻辑
                        agentBuilder.systemPrompt(context -> "你是一个专业的业务助手...");
                    }
                })
                .build();
```


### 2、子代理（Subagent）定制扩展

子代理有两种使用场景：

* 1）被主代理调度时，不可定制，只能通过 `{workspace}/{harnessHome}/agents/xxx.md` 定义。
* 2）使用代码调度时（可以进一步定制）。

```java
AgentSession session = engine.getSession("default");

//动态定义智能体
AgentDefinition definition = new AgentDefinition();
definition.setSystemPrompt("xxx"); //系统提示词
definition.getMetadata().addTools(ToolPermission.TOOL_BASH); //工具权限

ReActAgent subagent = engine.createSubagent(definition).defaultToolAdd(new OrderTool()).build();
subagent.prompt(prompt)
        .session(session) //没有，则为临时会话
        .options(o -> {
            //按需，动态指定工作区（没有，则为默认工作区）
            o.toolContextPut(HarnessEngine.ATTR_CWD, "xxx");
        })
        .call();
```

`engine.createSubagent(definition)` 返回的是 `ReActAgent.Builder`，仍可自由定制。
