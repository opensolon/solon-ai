# solon-ai-repo-mariadb

基于 MariaDB 的向量存储知识库实现。

## 功能特性

- 支持 MariaDB 原生 VECTOR 数据类型
- 支持 HNSW 向量索引（高性能相似度搜索）
- 支持向量相似度搜索（欧几里得距离排序）
- 支持元数据字段索引和过滤
- 支持批量操作
- 支持连接池管理（HikariCP）
- 支持自定义表名和字段配置
- 支持 UPSERT 操作
- 自动创建表和向量索引

## 环境要求

- MariaDB 10.11+（支持 VECTOR 数据类型）
- Java 8+
- 需要 MariaDB Vector 支持

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-repo-mariadb</artifactId>
    <version>3.9.2</version>
</dependency>
```

### 2. 配置数据库连接

在 `app.yml` 中配置：

```yaml
solon:
  ai:
    repo:
      mariadb:
        jdbcUrl: "jdbc:mariadb://localhost:3306/solon_ai_test"
        username: "root"
        password: "password"
        maximumPoolSize: 20
```

### 3. 使用示例

```java
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.repository.MariaDbRepository;
import org.noear.solon.ai.rag.repository.mariadb.MetadataField;
import org.noear.solon.ai.rag.Document;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;

// 创建 DataSource
HikariDataSource dataSource = new HikariDataSource();
dataSource.setJdbcUrl("jdbc:mariadb://localhost:3306/solon_ai_test");
dataSource.setUsername("root");
dataSource.setPassword("password");

// 创建 EmbeddingModel
EmbeddingModel embeddingModel = new EmbeddingModel(properties);

// 定义元数据字段
List<MetadataField> metadataFields = new ArrayList<>();
metadataFields.add(MetadataField.text("title"));
metadataFields.add(MetadataField.text("category"));
metadataFields.add(MetadataField.numeric("price"));

// 创建 Repository
MariaDbRepository repository = MariaDbRepository.builder(
    embeddingModel,
    dataSource
)
.tableName("my_documents")
.metadataFields(metadataFields)
.build();

// 插入文档
Document doc = new Document("这是一个关于人工智能的文档");
doc.getMetadata().put("title", "AI");
doc.getMetadata().put("category", "technology");
doc.getMetadata().put("price", 100);

repository.save(Collections.singletonList(doc));

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

## MariaDB Vector 特性

### HNSW 向量索引

模块自动创建 HNSW（Hierarchical Navigable Small World）向量索引，提供高效的近似最近邻搜索：

```sql
CREATE VECTOR INDEX `idx_{table_name}_embedding` ON `{table_name}` (`embedding`) M=6 DISTANCE=euclidean
```

### 向量函数支持

- `VECTOR(dimensions)`: 定义向量列
- `VEC_FromText()`: 将文本转换为向量
- `VEC_ToText()`: 将向量转换为文本
- `VEC_DISTANCE_EUCLIDEAN()`: 计算欧几里得距离
- `VEC_DISTANCE_COSINE()`: 计算余弦距离

### 向量精度控制

模块使用 `%.4g` 格式控制向量精度，平衡精度和存储空间，防止浮点数精度问题影响搜索准确性。

## 性能优化

1. **HNSW 索引**: 自动创建 HNSW 向量索引，加速向量相似度搜索
2. **连接池**: 使用 HikariCP 连接池，提高连接效率
3. **批量操作**: 支持批量插入和删除
4. **参数化查询**: 使用 PreparedStatement 防止 SQL 注入
5. **向量精度优化**: 4 位有效数字，减少存储空间
6. **UPSERT 支持**: 自动处理重复文档，避免插入错误

## 注意事项

1. 向量维度需要与 EmbeddingModel 的维度一致
2. 大量数据插入时建议使用批量操作
3. 定期维护数据库索引以保持查询性能
4. 需要 MariaDB 10.11+ 版本以支持 VECTOR 数据类型
5. 首次使用时会自动创建表和向量索引

## 许可证

Apache License 2.0
