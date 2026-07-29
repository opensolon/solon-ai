# acp-annotations

Zero-dependency annotation library for declarative ACP agent development. Use with `acp-agent-support` for annotation processing at runtime.

## Annotations

| Annotation | Target | Purpose |
|-----------|--------|---------|
| `@AcpAgent` | Class | Marks a class as an ACP agent |
| `@Initialize` | Method | Handles initialization requests |
| `@NewSession` | Method | Handles new session creation |
| `@LoadSession` | Method | Handles session resume |
| `@Prompt` | Method | Handles prompt requests |
| `@Cancel` | Method | Handles cancel notifications |
| `@SessionState` | Parameter | Injects session-scoped state |
| `@SetSessionMode` | Method | Handles mode change requests |
| `@SetSessionModel` | Method | Handles model change requests |

## Installation

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-annotations</artifactId>
    <version>0.9.0</version>
</dependency>
```

## Documentation

- [Annotation-based Agent API](https://springaicommunity.mintlify.app/acp-java-sdk/reference/java#annotation-based-api)
- [Tutorial](https://springaicommunity.mintlify.app/acp-java-sdk/tutorial)
