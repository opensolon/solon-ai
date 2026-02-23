package org.noear.solon.ai.skills.cli;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.annotation.Param;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LspTool - 对齐 OpenCode 逻辑
 * 使用 Eclipse LSP4J 实现 IDE 级代码分析能力（Java 8 语法版）
 */
public class LspTool {

    private final Path worktree;
    private final LspClient lspClient;

    public LspTool(String workDir, LspClient lspClient) {
        this.worktree = Paths.get(workDir).toAbsolutePath().normalize();
        this.lspClient = lspClient;
    }

    @ToolMapping(
            name = "lsp",
            description = "执行 LSP 操作（跳转定义、找引用、悬停提示等）。" +
                    "支持操作：goToDefinition, findReferences, hover, documentSymbol, workspaceSymbol, " +
                    "goToImplementation, prepareCallHierarchy, incomingCalls, outgoingCalls"
    )
    public Document lsp(
            @Param(name = "operation") String operation,
            @Param(name = "filePath") String filePath,
            @Param(name = "line") int line,
            @Param(name = "character") int character
    ) throws Exception {

        // 1. 路径与安全校验
        Path path = worktree.resolve(filePath).toAbsolutePath().normalize();
        if (!path.startsWith(worktree)) {
            throw new SecurityException("Access denied: path is outside worktree");
        }

        File file = path.toFile();
        if (!file.exists()) {
            throw new RuntimeException("File not found: " + filePath);
        }

        // 2. 坐标转换 (1-based -> 0-based)
        int lspLine = line - 1;
        int lspChar = character - 1;
        String uri = path.toUri().toString();

        // 3. 执行 LSP 操作并等待结果
        Object result = executeLspOperation(operation, uri, lspLine, lspChar).get();

        // 4. 格式化输出
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

    private CompletableFuture<?> executeLspOperation(String op, String uri, int line, int offset) throws Exception {
        // 确保文件已同步给 Language Server
        lspClient.touchFile(uri);

        TextDocumentIdentifier docId = new TextDocumentIdentifier(uri);
        Position pos = new Position(line, offset);

        // Java 8 不支持 switch 表达式，回归传统 switch 语句
        switch (op) {
            case "goToDefinition":
                return lspClient.definition(new DefinitionParams(docId, pos));
            case "findReferences":
                return lspClient.references(new ReferenceParams(docId, pos, new ReferenceContext(true)));
            case "hover":
                return lspClient.hover(new HoverParams(docId, pos));
            case "documentSymbol":
                return lspClient.documentSymbol(new DocumentSymbolParams(docId));
            case "workspaceSymbol":
                return lspClient.workspaceSymbol(new WorkspaceSymbolParams(""));
            case "goToImplementation":
                return lspClient.implementation(new ImplementationParams(docId, pos));
            case "prepareCallHierarchy":
                return lspClient.prepareCallHierarchy(new CallHierarchyPrepareParams(docId, pos));
            case "incomingCalls":
                return lspClient.incomingCalls(uri, line, offset);
            case "outgoingCalls":
                return lspClient.outgoingCalls(uri, line, offset);
            default:
                throw new IllegalArgumentException("Unknown LSP operation: " + op);
        }
    }

    /**
     * 对齐 OpenCode 逻辑的 LSP 客户端接口
     */
    public interface LspClient {
        void touchFile(String uri) throws Exception;

        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params);

        CompletableFuture<List<? extends Location>> references(ReferenceParams params);

        CompletableFuture<Hover> hover(HoverParams params);

        CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params);

        CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> workspaceSymbol(WorkspaceSymbolParams params);

        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params);

        CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params);

        CompletableFuture<List<CallHierarchyIncomingCall>> incomingCalls(String uri, int line, int offset);

        CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(String uri, int line, int offset);
    }
}