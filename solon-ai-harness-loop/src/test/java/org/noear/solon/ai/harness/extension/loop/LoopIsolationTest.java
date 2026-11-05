package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件隔离守卫：loop 的 main 代码只依赖核心契约，不得 import 其它插件包或宿主（soloncode）代码。
 */
class LoopIsolationTest {

    @Test
    void mainSourcesImportNoOtherPluginOrHost() throws Exception {
        Path root = Paths.get("src/main/java");
        AtomicInteger scanned = new AtomicInteger();
        List<String> violations;
        try (Stream<Path> files = Files.walk(root)) {
            violations = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        scanned.incrementAndGet();
                        try {
                            String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            return src.matches("(?s).*import\\s+(static\\s+)?org\\.noear\\.solon\\.ai\\.harness\\.session\\.(queue|steer|goal)\\..*")
                                    || src.matches("(?s).*import\\s+(static\\s+)?org\\.noear\\.solon\\.codecli\\..*");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(p -> root.relativize(p).toString())
                    .collect(Collectors.toList());
        }

        assertTrue(scanned.get() >= 8, "守卫必须真的扫描到源码（防止路径错误导致假通过），实际 " + scanned.get());
        assertTrue(violations.isEmpty(), "loop 插件不得 import 其它插件或宿主：" + violations);
    }
}
