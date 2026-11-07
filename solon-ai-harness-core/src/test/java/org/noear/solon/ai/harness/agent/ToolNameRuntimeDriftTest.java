package org.noear.solon.ai.harness.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具名映射一致性：ToolName 的「逻辑名 → 运行时名」必须与各内置 Talent 实际提供的工具名一致。
 * 否则作用域名单会悄悄失效（误拒，或把新工具当作扩展工具放行）。
 */
class ToolNameRuntimeDriftTest {
    @TempDir
    Path workspace;

    /** 只在运行态（如已配置 MCP 服务）才注册的工具 */
    private static final Set<String> DYNAMIC = Collections.singleton("call_tool");

    private static Set<String> namesOf(Talent talent) {
        // 声明的（@ToolMapping）∪ 运行时的（getTools）：前者不受运行态隐藏影响，后者含编程注册的工具（如 call_tool）
        Set<String> out = new TreeSet<>();
        for (FunctionTool t : new MethodToolProvider(talent).getTools()) {
            out.add(t.name());
        }
        Collection<FunctionTool> runtime = talent.getTools(null);
        if (runtime != null) {
            for (FunctionTool t : runtime) {
                out.add(t.name());
            }
        }
        return out;
    }

    @Test
    void talentToolNamesMatchMapping() {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), ".solon/")
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();

        Map<String, Talent> talents = new LinkedHashMap<>();
        talents.put("skill", engine.getTalents().getSkill());
        talents.put("todo", engine.getTalents().getTodo());
        talents.put("webfetch", engine.getTalents().getWebfetch());
        talents.put("websearch", engine.getTalents().getWebsearch());
        talents.put("codesearch", engine.getTalents().getCodeSearch());
        talents.put("memory", engine.getTalents().getMemory());
        talents.put("task", engine.getTalents().getTask());
        talents.put("generate", engine.getTalents().getGenerate());
        talents.put("mcp", engine.getTalents().getMcpGateway());
        talents.put("openapi", engine.getTalents().getOpenApiGateway());
        talents.put("lsp", engine.getTalents().getLsp());
        talents.put("code", engine.getTalents().getCode());

        for (Map.Entry<String, Talent> kv : talents.entrySet()) {
            if (kv.getValue() == null) {
                continue; // 未启用（如未配置 LSP）
            }
            Set<String> mapped = new TreeSet<>(ToolName.runtimeNamesOf(kv.getKey()));
            Set<String> actual = namesOf(kv.getValue());
            // Talent 提供而映射没有：作用域会把它当扩展工具放行 —— 必须同步映射
            assertTrue(mapped.containsAll(actual), "ToolName 映射缺少 " + kv.getKey() + " 的工具: " + actual);
            // 映射有而 Talent 没有：只允许运行态才注册的工具（call_tool 在有 MCP 服务时才出现）
            Set<String> extra = new TreeSet<>(mapped);
            extra.removeAll(actual);
            extra.removeAll(DYNAMIC);
            assertTrue(extra.isEmpty(), "ToolName 映射中 " + kv.getKey() + " 有过时的工具名: " + extra);
        }

        // 终端工具：映射内的名字都必须是 TerminalTalent 真实提供的
        Set<String> terminal = namesOf(engine.getTalents().getTerminal());
        for (String logical : Arrays.asList("read", "write", "edit", "glob", "grep", "ls", "bash")) {
            assertTrue(terminal.containsAll(ToolName.runtimeNamesOf(logical)),
                    "TerminalTalent 缺少映射内的工具: " + logical + " -> " + ToolName.runtimeNamesOf(logical));
        }
        assertTrue(ToolName.isAlwaysAllowed("now"));
        assertTrue(namesOf(engine.getTalents().getClock()).contains("now"));
    }
}
