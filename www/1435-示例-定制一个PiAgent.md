---
title: "示例：定制一个 PiAgent"
---

网上传说的 PiAgent 只有四个工具："read", "write", "edit", "bash"，我们特意定义了一个枚举（ToolPermission.TOOL_PI）方便使用。

### 1、代码示例

```java
public class DemoApp {
    public static void main(String[] arg) throws Throwable {
        AgentSessionProvider sessionProvider = new AgentSessionProvider() {
            private Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

            @Override
            public AgentSession getSession(String instanceId) {
                return sessionMap.computeIfAbsent(instanceId, k -> InMemoryAgentSession.of(k));
            }
        };

        HarnessEngine engine = HarnessEngine.of(".tmp/", ".demo")
                .sessionProvider(sessionProvider)
                .toolsAdd(ToolPermission.TOOL_PI)
                .build();

        engine.prompt("网络调查 ai mcp 协议，生成一个 mcp.md 报告").call();
    }
}
```
