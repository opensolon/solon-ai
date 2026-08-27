# solon-ai-router

`solon-ai-router` 在创建聊天请求时，从一组已经构建完成的 `ChatModel` 中选择一个物理模型，并原样返回该模型创建的 `ChatRequestDesc`。

它只作用于 `ChatModel`，不接入 Agent、Flow 或 Harness。选路完成后的 `session()`、`role()`、`instruction()`、`systemPrompt()`、`options()`、`call()` 和 `stream()` 都由目标模型原有实现处理。

## 引入依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-router</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

## 基本使用

```java
import org.noear.solon.ai.router.ChatModelRoute;
import org.noear.solon.ai.router.ChatModelRouter;
import org.noear.solon.ai.router.strategy.RoundRobinRoutingStrategy;

import java.util.Arrays;

ChatModelRouter router = new ChatModelRouter(Arrays.asList(
        new ChatModelRoute("fast", "快速、低成本的常规问答模型", 1, fastModel),
        new ChatModelRoute("reasoning", "适合复杂分析和多步推理的模型", 1, reasoningModel)
), new RoundRobinRoutingStrategy());

ChatResponse response = router.prompt("分析这个并发问题")
        .options(options -> options.temperature(0.2F))
        .call();
```

`ChatModelRouter` 提供与 `ChatModel` 相同形态的四个入口：

```java
router.prompt(prompt);
router.prompt(messages);
router.prompt(systemMessage, userMessage);
router.prompt("user message");
```

Router 在 `prompt(...)` 时完成选路。仅创建请求但没有执行 `call()` 或 `stream()`，仍会消耗一次轮询位置。

## 内置策略

### 普通轮询

```java
new RoundRobinRoutingStrategy()
```

按候选注册顺序循环选择，内部使用原子游标，可被多个线程共享。

### 平滑加权轮询

```java
new WeightedRoundRobinRoutingStrategy()
```

使用 `ChatModelRoute` 的 `weight` 执行平滑加权轮询，不复制候选列表。一个策略实例首次使用后会固定候选 ID、顺序和权重。

### 规则路由

```java
RuleBasedRoutingStrategy strategy = new RuleBasedRoutingStrategy(Arrays.asList(
        new RoutingRule("reasoning", context -> {
            String content = context.getPrompt().getUserContent();
            return content != null && content.contains("分析");
        }),
        new RoutingRule("fast", context -> true)
));
```

规则按注册顺序执行，第一个匹配项胜出。若没有规则匹配，策略直接抛出 `RoutingException`；如需覆盖全部请求，应显式添加最后一条匹配规则。

### 智能路由

```java
ChatModelRouter router = new ChatModelRouter(Arrays.asList(
        new ChatModelRoute("fast", "常规问答", 3, fastModel),
        new ChatModelRoute("reasoning", "复杂推理", 1, reasoningModel)
), new SmartRoutingStrategy(classifierModel));

ChatResponse response = router.prompt("分析并发问题")
        .options(options -> options.temperature(0.2F))
        .call();
```

智能策略使用独立的普通 `ChatModel` 进行一次同步分类，并通过 `RoutingDecision` 输出架构取得 `routeId` 和 `reasoning`。它会增加一次模型调用的成本和延迟。分类失败、输出非法或返回未知候选时，请求直接失败，不会调用业务模型。

## 显式路由

Prompt 可以通过固定属性绕过策略：

```java
Prompt prompt = Prompt.of("生成摘要")
        .attrPut(ChatModelRouter.ATTR_ROUTE_ID, "fast");

router.prompt(prompt).call();
```

显式 ID 必须是已注册的非空字符串。未知或非法 ID 会直接抛出 `RoutingException`，不会退回配置策略。

## 错误语义

- Router 不提供默认候选、失败转移或静默 fallback。
- 策略决策为空、routeId 为空或候选不存在时立即失败。
- 智能分类失败时不会尝试业务候选。
- 目标模型 `call()` 和 `stream()` 的异常保持原类型与传播方式。
