package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.DeployMode;
import org.noear.solon.ai.harness.extension.goal.validate.ValidatorPolicy;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.InputScopeResolver;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.talents.cli.CommandResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * goal 验证器缺省值随引擎模式：SERVER 不注册内置验证器、无法判定按未通过；STANDALONE 保持原有体验
 */
class GoalDeployModeDefaultsTest {

    @TempDir
    Path tmp;

    private HarnessEngine engine(DeployMode mode) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws-" + mode));
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return HarnessEngine.of(ws.toString(), ".solon/")
                .deployMode(mode)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .stateSource(FileMountSource.of(Files.createDirectories(tmp.resolve("state-" + mode))))
                .modelAdd(model)
                .scopeResolver(InputScopeResolver.byPrincipal(p -> InputScope.builder(p).build()))
                .runtimeMutable(true) // 测试里要 addExtension
                .build();
    }

    private static GoalExtension attach(HarnessEngine engine, GoalExtension.Builder b) {
        GoalExtension goal = b.jobManager(null).build();
        engine.addExtension(goal);
        return goal;
    }

    @Test
    void serverModeIsConservative() throws Exception {
        GoalExtension goal = attach(engine(DeployMode.SERVER), GoalExtension.builder());
        assertEquals(0, goal.getGoalRuntime().validatorCount(), "SERVER 不注册内置构建/测试验证器");
        assertEquals(ValidatorPolicy.FAIL_CLOSED, goal.getGoalRuntime().getValidatorPolicy());
    }

    @Test
    void standaloneKeepsDefaults() throws Exception {
        GoalExtension goal = attach(engine(DeployMode.STANDALONE), GoalExtension.builder());
        assertEquals(2, goal.getGoalRuntime().validatorCount());
        assertEquals(ValidatorPolicy.FAIL_OPEN, goal.getGoalRuntime().getValidatorPolicy());
    }

    @Test
    void explicitSettingsOverrideDeployMode() throws Exception {
        GoalExtension goal = attach(engine(DeployMode.SERVER), GoalExtension.builder()
                .defaultValidators(true)
                .validatorPolicy(ValidatorPolicy.FAIL_OPEN)
                .validatorTimeoutMs(1000));
        assertEquals(2, goal.getGoalRuntime().validatorCount());
        assertEquals(ValidatorPolicy.FAIL_OPEN, goal.getGoalRuntime().getValidatorPolicy());
        assertThrows(IllegalArgumentException.class, () -> GoalExtension.builder().validatorTimeoutMs(0));
    }

    @Test
    void validationCommandGoesThroughEngineTerminal() throws Exception {
        HarnessEngine engine = engine(DeployMode.STANDALONE);
        GoalExtension goal = attach(engine, GoalExtension.builder());
        // SERVER/STANDALONE 都走 TerminalTalent.exec：STANDALONE 下关掉沙盒只为在测试机上可执行
        engine.getTerminalTalent().setSandboxEnabled(false);
        CommandResult r = goal.getGoalRuntime().runValidationCommand("echo ok", engine.getWorkspace());
        assertEquals(CommandResult.Status.COMPLETED, r.getStatus());
        assertTrue(r.getText().contains("ok"));
    }
}
