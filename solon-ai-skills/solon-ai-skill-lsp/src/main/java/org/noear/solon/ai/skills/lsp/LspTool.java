/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.lsp;

import org.eclipse.lsp4j.*;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.annotation.Param;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP 技能 - 对齐 OpenCode 的 LSP 工具模型
 *
 * <p>支持按文件扩展名自动路由到对应的 LSP 服务器。
 * 提供跳转定义、查找引用、悬停提示、文档符号、调用层次等操作。
 *
 * @author noear
 * @since 3.10.0
 */
public class LspTool extends AbsToolProvider {
    private final LspManager lspManager;
    private final String workDir;

    /**
     * 诊断信息缓存：uri -> 诊断文本
     */
    private final ConcurrentHashMap<String, String> diagnosticsCache = new ConcurrentHashMap<>();

    public LspTool(LspManager lspManager, String workDir) {
        this.lspManager = lspManager;
        this.workDir = workDir;
    }

    public LspManager getLspManager() {
        return lspManager;
    }

    private Path getWorkPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : workDir;
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
    }

    @ToolMapping(
            name = "lsp",
            description = "执行 LSP 操作（跳转定义、找引用、悬停提示、文档符号等）。" +
                    "支持操作：goToDefinition, findReferences, hover, documentSymbol, workspaceSymbol, " +
                    "goToImplementation, prepareCallHierarchy, incomingCalls, outgoingCalls, diagnostics"
    )
    public Document lsp(
            @Param(name = "operation") String operation,
            @Param(name = "filePath") String filePath,
            @Param(name = "line") int line,
            @Param(name = "character") int character,
            String __cwd,
            String __sessionId
    ) throws Exception {
        Path worktree = getWorkPath(__cwd);

        // 1. 路径与安全校验
        Path path = worktree.resolve(filePath).toAbsolutePath().normalize();
        if (!path.startsWith(worktree)) {
            throw new SecurityException("Access denied: path is outside worktree");
        }

        File file = path.toFile();
        if (!file.exists()) {
            throw new RuntimeException("File not found: " + filePath);
        }

        String uri = path.toUri().toString();

        // 2. diagnostics 操作直接返回缓存
        if ("diagnostics".equals(operation)) {
            String diagnostics = diagnosticsCache.getOrDefault(uri, "No diagnostics available for " + filePath);
            return new Document()
                    .title(String.format("diagnostics %s", worktree.relativize(path)))
                    .content(diagnostics)
                    .metadata("operation", "diagnostics")
                    .metadata("uri", uri);
        }

        // 3. 路由到对应的 LSP 服务器
        LspClient client = lspManager.getClientForFile(filePath);
        if (client == null) {
            return new Document()
                    .title(String.format("%s %s", operation, filePath))
                    .content("No LSP server configured for file: " + filePath +
                            ". Supported extensions: " + lspManager.getServerConfigs().values()
                            .stream()
                            .flatMap(p -> p.getExtensions().stream())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("none"))
                    .metadata("operation", operation)
                    .metadata("uri", uri);
        }

        // 4. 坐标转换 (1-based -> 0-based)
        int lspLine = line - 1;
        int lspChar = character - 1;

        // 5. 确保文件已同步给 Language Server
        client.touchFile(uri);

        // 6. 构造参数并执行
        TextDocumentIdentifier docId = new TextDocumentIdentifier(uri);
        Position pos = new Position(lspLine, lspChar);

        Object result = executeOperation(client, operation, docId, pos, uri, lspLine, lspChar);

        // 7. 格式化输出
        String output;
        if (result == null || (result instanceof List && ((List<?>) result).isEmpty())) {
            output = "No results found for " + operation;
        } else {
            output = ONode.serialize(result);
        }

        String title = String.format("%s %s:%d:%d", operation, worktree.relativize(path), line, character);

        return new Document()
                .title(title)
                .content(output)
                .metadata("operation", operation)
                .metadata("uri", uri);
    }

    private Object executeOperation(LspClient client, String op,
                                    TextDocumentIdentifier docId, Position pos,
                                    String uri, int line, int offset) throws Exception {
        switch (op) {
            case "goToDefinition":
                return client.definition(new DefinitionParams(docId, pos)).get();
            case "findReferences":
                return client.references(new ReferenceParams(docId, pos, new ReferenceContext(true))).get();
            case "hover":
                return client.hover(new HoverParams(docId, pos)).get();
            case "documentSymbol":
                return client.documentSymbol(new DocumentSymbolParams(docId)).get();
            case "workspaceSymbol":
                return client.workspaceSymbol(new WorkspaceSymbolParams("")).get();
            case "goToImplementation":
                return client.implementation(new ImplementationParams(docId, pos)).get();
            case "prepareCallHierarchy":
                return client.prepareCallHierarchy(new CallHierarchyPrepareParams(docId, pos)).get();
            case "incomingCalls":
                return client.incomingCalls(uri, line, offset).get();
            case "outgoingCalls":
                return client.outgoingCalls(uri, line, offset).get();
            default:
                throw new IllegalArgumentException("Unknown LSP operation: " + op);
        }
    }

    /**
     * 更新诊断信息缓存（由 LspClientImpl 的 publishDiagnostics 回调调用）
     */
    public void updateDiagnostics(String uri, String diagnosticsText) {
        if (diagnosticsText == null || diagnosticsText.isEmpty()) {
            diagnosticsCache.remove(uri);
        } else {
            diagnosticsCache.put(uri, diagnosticsText);
        }
    }
}
