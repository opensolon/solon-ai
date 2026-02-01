# Chroma API v1 与 v2 差异对比文档

## 概述

Chroma 1.0+ 版本引入了全新的 v2 API，原有的 v1 API 已被标记为弃用。本文档详细对比 v1 和 v2 API 的差异，为代码迁移提供参考。

## 版本信息

| 版本 | API 版本 | 状态 | 说明 |
|------|----------|------|------|
| Chroma 0.4.x | v1 | 稳定版 | 当前代码使用的版本 |
| Chroma 1.0+ | v2 | 最新版 | 引入多租户架构，v1 API 已弃用 |

---

## 核心架构变化

### v1 架构
```
Chroma Server
  └── Collections (集合)
      └── Documents (文档)
```

### v2 架构
```
Chroma Server
  └── Tenants (租户)
      └── Databases (数据库)
          └── Collections (集合)
              └── Documents (文档)
```

### 多租户层级说明

| 层级 | 说明 | 默认值 | 用途 |
|------|------|--------|------|
| **Tenant (租户)** | 逻辑分组，代表一个组织、用户或客户 | `default_tenant` | 用于组织一组数据库，实现多租户隔离 |
| **Database (数据库)** | 逻辑分组，代表一个应用程序或项目 | `default_database` | 用于组织一组 Collection |
| **Collection (集合)** | 存储文档的集合 | 无 | 实际存储文档和向量数据的地方 |

---

## API 端点对比

### 1. 健康检查

| 功能 | v1 API | v2 API | 说明 |
|------|--------|--------|------|
| 健康检查 | `GET /api/v1/heartbeat` | `GET /api/v2/heartbeat` | 检查服务是否健康 |

**v1 请求示例：**
```http
GET http://localhost:8000/api/v1/heartbeat
```

**v2 请求示例：**
```http
GET http://localhost:8000/api/v2/heartbeat
```

---

### 2. 集合管理

#### 2.1 列出所有集合

| 功能 | v1 API | v2 API | 说明 |
|------|--------|--------|------|
| 列出集合 | `GET /api/v1/collections` | `GET /api/v2/tenants/{tenant}/databases/{database}/collections` | 获取指定租户和数据库下的所有集合 |

**v1 请求示例：**
```http
GET http://localhost:8000/api/v1/collections
```

**v2 请求示例：**
```http
GET http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections
```

#### 2.2 创建集合

| 功能 | v1 API | v2 API | 说明 |
|------|--------|--------|------|
| 创建集合 | `POST /api/v1/collections` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections` | 创建新集合 |

**v1 请求示例：**
```http
POST http://localhost:8000/api/v1/collections
Content-Type: application/json

{
  "name": "my_collection",
  "metadata": {
    "description": "My collection",
    "hnsw:space": "cosine"
  }
}
```

**v2 请求示例：**
```http
POST http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections
Content-Type: application/json

{
  "name": "my_collection",
  "metadata": {
    "description": "My collection",
    "hnsw:space": "cosine"
  }
}
```

#### 2.3 获取集合信息

| 功能 | v1 API | v2 API | 说明 |
|------|--------|--------|------|
| 获取集合 | `GET /api/v1/collections/{collection_name_or_id}` | `GET /api/v2/tenants/{tenant}/databases/{database}/collections/{collection_name_or_id}` | 获取集合详细信息 |

**v1 请求示例：**
```http
GET http://localhost:8000/api/v1/collections/my_collection
```

**v2 请求示例：**
```http
GET http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections/my_collection
```

#### 2.4 删除集合

| 功能 | v1 API | v2 API | 说明 |
|------|--------|--------|------|
| 删除集合 | `DELETE /api/v1/collections/{collection_id}` | `DELETE /api/v2/tenants/{tenant}/databases/{database}/collections/{collection_id}` | 删除指定集合 |

**v1 请求示例：**
```http
DELETE http://localhost:8000/api/v1/collections/{collection_id}
```

**v2 请求示例：**
```http
DELETE http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections/{collection_id}
```

---

### 3. 文档操作

#### 3.1 添加文档

| 功能 | v1 API | v2 API | 说明 |
|------|--------|--------|------|
| 添加文档 | `POST /api/v1/collections/{collection_id}/add` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections/{collection_id}/add` | 批量添加文档 |

**v1 请求示例：**
```http
POST http://localhost:8000/api/v1/collections/{collection_id}/add
Content-Type: application/json

{
  "ids": ["doc1", "doc2"],
  "embeddings": [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]],
  "documents": ["Document 1 content", "Document 2 content"],
  "metadatas": [
    {"source": "notion"},
    {"source": "google-docs"}
  ]
}
```

**v2 请求示例：**
```http
POST http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections/{collection_id}/add
Content-Type: application/json

{
  "ids": ["doc1", "doc2"],
  "embeddings": [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]],
  "documents": ["Document 1 content", "Document 2 content"],
  "metadatas": [
    {"source": "notion"},
    {"source": "google-docs"}
  ]
}
```

#### 3.2 查询文档

| 功能 | v1 API | v2 API | 说明 |
|------|--------|--------|------|
| 查询文档 | `POST /api/v1/collections/{collection_id}/query` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections/{collection_id}/query` | 向量相似度查询 |

**v1 请求示例：**
```http
POST http://localhost:8000/api/v1/collections/{collection_id}/query
Content-Type: application/json

{
  "query_embeddings": [[0.1, 0.2, 0.3]],
  "n_results": 5,
  "include": ["documents", "metadatas", "distances"],
  "where": {
    "source": "notion"
  }
}
```

**v2 请求示例：**
```http
POST http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections/{collection_id}/query
Content-Type: application/json

{
  "query_embeddings": [[0.1, 0.2, 0.3]],
  "n_results": 5,
  "include": ["documents", "metadatas", "distances"],
  "where": {
    "source": "notion"
  }
}
```

#### 3.3 获取文档

| 功能 | v1 API | v2 API | 说明 |
|------|--------|--------|------|
| 获取文档 | `POST /api/v1/collections/{collection_id}/get` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections/{collection_id}/get` | 根据 ID 获取文档 |

**v1 请求示例：**
```http
POST http://localhost:8000/api/v1/collections/{collection_id}/get
Content-Type: application/json

{
  "ids": ["doc1", "doc2"],
  "include": ["documents", "metadatas"]
}
```

**v2 请求示例：**
```http
POST http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections/{collection_id}/get
Content-Type: application/json

{
  "ids": ["doc1", "doc2"],
  "include": ["documents", "metadatas"]
}
```

#### 3.4 删除文档

| 功能 | v1 API | v2 API | 说明 |
|------|--------|--------|------|
| 删除文档 | `POST /api/v1/collections/{collection_id}/delete` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections/{collection_id}/delete` | 根据 ID 删除文档 |

**v1 请求示例：**
```http
POST http://localhost:8000/api/v1/collections/{collection_id}/delete
Content-Type: application/json

{
  "ids": ["doc1", "doc2"]
}
```

**v2 请求示例：**
```http
POST http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections/{collection_id}/delete
Content-Type: application/json

{
  "ids": ["doc1", "doc2"]
}
```

---

## API 端点汇总表

| 操作 | v1 API | v2 API |
|------|--------|--------|
| 健康检查 | `GET /api/v1/heartbeat` | `GET /api/v2/heartbeat` |
| 列出集合 | `GET /api/v1/collections` | `GET /api/v2/tenants/{tenant}/databases/{database}/collections` |
| 创建集合 | `POST /api/v1/collections` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections` |
| 获取集合 | `GET /api/v1/collections/{id}` | `GET /api/v2/tenants/{tenant}/databases/{database}/collections/{id}` |
| 删除集合 | `DELETE /api/v1/collections/{id}` | `DELETE /api/v2/tenants/{tenant}/databases/{database}/collections/{id}` |
| 添加文档 | `POST /api/v1/collections/{id}/add` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections/{id}/add` |
| 查询文档 | `POST /api/v1/collections/{id}/query` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections/{id}/query` |
| 获取文档 | `POST /api/v1/collections/{id}/get` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections/{id}/get` |
| 删除文档 | `POST /api/v1/collections/{id}/delete` | `POST /api/v2/tenants/{tenant}/databases/{database}/collections/{id}/delete` |

---

## 请求和响应格式

### 请求格式

v1 和 v2 的请求体格式基本保持一致，主要区别在于 URL 路径。

### 响应格式

v1 和 v2 的响应格式基本保持一致，包括：

**成功响应：**
```json
{
  "ids": [["doc1", "doc2"]],
  "documents": [["Document 1", "Document 2"]],
  "metadatas": [[{"source": "notion"}, {"source": "google-docs"}]],
  "distances": [[0.1, 0.2]]
}
```

**错误响应：**
```json
{
  "error": "Error message",
  "message": "Detailed error description"
}
```

---

## 默认配置

### v2 API 默认值

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `tenant` | `default_tenant` | 默认租户名称 |
| `database` | `default_database` | 默认数据库名称 |

**使用默认配置的 v2 API 示例：**
```
http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections
```

---

## 兼容性说明

### v1 API 状态

- **Chroma 0.4.x**: v1 API 是稳定版本
- **Chroma 1.0+**: v1 API 已标记为弃用，但可能仍然可用
- **未来版本**: v1 API 可能会被完全移除

### v2 API 状态

- **Chroma 1.0+**: v2 API 是推荐使用的版本
- **未来版本**: v2 API 将持续维护和更新

---

## 代码迁移检查清单

在从 v1 迁移到 v2 时，需要检查以下内容：

- [ ] 更新所有 API 端点路径
- [ ] 添加 tenant 和 database 配置参数
- [ ] 测试所有集合操作（创建、获取、删除）
- [ ] 测试所有文档操作（添加、查询、获取、删除）
- [ ] 验证健康检查功能
- [ ] 检查错误处理逻辑
- [ ] 更新相关文档和注释
- [ ] 进行集成测试

---

## 迁移指南

### 自动检测 API 版本（推荐）

客户端会自动检测 Chroma 服务器的 API 版本，无需手动配置。

**优点：**
- 向后兼容，支持旧版本
- 平滑过渡，降低迁移风险
- 灵活适应不同环境
- **自动检测服务器版本，优先使用 v2**
- **零配置，开箱即用**

**实现要点：**
1. 客户端构造时自动检测服务器版本
2. 优先尝试 v2 API，如果不可用则降级到 v1
3. v2 API 需要配置 `tenant` 和 `database` 参数（使用默认值）
4. 记录检测到的 API 版本，便于调试

#### 自动检测机制

客户端在初始化时会：

1. **优先尝试访问 v2 API 端点**：`GET /api/v2/heartbeat`
2. **检测响应**：如果 v2 API 响应成功，则使用 v2
3. **降级处理**：如果 v2 API 不可用，自动降级到 v1
4. **记录日志**：记录检测到的 API 版本

#### 使用方式

**基础使用（自动检测）：**
```java
// 自动检测服务器版本（推荐）
ChromaClient client = new ChromaClient("http://localhost:8000");

// 通过 Properties 配置
Properties props = new Properties();
props.setProperty("url", "http://localhost:8000");
ChromaClient client = new ChromaClient(props);
```

**自定义租户和数据库：**
```java
// 自动检测版本，但使用自定义的租户和数据库
ChromaClient client = new ChromaClient(
    "http://localhost:8000", 
    "my_tenant", 
    "my_database"
);
```

**ChromaRepository 使用：**
```java
// 基础使用（自动检测版本）
ChromaRepository repository = ChromaRepository.builder(
        embeddingModel, 
        "http://localhost:8000"
    )
    .collectionName("my_collection")
    .build();

// 自定义租户和数据库
ChromaRepository repository = ChromaRepository.builder(
        embeddingModel, 
        "http://localhost:8000",
        "my_tenant",
        "my_database"
    )
    .collectionName("my_collection")
    .build();
```

---

## 参考资料

- [Chroma GitHub 仓库](https://github.com/chroma-core/chroma)
- [Chroma 官方文档](https://docs.trychroma.com/)
- [Chroma API 参考](https://docs.trychroma.com/api)

---

## 附录：当前代码使用的 v1 API 端点

以下是从 `ChromaClient.java` 中提取的当前使用的 v1 API 端点：

| 行号 | 端点 | 用途 |
|------|------|------|
| 74 | `api/v1/heartbeat` | 健康检查 |
| 93 | `api/v1/collections` | 列出集合 |
| 120 | `api/v1/collections` | 创建集合 |
| 160 | `api/v1/collections/{name}` | 获取集合信息 |
| 185 | `api/v1/collections/{id}` | 删除集合 |
| 237 | `api/v1/collections/{id}/add` | 添加文档 |
| 299 | `api/v1/collections/{id}/delete` | 删除文档 |
| 323 | `api/v1/collections/{id}/get` | 获取文档 |
| 356 | `api/v1/collections/{id}/query` | 查询文档 |

---

**文档版本:** 1.0  
**最后更新:** 2026-02-01  
**适用版本:** Chroma 0.4.x - 1.0+
