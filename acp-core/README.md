# acp-core

Core implementation of the Agent Client Protocol for Java. Provides client and agent APIs, protocol types, stdio transport, and Project Reactor-based async support.

## Key Classes

| Class | Purpose |
|-------|---------|
| `AcpClient` | Factory — `AcpClient.sync()` and `AcpClient.async()` |
| `AcpAgent` | Factory — `AcpAgent.sync()` and `AcpAgent.async()` |
| `AcpSchema` | All protocol types (requests, responses, updates) |
| `StdioAcpClientTransport` | Launches agent as subprocess, communicates over stdin/stdout |
| `StdioAcpAgentTransport` | Reads from stdin, writes to stdout |

## Installation

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-core</artifactId>
    <version>0.9.0</version>
</dependency>
```

## Documentation

- [API Reference](https://springaicommunity.mintlify.app/acp-java-sdk/reference/java)
- [Tutorial](https://springaicommunity.mintlify.app/acp-java-sdk/tutorial)
