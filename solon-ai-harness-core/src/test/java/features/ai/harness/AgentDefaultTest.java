package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.agent.AgentManager;
import org.noear.solon.ai.harness.agent.BuiltinAgentI18n;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.test.SolonTest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author noear 2026/3/20 created
 *
 */
@SolonTest
public class AgentDefaultTest {
    @Test
    public void case1() {
        HarnessEngine engine = HarnessEngine.of(".", ".").sessionProvider((id, parentId, c) -> InMemoryAgentSession.of(id)).build();
        AgentManager agentManager = engine.getAgentManager();

        AgentDefinition bash = agentManager.getAgent("bash");
        AgentDefinition explore = agentManager.getAgent("explore");
        AgentDefinition general_purpose = agentManager.getAgent("general");
        AgentDefinition plan = agentManager.getAgent("plan");

        Assertions.assertNotNull(bash);
        Assertions.assertNotNull(explore);
        Assertions.assertNotNull(general_purpose);
        Assertions.assertNotNull(plan);

        assert bash.getMetadata().getTools().contains("bash");
        assert bash.getMetadata().getTools().contains("list");
        //bash 没有专用文件编辑工具（写文件只能经由 shell）
        Assertions.assertFalse(bash.getMetadata().getTools().contains("edit"));
        Assertions.assertFalse(bash.getMetadata().getTools().contains("write"));

        assert explore.getMetadata().getTools().contains("list");

        assert general_purpose.getMetadata().getTools().contains("*");
        //general 没有正文：走 ReAct 默认提示词（与 opencode 的 general 一致），不被整段替换掉核心规则
        Assertions.assertTrue(Assert.isEmpty(general_purpose.getSystemPrompt()));
        //"*" 只展开公有工具，mcp / memory 需显式声明
        assert general_purpose.getMetadata().getTools().contains("mcp");
        assert general_purpose.getMetadata().getTools().contains("memory");
        //子代理不嵌套委派
        Assertions.assertTrue(general_purpose.getMetadata().getDisallowedTools().contains("task"));
        Assertions.assertTrue(general_purpose.getMetadata().getDisallowedTools().contains("generate"));

        assert plan.getMetadata().getTools().contains("webfetch");
        //plan 声明 todo 后，子代理走内存版计划（AgentFactory#todoToolAddDo）
        assert plan.getMetadata().getTools().contains("todo");
        Assertions.assertFalse(plan.getMetadata().getTools().contains("edit"));

        //explore / plan 带只读的代码导航（LSP）
        Assertions.assertTrue(explore.getMetadata().getTools().contains("lsp"));
        Assertions.assertTrue(plan.getMetadata().getTools().contains("lsp"));

        //explore 可执行只读命令（查 git 历史等）：只读由 permissionRules 兜底（子代理执行前判定，见 AgentPermissionGuardTest），提示词同步说明
        Assertions.assertTrue(explore.getMetadata().getTools().contains("bash"));
        Assertions.assertTrue(explore.getMetadata().hasPermissionRules());
        Assertions.assertTrue(explore.getMetadata().getPermissionRules().stream().anyMatch(r ->
                "bash".equals(r.toolName()) && r.behavior() == PermissionBehavior.DENY && !r.pattern().isPresent() && r.priority() < 0),
                "explore 的 shell 兜底拒绝");
        Assertions.assertTrue(explore.getSystemPrompt().contains("shell 受权限规则约束"));

        //explore / plan 的描述写着「只读」，不能有文件编辑工具；plan 也不执行命令
        for (AgentDefinition readOnly : Arrays.asList(explore, plan)) {
            for (String tool : Arrays.asList("edit", "write", "*", "**")) {
                Assertions.assertFalse(readOnly.getMetadata().getTools().contains(tool),
                        readOnly.getMetadata().getName() + " 不应声明 " + tool);
            }
        }
        Assertions.assertFalse(plan.getMetadata().getTools().contains("bash"));
    }

    /**
     * 语言版本只替换描述与正文（BuiltinAgentI18n）：工具等元数据写在语言文件里不会生效，
     * 只会在基础文件改动后悄悄过时，所以不许出现。
     */
    @Test
    public void i18nVariantsOnlyCarryNameAndDescription() throws Exception {
        for (String name : Arrays.asList("bash", "explore", "general", "plan", "git-summary")) {
            String path = BuiltinAgentI18n.I18N_PATH + name + "_en.md";
            List<String> lines;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                Assertions.assertNotNull(in, path);
                lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
            }

            Markdown variant = MarkdownUtil.resolve(lines);
            Assertions.assertEquals(name, variant.getName(), path);
            Assertions.assertTrue(Assert.isNotEmpty(variant.getDescription()), path);
            for (String key : Arrays.asList("tools", "disallowedTools", "hidden", "model", "permissionRules")) {
                Assertions.assertFalse(variant.hasMeta(key), path + " 不应声明 " + key);
            }
        }
    }
}
