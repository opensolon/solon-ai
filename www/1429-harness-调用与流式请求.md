---
title: "harness - 调用与流式请求"
---

`engine.prompt(...)` 的结果即是一个 ReActRequest 接口（和 `ReActAgent::prompt` 一样）。

具体参考：[《agent - 同步与流式响应（call 与 stream）》](/article/1355)


### 1、调用示例


```java
HarnessEngine engine = HarnessEngine.of(...)
                .sessionProvider(sessionProvider)
                .build();

engine.prompt("hello").call();
```

### 2、流式示例

```java
HarnessEngine engine = HarnessEngine.of(...)
                .sessionProvider(sessionProvider)
                .build();

engine.prompt("hello").stream();
```

### 3、指定会话与请求选项

通过 `session(...)` 绑定持久会话（不指定则为临时会话）；通过 `options(...)` 可动态切换模型或指定工作区。

```java
AgentSession session = engine.getSession("default");

engine.prompt("hello")
        .session(session)            // 没有，则为临时会话
        .options(o -> {
            //切换大模型（按模型名，不指定则用主模型）
            o.chatModel(engine.getModelOrMain("deepseek-v4-flash"));

            //按需，动态指定工作区（没有，则为默认工作区）
            o.toolContextPut(HarnessEngine.ATTR_CWD, "xxx");
        })
        .call();
```
