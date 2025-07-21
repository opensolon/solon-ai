# solon-ai-repo-pgvector

基于 PostgreSQL pgvector 扩展的向量存储知识库实现。

## 功能特性

- 支持向量相似度搜索
- 支持元数据字段索引和过滤
- 支持批量操作
- 支持连接池管理
- 支持自定义表名和字段配置
- 支持 UPSERT 操作

## 环境要求

- PostgreSQL 11+
- pgvector 扩展
- Java 8+

## 快速开始

### 1. 启动 PostgreSQL 和 pgvector

使用 Docker Compose 快速启动：

```bash
docker-compose up -d
```

或者手动安装 pgvector 扩展：

```sql
-- 安装 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;
```

### 2. 添加依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-repo-pgvector</artifactId>
    <version>3.4.0</version>
</dependency>
```

### 3. 配置数据库连接

在 `app.yml` 中配置：

```yaml
solon:
  ai:
    repo:
      pgvector:
        jdbcUrl: "jdbc:postgresql://localhost:5432/solon_ai_test"
        username: "postgres"
        password: "password"
```

### 4. 使用示例

```java
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.repository.PgVectorRepository;
import org.noear.solon.ai.rag.repository.pgvector.MetadataField;
import org.noear.solon.ai.rag.Document;

// 创建 EmbeddingModel
EmbeddingModel embeddingModel = new EmbeddingModel(properties);

// 定义元数据字段
List<MetadataField> metadataFields = new ArrayList<>();
metadataFields.add(MetadataField.text("title"));
metadataFields.add(MetadataField.text("category"));
metadataFields.add(MetadataField.numeric("price"));

// 创建 Repository
PgVectorRepository repository = PgVectorRepository.builder(
    embeddingModel, 
    "jdbc:postgresql://localhost:5432/solon_ai_test",
    "postgres", 
    "password"
)
.tableName("my_documents")
.metadataFields(metadataFields)
.maxPoolSize(20)
.build();

// 插入文档
Document doc = new Document("这是一个关于人工智能的文档");
doc.getMetadata().put("title", "AI");
doc.getMetadata().put("category", "technology");
doc.getMetadata().put("price", 100);

repository.insert(doc);

// 搜索文档
List<Document> results = repository.search("人工智能");

// 使用过滤条件搜索
String filterExpression = "category == 'technology' AND price > 50";
List<Document> filteredResults = repository.search(
    new QueryCondition("人工智能")
        .filterExpression(filterExpression)
        .limit(10)
);
```

## 配置选项

### Builder 配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| tableName | String | "solon_ai_documents" | 表名 |
| metadataFields | List<MetadataField> | [] | 元数据字段定义 |
| maxPoolSize | int | 10 | 最大连接池大小 |
| minIdle | int | 2 | 最小空闲连接数 |
| connectionTimeout | long | 30000 | 连接超时时间(ms) |
| idleTimeout | long | 600000 | 空闲超时时间(ms) |
| maxLifetime | long | 1800000 | 最大生命周期(ms) |

### 元数据字段类型

- `TEXT`: 文本字段，支持字符串比较和模糊匹配
- `NUMERIC`: 数值字段，支持数值比较和范围查询
- `JSON`: JSON 字段，支持复杂数据结构

## 过滤表达式语法

支持以下操作符：

- 比较操作符：`==`, `!=`, `>`, `>=`, `<`, `<=`
- 逻辑操作符：`AND`, `OR`, `NOT`
- 集合操作符：`IN`, `NOT IN`

示例：

```java
// 简单比较
"title == 'AI'"

// 数值比较
"price > 100"

// 逻辑组合
"category == 'technology' AND price > 50"

// 集合操作
"title IN ['AI', 'ML', 'DL']"

// 复杂表达式
"(category == 'technology' OR category == 'science') AND price > 50"
```

## 性能优化

1. **索引优化**：自动创建向量索引，支持余弦相似度搜索
2. **连接池**：使用 HikariCP 连接池，提高连接效率
3. **批量操作**：支持批量插入和删除
4. **参数化查询**：使用 PreparedStatement 防止 SQL 注入

## 注意事项

1. 确保 PostgreSQL 已安装 pgvector 扩展
2. 向量维度需要与 EmbeddingModel 的维度一致
3. 大量数据插入时建议使用批量操作
4. 定期维护数据库索引以保持查询性能

## 测试

运行测试前需要启动 PostgreSQL 服务：

```bash
# 启动测试数据库
docker-compose up -d

# 运行测试
mvn test
```

## 许可证

Apache License 2.0 