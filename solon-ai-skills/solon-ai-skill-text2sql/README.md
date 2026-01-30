# solon-ai-skill-text2sql

solon-ai-skill-text2sql 是基于 Solon AI 框架封装的数据库交互技能模块。

它通过 JDBC 自动提取数据库元数据，并将其转化为自然语言模型（LLM）可理解的 Schema 上下文，使 Agent 具备精准的 Text-to-SQL 转化与执行能力。


### 功能特性

* 元数据自动感知：自动抓取表结构、主键（PK）、外键（FK）以及字段注释（Remarks），构建高内聚的 Prompt 上下文。
* ReAct 模式集成：完美适配 ReActAgent，支持“思考-行动-观察”循环，能够根据 SQL 执行报错进行自愈（Self-Correction）。
* 多方言适配：支持 H2、MySQL、PostgreSQL、Dameng（达梦）、Kingbase（金仓）等多种数据库方言，并能动态提示模型注意特定方言的保留字冲突。
* 内置安全机制：仅允许只读 SELECT 操作，严禁任何增删改 DDL/DML 语句。
* 灵活的表范围控制：支持按需注入表清单，避免无关表结构干扰模型的注意力窗口。

### 快速接入

#### 1. 引入依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-skill-text2sql</artifactId>
</dependency>
```

#### 2. 应用示例

在使用时，只需注入 DataSource 并指定需要 AI 关注的表名。

```java
// 实例化技能：指定受控的数据源和表名
Text2SqlSkill sqlSkill = new Text2SqlSkill(dataSource, "users", "orders", "order_refunds")
        .maxRows(50); // 限制返回行数，保护内存

// 构发建 Agent 或 ChatModel
SimpleAgent agent = SimpleAgent.of(chatModel)
        .role("财务数据分析师")
        .instruction("你负责分析订单与退款数据。金额单位均为元。")
        .defaultSkillAdd(sqlSkill) // 注入 SQL 技能
        .maxSteps(10)               // 允许 Agent 在 SQL 报错时有足够的重试空间
        .build();

// 发起自然语言查询
ReActResponse resp = agent.prompt("去年消费最高的 VIP 客户是谁？").call();
```