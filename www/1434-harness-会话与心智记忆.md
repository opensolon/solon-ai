---
title: "harness - 会话与心智记忆"
---

会话（Session）保存一次对话的上下文，心智记忆（Memory）则在多次对话之间长期留存关键事实。两者配合，让 Agent 既有“短期记忆”也有“长期记忆”。

### 1、会话提供者（sessionProvider）

`sessionProvider` 是构建引擎的必填项，决定会话如何创建与存储。最简单的是内存实现：

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(InMemoryAgentSession::of)   // 内存会话（进程级，重启即失）
        .build();
```

获取/使用会话：

```java
AgentSession session = engine.getSession("default");  // 按实例 id 获取

engine.prompt("hello")
        .session(session)            // 不传则为临时会话（不留历史）
        .call();
```

> 需要持久化会话时，实现自定义的 `AgentSessionProvider`，把会话落到文件/数据库即可。会话相关的运行态数据默认落在 `{harnessHome}/sessions/` 下。

### 2、会话窗口与上下文压缩

为控制上下文长度，引擎提供两层机制（详见 [《harness - 配置参考》](/article/1427)）：

* `sessionWindowSize`：新指令携带几条历史消息（默认 8）。
* `compressionThreshold(maxMessages, maxTokens)`：消息条数或内容长度超阈值时，自动压缩历史上下文。

### 3、心智记忆（Memory）

心智记忆让 Agent 把用户偏好、项目规约等关键事实长期保存，并在需要时检索召回。需满足两个条件：`memoryEnabled=true`（默认开启）且已通过 `memorySolution(...)` 配置记忆方案。

记忆的存取由 `MemorySolution` 定义，它组合了两个能力：

* `MemoryStoreProvider`：物理持久化与 TTL 管理。
* `MemorySearchProvider`：语义检索与热记忆提取。

通过 `memorySolution(Factory)` 注入，工厂按工作区（`cwd`）返回对应方案，从而支持多租户/多项目隔离。框架内置了零外部依赖的 Markdown 方案 `MemorySolutionMdImpl`（构造入参为存储目录的 `Path`）：

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(InMemoryAgentSession::of)
        .memorySolution(cwd -> new MemorySolutionMdImpl(Paths.get(cwd, "memory_md")))  // 按工作区构建记忆方案
        .build();
```

`MemorySolutionMdImpl` 把记忆以 MD 文件存储，Store 与 Search 共享同一份内存数据（`MemoryMdData`），写入即更新索引：启动时全量加载已有 MD，并可开启后台过期清理。其 `getStoreProvider()` / `getSearchProvider()` 分别返回 `MemoryStoreProviderMdImpl` 与 `MemorySearchProviderMdImpl`。

如需自定义存储/检索，只要实现 `MemorySolution` 接口的两个方法即可：

```java
public interface MemorySolution {
    MemoryStoreProvider getStoreProvider();    // 物理持久化与 TTL
    MemorySearchProvider getSearchProvider();  // 语义检索与热记忆提取
}
```

> 除 MD 外，记忆模块还提供 Redis、Lucene、Repository（向量库）等 Store/Search 实现，可按需组合自己的 `MemorySolution`。

### 4、记忆能力

配置记忆方案后，Agent 可自主进行：提取（写入事实）、召回（按 key 精确取）、语义检索、认知整合（碎片升维合并）与修剪（删除过时认知）。记忆条目带重要度评分，便于按价值排序与管理。
