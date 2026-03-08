# Solon AI Search Tavily

**solon-ai-search-tavily** 增加了 Solon AI 对 [Tavily](https://tavily.com/) 服务的支持，Tavily 是一家提供实时检索、爬取、提取、网站图谱解析的服务商。

## 功能概览

| 功能 | 说明 | 返回类型 |
|------|------|----------|
| **Search** | 联网搜索，支持 LLM 答案生成、图片搜索、域名过滤等 | `TavilySearchResponse` |
| **Extract** | 从指定 URL 提取页面内容 | `TavilyExtractResponse` |
| **Crawl** | 从根 URL 爬取网站内容，支持深度和广度控制 | `TavilyCrawlResponse` |
| **Map** | 映射网站结构，返回发现的 URL 列表 | `TavilyMapResponse` |

## 配置

### 1. 获取 API Key

访问 [Tavily API Platform](https://app.tavily.com/home) 获取 API Key：

1. 登录 Tavily API Platform
2. 查看 Overview 右侧的 `Api Keys` 视图
3. 复制对应的 Api Key，如果没有需要新建，默认用户可获得一定的免费额度

### 2. API Key 格式

```
格式：tvly-xxx-xxxxx
```
提醒：生产环境请使用生产模式 Key，避免出现速率超限，具体限制请查看官方网站。

### 3. 使用注意

- 如果查询时配置了嵌入模型，会进行查询后的数据执行相似度排序，对于网络搜索而言，执行问题的相似度排序一般没有意义
  - 如果不需要进行相似度排序，初始化 Repository 时请**不要**传入嵌入模型

## 快速开始

### 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-search-tavily</artifactId>
</dependency>
```

### 基础搜索（Repository 接口）

通过标准 `Repository` 接口使用，返回 `List<Document>`，适合最简单查询的情况：

```java
// 方式一：TavilySimpleSearchRepository（仅搜索）
TavilySimpleSearchRepository repo = TavilySimpleSearchRepository.of("your-api-key").build();
List<Document> docs = repo.search(new QueryCondition("Java framework").limit(5));

// 方式二：TavilySearchRepository（完整功能）
TavilySearchRepository repo = TavilySearchRepository.of("your-api-key").build();
List<Document> docs = repo.search(new QueryCondition("Java framework").limit(5));
```

### 完整搜索（额外支持 Tavily 特有参数）

默认的Repository比较简单，如果你追求返回更多结果，比如检索速度，给前端返回网页favicon等，最好使用 `SearchCondition` 获取完整的 Tavily 搜索能力：

```java
TavilySearchRepository repo = TavilySearchRepository.of("your-api-key").build();

SearchCondition condition = new SearchCondition("latest AI developments")
        .searchDepth("advanced")       // 搜索深度：basic / advanced / fast / ultra-fast
        .maxResults(10)                // 最大结果数（0-20）
        .topic("news")                 // 搜索类别：general / news / finance
        .timeRange("week")             // 时间范围：day / week / month / year
        .includeAnswer("basic")        // 包含 LLM 答案：basic / advanced
        .includeRawContent("markdown") // 包含原始内容：markdown / text
        .includeImages(true)           // 包含图片
        .includeFavicon(true)          // 包含网站图标
        .includeDomains(Arrays.asList("github.com"))  // 域名白名单
        .excludeDomains(Arrays.asList("example.com")) // 域名黑名单
        .country("us")                 // 国家偏好
        .startDate("2025-01-01")       // 起始日期
        .endDate("2025-12-31");        // 截止日期

TavilySearchResponse response = repo.search(condition);

// 获取 LLM 答案
System.out.println("Answer: " + response.getAnswer());

// 遍历搜索结果（Tavily 原生类型）
for (TavilySearchDocument doc : response.getResults()) {
    System.out.println(doc.getTitle() + " - " + doc.getUrl());
    System.out.println("Score: " + doc.getScore());
    System.out.println("Content: " + doc.getContent());
    System.out.println("Raw Content: " + doc.getRawContent());
    System.out.println("Favicon: " + doc.getFavicon());
}

// 可转换为标准 Document 列表
List<Document> docs = response.toDocuments();
```

### Extract（内容提取）

从指定 URL 提取页面内容，有点像`web_fetch`这个工具，做`deepsearch`时，很有用，比较消耗`credit`，一般用搜索即可：

```java
TavilySearchRepository repo = TavilySearchRepository.of("your-api-key").build();

ExtractCondition condition = new ExtractCondition("https://example.com", "https://example.org")
        .query("main content")          // 按相关性排序提取内容
        .chunksPerSource(3)              // 每个来源最大片段数
        .format("markdown")             // 输出格式：markdown / text
        .extractDepth("advanced");       // 提取深度：basic / advanced

TavilyExtractResponse response = repo.extract(condition);

for (TavilyExtractDocument doc : response.getResults()) {
    System.out.println("URL: " + doc.getUrl());
    System.out.println("Content: " + doc.getRawContent());
}

// 查看失败的 URL
for (TavilyFailedResult failed : response.getFailedResults()) {
    System.out.println("Failed: " + failed.getUrl() + " - " + failed.getError());
}
```

### Crawl（网站爬取）

从根 URL 爬取网站内容：

```java
TavilySearchRepository repo = TavilySearchRepository.of("your-api-key").build();

CrawlCondition condition = new CrawlCondition("https://docs.example.com")
        .maxDepth(2)                     // 最大深度（1-5）
        .maxBreadth(10)                  // 每层最大链接数（1-500）
        .limit(20)                       // 总链接数限制
        .selectPaths(Arrays.asList("/docs/.*"))  // 路径过滤（正则）
        .excludeDomains(Arrays.asList("^ads\\..*"))  // 排除域名
        .allowExternal(false)            // 不允许外部链接
        .format("markdown");

TavilyCrawlResponse response = repo.crawl(condition);

System.out.println("Base URL: " + response.getBaseUrl());
for (TavilyCrawlDocument doc : response.getResults()) {
    System.out.println("Page: " + doc.getUrl());
}

// 可转换为标准 Document 列表
List<Document> docs = response.toDocuments();
```

### Map（站点映射）

映射网站结构，只返回 URL 列表（不提取内容），一般，用于后续提取：

```java
TavilySearchRepository repo = TavilySearchRepository.of("your-api-key").build();

MapCondition condition = new MapCondition("https://example.com")
        .maxDepth(2)
        .limit(50)
        .instructions("Only return documentation pages");

TavilyMapResponse response = repo.map(condition);

System.out.println("Discovered " + response.getResults().size() + " URLs:");
for (String url : response.getResults()) {
    System.out.println("  " + url);
}
```

### 直接使用 TavilyClient

如果不需要 Repository 接口，也可直接使用 `TavilyClient`：

```java
TavilyClient client = ClientBuilder.of("your-api-key")
        .apiBase("https://custom.api.com")   // 可选，自定义端点
        .timeout(Duration.ofSeconds(120))     // 可选，超时设置
        .build();

TavilySearchResponse searchResp = client.search(new SearchCondition("query"));
TavilyExtractResponse extractResp = client.extract(new ExtractCondition("https://example.com"));
TavilyCrawlResponse crawlResp = client.crawl(new CrawlCondition("https://example.com"));
TavilyMapResponse mapResp = client.map(new MapCondition("https://example.com"));
```

## 包结构

```
org.noear.solon.ai.rag.search.tavily
├── TavilyClient                    # 核心 API 客户端
├── ClientBuilder                   # 客户端构建器
├── condition/                      # 请求条件
│   ├── SearchCondition             # 搜索条件（完整 Tavily 参数）
│   ├── ExtractCondition            # 提取条件
│   ├── CrawlCondition              # 爬取条件
│   └── MapCondition                # 映射条件
├── document/                       # 响应/结果类型
│   ├── TavilySearchDocument        # 搜索结果文档
│   ├── TavilySearchResponse        # 搜索完整响应
│   ├── TavilyExtractDocument       # 提取结果文档
│   ├── TavilyExtractResponse       # 提取完整响应
│   ├── TavilyCrawlDocument         # 爬取结果文档
│   ├── TavilyCrawlResponse         # 爬取完整响应
│   ├── TavilyMapResponse           # 映射完整响应
│   ├── TavilyImage                 # 图片对象
│   └── TavilyFailedResult          # 失败结果（Extract 用）
└── repository/                     # 知识库
    └── TavilySearchRepository      # 完整功能知识库（实现 Repository 接口）

org.noear.solon.ai.rag.search
└── TavilyWebSearchRepository       # 简化版知识库（仅搜索）
```

**注意**: 请在使用本模块调用对应的服务时，遵循 Tavily 的使用协议和条款；在中华人民共和国境内使用对应的服务，应当遵循《生成式人工智能服务管理办法》；跨境传输用户数据，应当遵循有关数据出境安全要求。
