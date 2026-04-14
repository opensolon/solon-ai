## Build and Test Commands

### Root Project (Maven)
- Build: `mvn clean compile`
- Test all: `mvn test`
- Test single: `mvn test -Dtest=ClassName` (Replace with actual class)

### Sub-modules / Sub-projects
- solon-ai-skills/solon-ai-skill-toolgateway: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skills: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-social: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-generation: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-data: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-sys: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-web: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-lucene: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-cli: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-file: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-memory: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-lsp: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-restapi: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-pdf: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-browser: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-mail: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-text2sql: Maven module. Controlled by root project commands.
- solon-ai-skills/solon-ai-skill-diff: Maven module. Controlled by root project commands.
- solon-ai-agent: Maven module. Controlled by root project commands.
- solon-ai-ui/solon-ai-ui-aisdk: Maven module. Controlled by root project commands.
- solon-ai-rag-searchs/solon-ai-search-baidu: Maven module. Controlled by root project commands.
- solon-ai-rag-searchs/solon-ai-search-tavily: Maven module. Controlled by root project commands.
- solon-ai-rag-searchs/solon-ai-search-bocha: Maven module. Controlled by root project commands.
- acp-sdk: Maven module. Controlled by root project commands.
- solon-ai-a2a: Maven module. Controlled by root project commands.
- solon-ai-rag-loaders/solon-ai-load-excel: Maven module. Controlled by root project commands.
- solon-ai-rag-loaders/solon-ai-load-pdf: Maven module. Controlled by root project commands.
- solon-ai-rag-loaders/solon-ai-load-html: Maven module. Controlled by root project commands.
- solon-ai-rag-loaders/solon-ai-load-ddl: Maven module. Controlled by root project commands.
- solon-ai-rag-loaders/solon-ai-load-ppt: Maven module. Controlled by root project commands.
- solon-ai-rag-loaders/solon-ai-load-word: Maven module. Controlled by root project commands.
- solon-ai-rag-loaders/solon-ai-load-markdown: Maven module. Controlled by root project commands.
- solon-ai-parent: Maven module. Controlled by root project commands.
- solon-ai-mcp: Maven module. Controlled by root project commands.
- solon-ai-flow: Maven module. Controlled by root project commands.
- solon-ai: Maven module. Controlled by root project commands.
- solon-ai-core: Maven module. Controlled by root project commands.
- mcp-sdk: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-opensearch: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-tcvectordb: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-chroma: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-weaviate: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-elasticsearch: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-redis: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-mariadb: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-vectorex: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-pgvector: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-dashvector: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-qdrant: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-milvus: Maven module. Controlled by root project commands.
- solon-ai-rag-repositorys/solon-ai-repo-mysql: Maven module. Controlled by root project commands.
- solon-ai-llm-dialects/solon-ai-dialect-gemini: Maven module. Controlled by root project commands.
- solon-ai-llm-dialects/solon-ai-dialect-anthropic: Maven module. Controlled by root project commands.
- solon-ai-llm-dialects/solon-ai-dialect-dashscope: Maven module. Controlled by root project commands.
- solon-ai-llm-dialects/solon-ai-dialect-ollama: Maven module. Controlled by root project commands.
- solon-ai-llm-dialects/solon-ai-dialect-openai: Maven module. Controlled by root project commands.
- __release: Maven module. Controlled by root project commands.
- solon-ai-acp: Maven module. Controlled by root project commands.

## Guidelines

- **Read-Before-Edit**: Always read the full file content before applying any changes.
- **Atomic Work**: Implement one feature/fix at a time.
- **Verification**: Run tests before considering a task complete.
- **Path Usage**: Use relative paths only (e.g., `src/main.java`, NOT `./src/main.java`).
- **Style**: Follow existing patterns in the codebase.

