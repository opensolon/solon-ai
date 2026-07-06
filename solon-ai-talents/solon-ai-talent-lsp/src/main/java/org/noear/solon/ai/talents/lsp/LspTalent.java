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
package org.noear.solon.ai.talents.lsp;

import org.eclipse.lsp4j.*;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.talents.lsp.exception.LspCommandNotFoundException;
import org.noear.solon.ai.talents.lsp.exception.LspEnvironmentException;
import org.noear.solon.ai.talents.lsp.exception.LspNoMatchException;
import org.noear.solon.ai.talents.lsp.exception.LspStartException;
import org.noear.solon.annotation.Param;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;

/**
 * LSP 工具包 - 对齐 OpenCode 的 LSP 工具模型
 *
 * <p>支持按文件扩展名自动路由到对应的 LSP 服务器。
 * 提供跳转定义、查找引用、悬停提示、文档符号、调用层次等操作。
 *
 * @author noear
 * @since 3.10.0
 */
public class LspTalent extends AbsTalent {
    private final LspManager lspManager;
    private final String workDir;

    /**
     * 诊断信息缓存：uri -> 诊断文本
     */
    private final ConcurrentHashMap<String, String> diagnosticsCache = new ConcurrentHashMap<>();

    public LspTalent(LspManager lspManager, String workDir) {
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

    @Override
    public boolean isSupported(Prompt prompt) {
        return lspManager.hasServers();
    }

    //可以返回 Document（结果结构数据） 或 String（给 llm 的提示）
    @ToolMapping(
            name = "lsp",
            description = "执行 LSP 操作，用于代码导航与分析。根据操作类型可能需要光标位置（行号+列号）。\n\n" +
                    "操作说明：\n" +
                    "  - goToDefinition：跳转到光标处符号的定义位置\n" +
                    "  - findReferences：查找光标处符号的所有引用\n" +
                    "  - hover：获取光标处符号的类型签名/文档注释\n" +
                    "  - goToImplementation：跳转到光标处接口/抽象方法的实现\n" +
                    "  - prepareCallHierarchy：查看光标处方法的调用层级\n" +
                    "  - incomingCalls：谁调用了光标处的方法\n" +
                    "  - outgoingCalls：光标处的方法调用了谁\n" +
                    "  - documentSymbol：列出当前文件中的所有符号（类/方法/字段），不需光标位置\n" +
                    "  - workspaceSymbol：在整个工作区中搜索符号，不需光标位置\n" +
                    "  - diagnostics：获取当前文件的编译/语法诊断信息，不需光标位置\n\n" +
                    "使用建议：当需要理解代码结构、查找定义、搜索符号时优先使用此工具。"
    )
    public Document lsp(
            @Param(name = "operation", description = "LSP 操作类型。可选值：goToDefinition（跳转到定义）、findReferences（查找引用）、hover（悬停类型/文档）、documentSymbol（当前文件符号）、workspaceSymbol（工作区符号搜索）、goToImplementation（跳转实现）、prepareCallHierarchy（准备调用层次）、incomingCalls（谁调用了当前方法）、outgoingCalls（当前方法调用了谁）、diagnostics（获取诊断信息）") String operation,
            @Param(name = "filePath", description = "目标文件路径，相对于工作区根目录。例如：src/main/java/App.java") String filePath,
            @Param(name = "line", required = false, defaultValue = "1", description = "行号（1-based，如编辑器中显示的行号）。仅 goToDefinition/findReferences/hover/goToImplementation/prepareCallHierarchy/incomingCalls/outgoingCalls 需要）") Integer line,
            @Param(name = "character", required = false, defaultValue = "1", description = "列号（1-based，如编辑器中显示的列号）。仅 goToDefinition/findReferences/hover/goToImplementation/prepareCallHierarchy/incomingCalls/outgoingCalls 需要）") Integer character,
            String __cwd,
            String __sessionId
    ) throws Exception {
        Path workPath = getWorkPath(__cwd);

        // 1. 路径与安全校验
        Path path = workPath.resolve(filePath).toAbsolutePath().normalize();
        if (!path.startsWith(workPath)) {
            throw new SecurityException("权限拒绝：路径越界。");
        }

        File file = path.toFile();
        if (!file.exists()) {
            throw new RuntimeException("文件不存在：" + filePath);
        }

        String uri = path.toUri().toString();

        // 2. diagnostics 操作直接返回缓存
        if ("diagnostics".equals(operation)) {
            String diagnostics = diagnosticsCache.getOrDefault(uri, "No diagnostics available for " + filePath);
            return new Document()
                    .title(String.format("diagnostics %s", workPath.relativize(path)))
                    .content(diagnostics)
                    .metadata("operation", "diagnostics")
                    .metadata("uri", uri);
        }

        // 3. 路由到对应的 LSP 服务器
        LspClient client;
        try {
            client = lspManager.getClientForFile(filePath);
        } catch (LspNoMatchException e) {
            // 文件扩展名没有匹配的 LSP 服务器
            return new Document()
                    .title(String.format("%s %s", operation, filePath))
                    .content("The file type is not supported by any LSP server. " +
                            "File: " + filePath + ". " +
                            "Supported types: [" + e.getSupportedExtensions() + "]. " +
                            "You can use other tools (read, grep) to inspect this file.")
                    .metadata("operation", operation)
                    .metadata("uri", uri);
        } catch (LspCommandNotFoundException e) {
            // LSP 服务器命令未安装
            return new Document()
                    .title(String.format("%s %s", operation, filePath))
                    .content("LSP server '" + e.getServerName() + "' is not installed. " +
                            "The command '" + e.getCommandName() + "' was not found in PATH. " +
                            "Please install it first. " +
                            "Common install commands: " +
                            "pip install python-lsp-server (Python), " +
                            "npm install -g typescript-language-server typescript (TypeScript), " +
                            "go install golang.org/x/tools/gopls@latest (Go), " +
                            "rustup component add rust-analyzer (Rust), " +
                            "brew install llvm (C/C++ clangd). " +
                            "You can use other tools (read, grep) to inspect this file.")
                    .metadata("operation", operation)
                    .metadata("uri", uri);
        } catch (LspEnvironmentException e) {
            // 运行环境不满足（如 Java 版本过低、缺少运行时依赖）
            return new Document()
                    .title(String.format("%s %s", operation, filePath))
                    .content("LSP server '" + e.getServerName() + "' environment requirement not met. " +
                            "Detail: " + e.getDetail() + ". " +
                            "Command: " + String.join(" ", e.getFullCommand()) + ". " +
                            "This usually means the runtime version is too old or a dependency is missing. " +
                            "For jdtls: requires Java 21+. " +
                            "For rust-analyzer: requires Rust toolchain. " +
                            "For sourcekit-lsp: requires Xcode/Swift toolchain. " +
                            "You can use other tools (read, grep) to inspect this file.")
                    .metadata("operation", operation)
                    .metadata("uri", uri);
        } catch (LspStartException e) {
            // LSP 服务器启动失败（初始化超时等通用场景）
            return new Document()
                    .title(String.format("%s %s", operation, filePath))
                    .content("LSP server '" + e.getServerName() + "' failed to start. " +
                            "Command: " + String.join(" ", e.getFullCommand()) + ". " +
                            "Reason: " + e.getCause().getMessage() + ". " +
                            "Please check if the LSP server binary is installed and accessible in PATH.")
                    .metadata("operation", operation)
                    .metadata("uri", uri);
        }

        if (client == null) {
            // 兜底（理论上不会走到这里，但保留防御性检查）
            return new Document()
                    .title(String.format("%s %s", operation, filePath))
                    .content("Unexpected error: LSP client is null for file: " + filePath)
                    .metadata("operation", operation)
                    .metadata("uri", uri);
        }

        // 4. 坐标转换 (1-based -> 0-based)，line/character 可为 null（documentSymbol/workspaceSymbol/diagnostics 不需要）
        int lspLine = (line != null) ? line - 1 : 0;
        int lspChar = (character != null) ? character - 1 : 0;

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

        String title = String.format("%s %s:%d:%d", operation, workPath.relativize(path),
                line != null ? line : 1, character != null ? character : 1);

        return new Document()
                .title(title)
                .content(output)
                .metadata("operation", operation)
                .metadata("uri", uri);
    }

    private static final long OPERATION_TIMEOUT = Long.getLong("lsp.operationTimeout", 15L);

    private Object executeOperation(LspClient client, String op,
                                    TextDocumentIdentifier docId, Position pos,
                                    String uri, int line, int offset) throws Exception {
        try {
            CompletableFuture<?> future;
            switch (op) {
                case "goToDefinition":
                    future = client.definition(new DefinitionParams(docId, pos));
                    break;
                case "findReferences":
                    future = client.references(new ReferenceParams(docId, pos, new ReferenceContext(true)));
                    break;
                case "hover":
                    future = client.hover(new HoverParams(docId, pos));
                    break;
                case "documentSymbol":
                    future = client.documentSymbol(new DocumentSymbolParams(docId));
                    break;
                case "workspaceSymbol":
                    future = client.workspaceSymbol(new WorkspaceSymbolParams(""));
                    break;
                case "goToImplementation":
                    future = client.implementation(new ImplementationParams(docId, pos));
                    break;
                case "prepareCallHierarchy":
                    future = client.prepareCallHierarchy(new CallHierarchyPrepareParams(docId, pos));
                    break;
                case "incomingCalls":
                    future = client.incomingCalls(uri, line, offset);
                    break;
                case "outgoingCalls":
                    future = client.outgoingCalls(uri, line, offset);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown LSP operation: " + op);
            }
            return future.get(OPERATION_TIMEOUT, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("LSP operation '" + op + "' timed out after " + OPERATION_TIMEOUT + "s", e);
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
