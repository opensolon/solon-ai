# Solon AI Search Baidu

**solon-ai-search-baidu** 是 Solon AI 生态系统的一部分，提供对百度 AI 搜索服务的集成支持。该模块实现了 `Repository` 接口，支持基础搜索和 AI 智能搜索两种模式。

## 🔧 配置

### 1. 获取 API Key

访问 [百度智能云控制台](https://console.bce.baidu.com/iam/#/iam/apikey/list) 获取 AppBuilder API Key：

1. 登录百度智能云控制台
2. 进入"API Key管理"页面  
3. 点击"创建API Key"
4. 选择服务：千帆AppBuilder
5. 确认创建，获得 API Key

### 2. API Key 格式

```
格式：bce-v3/ALTAK-xxxxxxxxxx/xxxxxxxxxxxxxxxxxxxxxxxx
```

## 📖 快速开始

参考以下测试代码：

- **基础使用示例**: [BaiduAiSearchExample.java](src/test/java/features/ai/example/BaiduAiSearchExample.java)
- **完整测试用例**: [BaiduAiSearchTest.java](src/test/java/features/ai/BaiduAiSearchTest.java)
- **测试工具配置**: [TestUtils.java](src/test/java/features/ai/TestUtils.java)



**注意**: 使用本模块需要有效的百度AppBuilder API Key，请确保遵守百度的服务条款和使用限制。 