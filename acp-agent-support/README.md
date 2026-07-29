# ACP Agent Support Module

The `acp-agent-support` module provides an annotation-based programming model for building ACP agents. It eliminates boilerplate code while maintaining full compatibility with the builder-based API from `acp-core`.

## Quick Start

```java
@AcpAgent
class MyAgent {

    @Initialize
    InitializeResponse init() {
        return InitializeResponse.ok();
    }

    @NewSession
    NewSessionResponse newSession(NewSessionRequest req) {
        return new NewSessionResponse("session-" + UUID.randomUUID(), null, null);
    }

    @Prompt
    PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
        ctx.sendMessage("Processing your request...");
        return PromptResponse.text("Done!");
    }
}

// Bootstrap and run
AcpAgentSupport.create(new MyAgent())
    .transport(StdioAcpAgentTransport.create())
    .run();
```

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-agent-support</artifactId>
    <version>${acp.version}</version>
</dependency>
```

This module transitively includes `acp-annotations` and `acp-core`.

## Annotations

### Class-Level

| Annotation | Description |
|------------|-------------|
| `@AcpAgent` | Marks a class as an ACP agent. Required on all agent classes. Optional `name` and `version` attributes. |

### Handler Methods

| Annotation | JSON-RPC Method | Description |
|------------|-----------------|-------------|
| `@Initialize` | `initialize` | Handles protocol initialization and capability negotiation. |
| `@NewSession` | `session/new` | Creates a new agent session. |
| `@LoadSession` | `session/load` | Loads an existing session by ID. |
| `@Prompt` | `session/prompt` | Handles user prompts within a session. |
| `@SetSessionMode` | `session/set_mode` | Changes the operational mode of a session. |
| `@SetSessionModel` | `session/set_model` | Changes the AI model used for a session. |
| `@Cancel` | `session/cancel` | Handles cancellation notifications (fire-and-forget). |

### Parameter Annotations

| Annotation | Description |
|------------|-------------|
| `@SessionId` | Injects the current session ID as a `String`. |
| `@SessionState` | Injects session-specific state (placeholder for future). |

### Exception Handling

| Annotation | Description |
|------------|-------------|
| `@AcpExceptionHandler` | Marks a method as an exception handler (runtime support pending). |

## Handler Method Signatures

Handler methods support flexible signatures. The runtime automatically resolves parameters based on their types:

### Supported Parameter Types

| Parameter Type | Source |
|----------------|--------|
| `InitializeRequest` | The raw initialize request (in `@Initialize` handlers). |
| `NewSessionRequest` | The raw new session request (in `@NewSession` handlers). |
| `LoadSessionRequest` | The raw load session request (in `@LoadSession` handlers). |
| `PromptRequest` | The raw prompt request (in `@Prompt` handlers). |
| `SetSessionModeRequest` | The raw set mode request (in `@SetSessionMode` handlers). |
| `SetSessionModelRequest` | The raw set model request (in `@SetSessionModel` handlers). |
| `CancelNotification` | The raw cancel notification (in `@Cancel` handlers). |
| `SyncPromptContext` | Synchronous context for sending messages, file I/O, permissions, etc. |
| `NegotiatedCapabilities` | The capabilities negotiated with the client. |
| `@SessionId String` | The current session ID. |

### Example Handler Signatures

```java
@Initialize
InitializeResponse init() { ... }

@Initialize
InitializeResponse init(InitializeRequest req) { ... }

@Prompt
PromptResponse answer(PromptRequest req) { ... }

@Prompt
PromptResponse answer(PromptRequest req, SyncPromptContext ctx) { ... }

@Prompt
PromptResponse answer(SyncPromptContext ctx, @SessionId String sessionId) { ... }

@Prompt
String simpleAnswer(PromptRequest req) { ... }  // Converted to PromptResponse

@Prompt
void streamingAnswer(PromptRequest req, SyncPromptContext ctx) { ... }  // Returns endTurn()

@Cancel
void onCancel(CancelNotification notification) { ... }
```

## Return Value Handling

The runtime automatically converts return values to protocol response types:

| Return Type | Conversion |
|-------------|------------|
| `InitializeResponse` | Passed through directly. |
| `NewSessionResponse` | Passed through directly. |
| `LoadSessionResponse` | Passed through directly. |
| `PromptResponse` | Passed through directly. |
| `SetSessionModeResponse` | Passed through directly. |
| `SetSessionModelResponse` | Passed through directly. |
| `String` | Converted to `PromptResponse.text(value)`. |
| `void` | Converted to `PromptResponse.endTurn()`. |
| `Mono<PromptResponse>` | Unwrapped and returned (for async handlers). |

## Using SyncPromptContext

`SyncPromptContext` provides a rich API for agent-client interaction:

```java
@Prompt
PromptResponse handle(PromptRequest req, SyncPromptContext ctx) {
    // Get session info
    String sessionId = ctx.getSessionId();
    NegotiatedCapabilities caps = ctx.getClientCapabilities();

    // Send messages and thoughts
    ctx.sendMessage("Working on it...");
    ctx.sendThought("Let me analyze this...");

    // File operations (requires client capabilities)
    String content = ctx.readFile("/path/to/file.txt");
    ctx.writeFile("/path/to/output.txt", "content");

    // Optional file read (returns Optional)
    Optional<String> maybeContent = ctx.tryReadFile("/path/to/file.txt");

    // Ask for user permission
    boolean allowed = ctx.askPermission("Delete all files in /tmp?");

    // Multiple choice
    String choice = ctx.askChoice("Which format?", "JSON", "XML", "YAML");

    // Execute terminal commands (requires client capabilities)
    CommandResult result = ctx.execute("ls", "-la");
    if (result.exitCode() == 0) {
        ctx.sendMessage("Output: " + result.output());
    }

    return PromptResponse.endTurn();
}
```

## Interceptors

Interceptors allow cross-cutting concerns like logging, metrics, or error handling:

```java
public class LoggingInterceptor implements AcpInterceptor {

    @Override
    public boolean preInvoke(AcpInvocationContext context) {
        log.info("Invoking: {}", context.getAcpMethod());
        return true;  // Continue processing
    }

    @Override
    public Object postInvoke(AcpInvocationContext context, Object result) {
        log.info("Result: {}", result);
        return result;
    }

    @Override
    public Object onError(AcpInvocationContext context, Throwable error) {
        log.error("Error in {}: {}", context.getAcpMethod(), error.getMessage());
        return null;  // Return null to re-throw, or return a replacement value
    }

    @Override
    public void afterCompletion(AcpInvocationContext context, Throwable error) {
        // Always called, even if exceptions occur
    }

    @Override
    public int getOrder() {
        return 0;  // Lower values execute first
    }
}

// Register interceptor
AcpAgentSupport.create(new MyAgent())
    .transport(transport)
    .interceptor(new LoggingInterceptor())
    .build();
```

## Custom Argument Resolvers

Extend argument resolution for custom parameter types:

```java
public class UserResolver implements ArgumentResolver {

    @Override
    public boolean supportsParameter(AcpMethodParameter parameter) {
        return parameter.getParameterType() == User.class;
    }

    @Override
    public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
        String sessionId = context.getSessionId();
        return userService.findBySession(sessionId);
    }
}

// Register resolver
AcpAgentSupport.create(new MyAgent())
    .transport(transport)
    .argumentResolver(new UserResolver())
    .build();
```

## Custom Return Value Handlers

Handle custom return types:

```java
public class CompletableFutureHandler implements ReturnValueHandler {

    @Override
    public boolean supportsReturnType(AcpMethodParameter returnType) {
        return CompletableFuture.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object handleReturnValue(Object returnValue, AcpMethodParameter returnType,
            AcpInvocationContext context) {
        CompletableFuture<?> future = (CompletableFuture<?>) returnValue;
        return future.join();  // Block and return result
    }
}

// Register handler
AcpAgentSupport.create(new MyAgent())
    .transport(transport)
    .returnValueHandler(new CompletableFutureHandler())
    .build();
```

## Transport Configuration

### Stdio Transport (Default for CLI Agents)

```java
AcpAgentSupport.create(new MyAgent())
    .transport(StdioAcpAgentTransport.create())
    .run();
```

### WebSocket Transport

```java
WebSocketAcpAgentTransport transport = WebSocketAcpAgentTransport.builder()
    .host("localhost")
    .port(8080)
    .path("/acp")
    .build();

AcpAgentSupport.create(new MyAgent())
    .transport(transport)
    .run();
```

### InMemory Transport (For Testing)

```java
InMemoryTransportPair pair = InMemoryTransportPair.create();

AcpAgentSupport support = AcpAgentSupport.create(new MyAgent())
    .transport(pair.agentTransport())
    .build();

support.start();

// Create client using the paired transport
AcpAsyncClient client = AcpClient.async(pair.clientTransport()).build();
```

## Builder API Reference

```java
AcpAgentSupport.create(agentInstance)      // Start with agent instance
    .transport(transport)                   // Required: set transport
    .requestTimeout(Duration.ofSeconds(60)) // Optional: request timeout (default: 30s)
    .interceptor(interceptor)               // Optional: add interceptor
    .argumentResolver(resolver)             // Optional: add custom resolver
    .returnValueHandler(handler)            // Optional: add custom handler
    .build();                               // Build the support instance
```

### Alternative Creation Methods

```java
// From class (must have no-arg constructor)
AcpAgentSupport.create(MyAgent.class)

// With factory supplier
AcpAgentSupport.create(MyAgent.class, () -> new MyAgent(dependency))
```

### Running the Agent

```java
AcpAgentSupport support = AcpAgentSupport.create(new MyAgent())
    .transport(transport)
    .build();

// Option 1: Non-blocking start
support.start();
// ... do other work ...
support.close();

// Option 2: Blocking run (blocks until closed)
support.run();
```

## Complete Example

```java
@AcpAgent(name = "code-assistant", version = "1.0.0")
class CodeAssistant {

    private final Map<String, List<String>> sessionHistory = new ConcurrentHashMap<>();

    @Initialize
    InitializeResponse init(InitializeRequest req) {
        // Customize response based on client capabilities
        return InitializeResponse.ok();
    }

    @NewSession
    NewSessionResponse newSession(NewSessionRequest req) {
        String sessionId = UUID.randomUUID().toString();
        sessionHistory.put(sessionId, new ArrayList<>());
        return new NewSessionResponse(sessionId, List.of(), List.of());
    }

    @LoadSession
    LoadSessionResponse loadSession(LoadSessionRequest req) {
        if (!sessionHistory.containsKey(req.sessionId())) {
            throw new AcpProtocolException(AcpErrorCodes.SESSION_NOT_FOUND,
                "Session not found: " + req.sessionId());
        }
        return new LoadSessionResponse(List.of(), List.of());
    }

    @Prompt
    PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
        String sessionId = ctx.getSessionId();
        sessionHistory.get(sessionId).add(extractText(req));

        ctx.sendThought("Analyzing the code...");

        // Check if we can read files
        if (ctx.getClientCapabilities().readTextFile()) {
            ctx.sendMessage("I can access files if needed.");
        }

        ctx.sendMessage("Here's my analysis...");
        return PromptResponse.endTurn();
    }

    @SetSessionMode
    SetSessionModeResponse setMode(SetSessionModeRequest req) {
        return new SetSessionModeResponse();
    }

    @Cancel
    void onCancel(CancelNotification notification, @SessionId String sessionId) {
        // Clean up any long-running operations
        log.info("Cancelled session: {}", sessionId);
    }

    private String extractText(PromptRequest req) {
        return req.prompt().stream()
            .filter(c -> c instanceof TextContent)
            .map(c -> ((TextContent) c).text())
            .collect(Collectors.joining("\n"));
    }
}

public class Main {
    public static void main(String[] args) {
        AcpAgentSupport.create(new CodeAssistant())
            .transport(StdioAcpAgentTransport.create())
            .interceptor(new MetricsInterceptor())
            .run();
    }
}
```

## Migration from Builder API

The annotation-based API provides the same functionality as the builder API with less boilerplate:

### Builder API (Before)

```java
AcpAgent.sync(transport)
    .initializeHandler(req -> InitializeResponse.ok())
    .newSessionHandler(req -> new NewSessionResponse("session-1", null, null))
    .promptHandler((req, ctx) -> {
        ctx.sendMessage("Hello!");
        return PromptResponse.endTurn();
    })
    .build()
    .run();
```

### Annotation API (After)

```java
@AcpAgent
class MyAgent {
    @Initialize InitializeResponse init() { return InitializeResponse.ok(); }
    @NewSession NewSessionResponse newSession() { return new NewSessionResponse("session-1", null, null); }
    @Prompt PromptResponse prompt(SyncPromptContext ctx) {
        ctx.sendMessage("Hello!");
        return PromptResponse.endTurn();
    }
}

AcpAgentSupport.create(new MyAgent()).transport(transport).run();
```

Both approaches produce identical runtime behavior and can coexist in the same application.

## Architecture

```
acp-agent-support
├── AcpAgentSupport          # Bootstrap and builder
├── AcpHandlerMethod         # Method + bean encapsulation
├── AcpMethodParameter       # Parameter metadata
├── AcpInvocationContext     # Request context during invocation
├── resolver/                # Argument resolvers
│   ├── ArgumentResolver     # Interface
│   ├── ArgumentResolverComposite
│   ├── PromptRequestResolver
│   ├── PromptContextResolver
│   ├── SessionIdResolver
│   └── ...
├── handler/                 # Return value handlers
│   ├── ReturnValueHandler   # Interface
│   ├── ReturnValueHandlerComposite
│   ├── DirectResponseHandler
│   ├── StringToPromptResponseHandler
│   ├── VoidHandler
│   └── MonoHandler
└── interceptor/             # Interceptor chain
    ├── AcpInterceptor       # Interface
    └── InterceptorChain     # Execution chain
```

## Dependencies

- `acp-annotations` - Zero-dependency annotation definitions
- `acp-core` - Core SDK with transport, schema, and client/agent APIs
- SLF4J - Logging facade
- Project Reactor - Reactive streams (from acp-core)
