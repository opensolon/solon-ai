# solon-ai-repo-zvec

基于 [Zvec](https://zvec.org)（阿里开源的进程内向量数据库）的向量存储知识库实现。

## 功能特性

- 进程内（嵌入式）运行，无需部署服务端，数据落在本地目录
- 支持 HNSW / Flat / IVF / DiskANN 等向量索引
- 支持元数据字段强类型列与倒排索引（过滤下推）
- 支持批量写入、进度回调、幂等更新（upsert）
- 支持 COSINE / L2 / IP 距离度量

## 环境要求

- Java 8+
- 支持平台：Linux x86_64/arm64、macOS x86_64/arm64、Windows x86_64/arm64（原生库随 `zvec-java` 一起打包）

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-repo-zvec</artifactId>
    <version>4.2.0</version>
</dependency>
```

### 2. 使用示例

```java
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.ZvecRepository;
import org.noear.solon.ai.rag.repository.zvec.MetadataField;
import org.noear.solon.ai.rag.util.QueryCondition;

EmbeddingModel embeddingModel = EmbeddingModel.of("http://127.0.0.1:11434/api/embed")
        .provider("ollama")
        .model("bge-m3")
        .build();

// 一个 collection 对应 basePath 下的一个子目录
ZvecRepository repository = ZvecRepository.builder(embeddingModel, "./data/zvec")
        .collectionName("solon_ai")
        // 参与过滤的元数据字段（Zvec 是强类型引擎，需要声明成标量列）
        .metadataFields(
                MetadataField.text("category"),
                MetadataField.int32("year"))
        .build();

// 写入（自动 embedding）
repository.save(new Document("solon 是一个 Java 应用开发框架")
        .metadata("category", "framework")
        .metadata("year", 2018));

// 优化索引（写入后调用一次，可提升检索速度）
repository.optimize();

// 检索
List<Document> docs = repository.search("Java 框架");

// 带过滤的检索（category 与 year 已声明为标量列，会下推给 Zvec）
List<Document> docs2 = repository.search(new QueryCondition("框架")
        .filterExpression("category == 'framework' && year > 2015")
        .limit(10));

// 删除
repository.deleteById("doc-id");
```

## 说明

- **强类型元数据**：Zvec 的过滤表达式只能引用 schema 中声明过的标量列。未在 `metadataFields(...)` 中声明的元数据仍然会以 JSON 形式完整保存与回读，但无法用于过滤（表达式里出现未知列时，本实现会自动退化为“先放宽召回、再内存二次过滤”）。
- **相似度换算**：Zvec 返回的是距离值，本实现按度量类型换算成“越大越相似”的评分
  - `COSINE`：`1 - 距离`
  - `L2`：`1 / (1 + 距离)`
  - `IP`：直接使用内积值
- **过滤下推范围**：`==`、`!=`、`>`、`>=`、`<`、`<=`、`&&`、`||`、`in`、`not in` 会下推；`like`（SnEL 与 Zvec 的通配符写法不同）与 `NOT`（Zvec 表达式无该语法）不下推，走内存二次过滤，结果仍然正确。
- **同一目录只能打开一次**：Zvec 的集合目录里带读写锁，一个进程内重复打开会报 `Can't lock read-write collection`。本实现以绝对路径缓存集合句柄（`zvec.CollectionRegistry`），指向同一路径的多个 `ZvecRepository` 共用同一个句柄。
- **生命周期**：`build()` 时自动初始化（目录已存在则打开，否则新建）；`dropRepository()` 会删除磁盘数据。
- **Zvec 初始化**：`build()` 会自动调用一次 `Zvec.initialize(null)`（幂等），无需手动初始化。
- **原生库定位**：默认使用 jar 内打包的原生库；如需使用本地构建，可设置 `-Dzvec.native.path=/path/to/zvec/build/lib` 或环境变量 `ZVEC_NATIVE_PATH`。
- **平台限制**：Zvec 是本地库，不支持 GraalVM native-image。
