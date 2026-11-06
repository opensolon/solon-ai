package org.noear.solon.ai.harness.extension.steer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * steer 插件经引擎的链路验证 + 插件边界守卫（steer 只依赖核心契约，不依赖 queue 插件）。
 */
class SteerEngineIntegrationTest {
    @TempDir
    Path workspace;

    @Test
    void steerWithoutRunningTaskSuggestsQueue() {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
        engine.addExtensionIfAbsent(new SteerExtension());

        InputOutcome outcome = engine.handle(HarnessInput.of(InMemoryAgentSession.of(), "/steer 补充一下"));

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("当前无正在执行的任务可插话"));
    }

    @Test
    void steerMainSourcesDoNotDependOnOtherPlugins() throws Exception {
        Path root = Paths.get("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            java.util.List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            assertTrue(sources.size() > 5, "守卫必须真的扫描到源码");
            java.util.List<String> violations = new java.util.ArrayList<>();
            for (Path p : sources) {
                String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                if (src.matches("(?s).*import\\s+(static\\s+)?org\\.noear\\.solon\\.ai\\.harness\\.session\\.(queue|goal)\\..*")) {
                    violations.add(root.relativize(p).toString());
                }
            }
            assertTrue(violations.isEmpty(), "steer 插件只能经核心契约（InputBacklog）协作：" + violations);
        }
    }
}
