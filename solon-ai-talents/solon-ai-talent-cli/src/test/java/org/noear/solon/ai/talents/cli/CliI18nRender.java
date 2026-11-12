package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountManager;
import org.noear.solon.ai.mount.MountType;
import org.noear.solon.ai.mount.source.ClasspathMountSource;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.MountCapabilities;
import org.noear.solon.ai.sandbox.SandboxManager;
import org.noear.solon.ai.talents.cli.i18n.CliI18n;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.noear.solon.ai.talents.cli.CliI18nFixtures.*;

/**
 * cli 才能（Terminal / Skill / Clock）对模型可见文本的渲染场景
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变。tag 为请求语言 / 工具上下文注入的 __locale
 * （null 表示未注入，用默认语言）。</p>
 */
final class CliI18nRender {
    private CliI18nRender() {
    }

    // ---------------- 调用适配（语言参数经此传入） ----------------

    static Prompt prompt(String tag) {
        Prompt prompt = Prompt.of("hi");
        if (tag != null) {
            prompt.locale(AiI18n.toLocale(tag));
        }
        return prompt;
    }

    static Collection<FunctionTool> tools(TerminalTalent t, String tag) {
        return t.getTools(prompt(tag));
    }

    static String bash(TerminalTalent t, String tag, String cmd, Integer timeout, Integer max) {
        return t.bash(cmd, timeout, max, null, tag).getContent();
    }

    static String bashStart(TerminalTalent t, String tag, String cmd) throws Exception {
        return t.bashStart(cmd, null, 1000, 64000, 120000, null, tag);
    }

    static String exec(TerminalTalent t, String tag, String cmd, String cwd, int timeoutMs, int max) {
        return t.exec(cmd, cwd, timeoutMs, max, loc(tag)).getText();
    }

    static String validate(TerminalTalent t, String tag, String cmd) {
        return t.validateCommand(cmd, loc(tag));
    }

    static String ls(TerminalTalent t, String tag, String path, boolean recursive) throws Exception {
        return t.ls(path, recursive, false, null, tag);
    }

    static String read(TerminalTalent t, String tag, String path, Integer offset, Integer limit) throws Exception {
        return t.read(path, offset, limit, null, tag);
    }

    static String write(TerminalTalent t, String tag, String path, String content) throws Exception {
        return t.write(path, content, null, tag).getContent();
    }

    static String edit(TerminalTalent t, String tag, String path, TerminalTalent.EditOp... ops) throws Exception {
        return t.edit(path, Arrays.asList(ops), null, tag).getContent();
    }

    static String grep(TerminalTalent t, String tag, String pattern, String path) throws Exception {
        return t.grep(pattern, path, null, null, tag);
    }

    static String glob(TerminalTalent t, String tag, String pattern, String path) throws Exception {
        return t.glob(pattern, path, null, tag);
    }

    static String loose(TerminalSupport s, String tag, String content, String old, Integer line) {
        return s.findLooseMatchDiagnostics(content, old, line, loc(tag));
    }

    static String apply(TerminalSupport s, String tag, String content, String old, String neu, boolean all, Integer line) {
        return s.applyEditLogic(content, old, neu, all, line);
    }

    static String executeCode(ProcessExecutor e, String tag, Path root, String code, String cmd, String ext) {
        return e.executeCode(root, code, cmd, ext, null, 5000, 1000, null, loc(tag));
    }

    static String executeCmd(ProcessExecutor e, String tag, Path root, List<String> argv, int timeoutMs, int max) {
        return e.executeCmdResult(root, argv, null, timeoutMs, max, null, loc(tag)).getText();
    }

    static String truncate(String tag, String text, int max) {
        return ProcessExecutor.truncateForLlm(text, max, loc(tag));
    }

    static String binary(String tag, byte[] bytes) throws Exception {
        Method m = ProcessExecutor.class.getDeclaredMethod("summarizeBinaryOutput", byte[].class, java.util.Locale.class);
        m.setAccessible(true);
        return (String) m.invoke(null, bytes, loc(tag));
    }

    static String resolve(TerminalSupport s, String tag, Path work, String path, boolean write, boolean sandbox,
                          org.noear.solon.ai.sandbox.config.FilesystemConfig fs) throws Exception {
        return s.resolveSafePath(work, path, write, sandbox, false, fs).toString();
    }

    static String collapse(TerminalSupport s, String tag, String content, String old) throws Exception {
        Method m = TerminalSupport.class.getDeclaredMethod("tryTrimMatchDiagnostics", String.class, String.class, int.class, String.class, java.util.Locale.class);
        m.setAccessible(true);
        return (String) m.invoke(s, content, old, 0, "collapse", loc(tag));
    }

    static void mountSafety(TerminalSupport s, Path mountRoot, Path target) throws Exception {
        Method m = TerminalSupport.class.getDeclaredMethod("checkLocalMountPathSafety", Path.class, Path.class);
        m.setAccessible(true);
        m.invoke(s, mountRoot, target);
    }

    static String skillList(SkillTalent t, String tag, List<String> selected, List<String> allowed) {
        return t.skilllist(selected, allowed, tag);
    }

    static String skillSearch(SkillTalent t, String tag, String query) {
        return t.skillsearch(query, null, null, tag);
    }

    static String skillRead(SkillTalent t, String tag, String name, List<String> allowed) throws Exception {
        return t.skillread(name, allowed, tag);
    }

    static String skillRefresh(SkillTalent t, String tag) {
        return t.skillrefresh(tag);
    }

    // ---------------- 场景 ----------------

    interface Call {
        String call() throws Throwable;
    }

    /**
     * 调用并记录结果；抛异常时记录异常消息（不记录异常类型）
     */
    static String run(Path root, Call call) {
        return run(root, null, call);
    }

    static String run(Path root, String tag, Call call) {
        try {
            return norm(call.call(), root);
        } catch (Throwable e) {
            if (e instanceof java.lang.reflect.InvocationTargetException) {
                e = e.getCause();
            }
            return "! " + norm(message(e, tag), root);
        }
    }

    static String message(Throwable e, String tag) {
        return CliI18n.messageOf(e, loc(tag));
    }

    static java.util.Locale loc(String tag) {
        return AiI18n.toLocale(tag);
    }

    static TerminalTalent terminal(Path work, ShellMode mode, String shellCmd) throws Exception {
        MountManager mm = new MountManager(work.toString());
        return terminal(mm, mode, shellCmd);
    }

    static TerminalTalent terminal(MountManager mm, ShellMode mode, String shellCmd) throws Exception {
        TerminalTalent t = new TerminalTalent(mm, new ShellCommandFactory(mode, shellCmd),
                Files.createDirectories(java.nio.file.Paths.get(mm.getWorkDir()).getParent().resolve("cache")));
        t.setSandboxSystemRestrict(false);
        runtimes(t, "python3", "node");
        return t;
    }

    static TerminalTalent.EditOp op(String old, String neu, Integer line, Boolean all) {
        TerminalTalent.EditOp op = new TerminalTalent.EditOp();
        op.oldStr = old;
        op.newStr = neu;
        op.oldStrStartLine = line;
        op.replaceAll = all;
        return op;
    }

    /**
     * 场景分组（覆盖率变异时只重跑读取过该 key 的分组，分组越小越快）
     */
    static final String[] PARTS = {"skill", "clock", "instruction", "command", "executor", "file", "virtual", "support"};

    static boolean on(String part, String name) {
        return part == null || part.equals(name);
    }

    static Map<String, String> render(String tag, String suffix) throws Exception {
        return render(tag, suffix, null);
    }

    /**
     * @param part 场景分组（见 {@link #PARTS}；null 表示全部）
     */
    static Map<String, String> render(String tag, String suffix, String part) throws Exception {
        Path root = Files.createTempDirectory("cli-i18n");
        try {
            Map<String, String> out = new LinkedHashMap<>();
            renderTerminal(out, root, tag, suffix, part);
            if (on(part, "skill")) {
                renderSkill(out, tag, suffix);
            }
            if (on(part, "clock")) {
                renderClock(out, tag, suffix);
            }
            return out;
        } finally {
            Path locked = root.resolve("proj/tree/locked");
            if (Files.exists(locked)) {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
            }
            try (Stream<Path> s = Files.walk(root)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    p.toFile().setWritable(true);
                    p.toFile().setReadable(true);
                    p.toFile().setExecutable(true);
                    p.toFile().delete();
                });
            }
        }
    }

    static void renderClock(Map<String, String> out, String tag, String suffix) {
        ClockTalent clock = new ClockTalent();
        out.put("clock_tools", GoldenFiles.dumpTools(clock.getTools(prompt(tag))));
    }

    static void renderSkill(Map<String, String> out, String tag, String suffix) throws Exception {
        SkillTalent small = new SkillTalent(new FakeSkills(2));
        SkillTalent mid = new SkillTalent(new FakeSkills(2)).listThreshold(1).searchThreshold(10);
        SkillTalent big = new SkillTalent(new FakeSkills(3)).listThreshold(1).searchThreshold(2);
        SkillTalent empty = new SkillTalent(new FakeSkills(0));

        if (tag == null) {
            out.put("skill_description" + suffix, small.description(null));
        }
        out.put("skill_tools_small", GoldenFiles.dumpTools(small.getTools(prompt(tag))));
        out.put("skill_tools_big", GoldenFiles.dumpTools(big.getTools(prompt(tag))));

        out.put("skill_instruction_summary" + suffix, small.getInstruction(prompt(tag)));
        out.put("skill_instruction_list" + suffix, mid.getInstruction(prompt(tag)));
        out.put("skill_instruction_search" + suffix, big.getInstruction(prompt(tag)));

        Prompt selected = prompt(tag);
        SkillTalent.putSelected(selected, Arrays.asList("@m/s1", "@m/s2", "nope"));
        SkillTalent preload = new SkillTalent(new FakeSkills(2)).preloadCharLimit(60);
        out.put("skill_instruction_preload" + suffix, preload.getInstruction(selected));

        out.put("skill_list" + suffix, skillList(small, tag, null, null));
        out.put("skill_list_empty" + suffix, skillList(empty, tag, null, null));
        out.put("skill_list_too_many" + suffix, skillList(big, tag, null, null));
        out.put("skill_search" + suffix, skillSearch(small, tag, "s1"));
        out.put("skill_search_none" + suffix, skillSearch(small, tag, "none"));
        out.put("skill_read" + suffix, skillRead(small, tag, "@m/s1", null));
        out.put("skill_read_missing" + suffix, skillRead(small, tag, "nope", null));
        out.put("skill_read_denied" + suffix, skillRead(small, tag, "@m/s1", Collections.singletonList("@m/s2")));
        out.put("skill_refresh" + suffix, skillRefresh(small, tag));
    }

    static void renderTerminal(Map<String, String> out, Path root, String tag, String suffix, String part) throws Exception {
        Path work = Files.createDirectories(root.resolve("proj"));
        Path pool = Files.createDirectories(root.resolve("pool"));
        Path ro = Files.createDirectories(root.resolve("ro"));

        // ---- 文件夹具 ----
        Files.write(work.resolve("a.txt"), "one\ntwo\nthree\nfour\nfive\n".getBytes(StandardCharsets.UTF_8));
        Files.write(work.resolve("dup.txt"), "x\nx\n".getBytes(StandardCharsets.UTF_8));
        Files.write(work.resolve("empty.txt"), new byte[0]);
        Files.write(work.resolve("bin.dat"), new byte[]{0, 1, 2, 3, 0, 0, 5});
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longLine.append("0123456789");
        }
        Files.write(work.resolve("long.txt"), longLine.toString().getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(work.resolve("emptydir"));
        Files.createDirectories(work.resolve("g"));
        Files.write(work.resolve("g/o.txt"), "foo\nboo\nzoo\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(work.resolve(".soloncode/commands"));
        Path locked = Files.createDirectories(work.resolve("tree/locked"));
        Files.write(work.resolve("tree/x.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        Files.createSymbolicLink(pool.resolve("link"), work);

        TerminalTalent base = terminal(work, ShellMode.UNIX_SHELL, "bash");

        if (tag == null) {
            out.put("terminal_description" + suffix, base.description(null));
        }
        base.setBashAsyncEnabled(true);
        if (on(part, "instruction")) out.put("terminal_tools", GoldenFiles.dumpTools(tools(base, tag)));
        base.setBashAsyncEnabled(false);

        // ---- 指令 ----
        if (on(part, "instruction")) out.put("terminal_instruction_unix" + suffix, run(root, tag, () -> base.getInstruction(prompt(tag))));

        TerminalTalent open = terminal(work, ShellMode.UNIX_SHELL, "bash");
        open.setSandboxEnabled(false);
        runtimes(open, null, null);
        if (on(part, "instruction")) out.put("terminal_instruction_unix_open" + suffix, run(root, tag, () -> open.getInstruction(prompt(tag))));

        MountManager mounts = new MountManager(work.toString());
        mounts.register(Mount.builder().alias("@pool").description("公共池").type(MountType.FILES)
                .source(FileMountSource.of(pool)).writable(true).build());
        mounts.register(Mount.builder().alias("@ro").type(MountType.FILES)
                .source(FileMountSource.of(ro)).writable(false).build());
        mounts.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(Thread.currentThread().getContextClassLoader(), "test-skills"))
                .writable(false).build());
        mounts.register(Mount.builder().alias("@mem").type(MountType.FILES)
                .source(MemSource.readWrite().put("a.txt", "alpha\nbeta\n").put("dir/b.md", "# beta\n"))
                .writable(true).build());
        TerminalTalent mounted = terminal(mounts, ShellMode.UNIX_SHELL, "bash");
        mounted.setSandboxAllowUserHome(true);
        mounted.setBashAsyncEnabled(true);
        if (on(part, "instruction")) out.put("terminal_instruction_mounts" + suffix, run(root, tag, () -> mounted.getInstruction(prompt(tag))));

        TerminalTalent mountedOpen = terminal(mounts, ShellMode.UNIX_SHELL, "bash");
        mountedOpen.setSandboxEnabled(false);
        if (on(part, "instruction")) out.put("terminal_instruction_mounts_open" + suffix, run(root, tag, () -> mountedOpen.getInstruction(prompt(tag))));

        MountManager virtualOnly = new MountManager(work.toString());
        virtualOnly.register(Mount.builder().alias("@vm").type(MountType.FILES)
                .source(MemSource.readWrite()).writable(false).build());
        TerminalTalent virtual = terminal(virtualOnly, ShellMode.UNIX_SHELL, "bash");
        if (on(part, "instruction")) out.put("terminal_instruction_virtual" + suffix, run(root, tag, () -> virtual.getInstruction(prompt(tag))));
        TerminalTalent virtualOpen = terminal(virtualOnly, ShellMode.UNIX_SHELL, "bash");
        virtualOpen.setSandboxEnabled(false);
        if (on(part, "instruction")) out.put("terminal_instruction_virtual_open" + suffix, run(root, tag, () -> virtualOpen.getInstruction(prompt(tag))));

        TerminalTalent pwsh = terminal(work, ShellMode.POWERSHELL, "pwsh");
        if (on(part, "instruction")) out.put("terminal_instruction_pwsh" + suffix, run(root, tag, () -> pwsh.getInstruction(prompt(tag))));
        TerminalTalent ps51 = terminal(work, ShellMode.POWERSHELL, "powershell");
        runtimes(ps51, null, null);
        if (on(part, "instruction")) out.put("terminal_instruction_ps51" + suffix, run(root, tag, () -> ps51.getInstruction(prompt(tag))));
        TerminalTalent cmd = terminal(work, ShellMode.CMD, "cmd");
        if (on(part, "instruction")) out.put("terminal_instruction_cmd" + suffix, run(root, tag, () -> cmd.getInstruction(prompt(tag))));

        MountManager noWork = new MountManager("/");
        TerminalTalent nameless = new TerminalTalent(noWork, new ShellCommandFactory(ShellMode.UNIX_SHELL, "sh"), (Path) null);
        runtimes(nameless, "python3", null);
        if (on(part, "instruction")) out.put("terminal_instruction_nameless" + suffix, run(root, tag, () -> nameless.getInstruction(prompt(tag))));

        // ---- 命令校验 ----
        if (on(part, "command")) out.put("terminal_validate_empty" + suffix, validate(base, tag, ""));
        if (on(part, "command")) out.put("terminal_validate_kill" + suffix, norm(validate(base, tag, "kill -9 " + org.noear.solon.Utils.pid()), root)
                .replace(org.noear.solon.Utils.pid(), "<pid>"));
        if (on(part, "command")) out.put("terminal_validate_exit" + suffix, validate(base, tag, "echo a; exit 1"));
        if (on(part, "command")) out.put("terminal_validate_home" + suffix, validate(base, tag, "cat ~/a.txt"));
        if (on(part, "command")) out.put("terminal_validate_rm_windows" + suffix, validate(cmd, tag, "rmdir /s /q C:\\Windows"));
        if (on(part, "command")) out.put("terminal_sandbox_disabled" + suffix, TerminalTalent.sandboxDisabledText(loc(tag)));
        if (on(part, "command")) out.put("terminal_windows_prepare_failed" + suffix, TerminalTalent.windowsPrepareFailedText(loc(tag), new java.io.IOException("disk full")));

        // ---- bash / exec ----
        if (on(part, "command")) out.put("terminal_bash_ok" + suffix, run(root, tag, () -> bash(base, tag, "true", 10000, 1000)));
        if (on(part, "command")) out.put("terminal_bash_danger" + suffix, run(root, tag, () -> bash(base, tag, "rm -rf /", 10000, 1000)));
        if (on(part, "command")) out.put("terminal_bash_timeout" + suffix, run(root, tag, () -> bash(base, tag, "echo a && exec sleep 5", 600, 1000)));
        if (on(part, "command")) out.put("terminal_bash_truncated" + suffix, run(root, tag, () -> bash(base, tag, "seq 1 300", 10000, 100)));
        if (on(part, "command")) out.put("terminal_bash_home" + suffix, run(root, tag, () -> bash(base, tag, "cat ~/a.txt", 10000, 1000)));
        if (on(part, "command")) out.put("terminal_bash_start_danger" + suffix, run(root, tag, () -> bashStart(base, tag, "")));
        if (on(part, "command")) out.put("terminal_bash_start_home" + suffix, run(root, tag, () -> bashStart(base, tag, "cat ~/a.txt")));

        if (on(part, "command")) out.put("terminal_exec_empty" + suffix, run(root, tag, () -> exec(base, tag, "", null, 1000, 100)));
        TerminalTalent policy = terminal(work, ShellMode.UNIX_SHELL, "bash");
        policy.setWorkRootPolicy(p -> false);
        if (on(part, "command")) out.put("terminal_exec_policy" + suffix, run(root, tag, () -> exec(policy, tag, "true", work.toString(), 1000, 100)));
        if (on(part, "command")) out.put("terminal_exec_home" + suffix, run(root, tag, () -> exec(base, tag, "cat ~/a.txt", null, 1000, 100)));

        TerminalTalent strict = terminal(work, ShellMode.UNIX_SHELL, "bash");
        strict.setSandboxSystemRestrict(true);
        strict.setSandboxSelfBootstrap(false);
        if (SandboxManager.isSandboxingEnabled()) {
            SandboxManager.reset();
        }
        if (on(part, "command")) out.put("terminal_exec_no_sandbox" + suffix, run(root, tag, () -> exec(strict, tag, "true", null, 1000, 100)));
        if (on(part, "command")) out.put("terminal_bash_start_no_sandbox" + suffix, run(root, tag, () -> bashStart(strict, tag, "true")));

        MountManager matMounts = new MountManager(work.toString());
        DowngradedSource down = new DowngradedSource();
        matMounts.register(Mount.builder().alias("@down").type(MountType.FILES).source(down).build());
        TerminalTalent downgraded = terminal(matMounts, ShellMode.UNIX_SHELL, "bash");
        down.downgradeAfter(2);
        if (on(part, "command")) out.put("terminal_bash_mat_empty" + suffix, run(root, tag, () -> bash(downgraded, tag, "ls @down", 1000, 100)));
        MountManager brokenMounts = new MountManager(work.toString());
        brokenMounts.register(Mount.builder().alias("@broken").type(MountType.FILES).source(new BrokenMaterializableSource()).build());
        TerminalTalent broken = terminal(brokenMounts, ShellMode.UNIX_SHELL, "bash");
        if (on(part, "command")) out.put("terminal_bash_mat_failed" + suffix, run(root, tag, () -> bash(broken, tag, "ls @broken", 1000, 100)));
        if (on(part, "command")) out.put("terminal_bash_start_mat_failed" + suffix, run(root, tag, () -> bashStart(broken, tag, "ls @broken")));
        TerminalTalent noCache = new TerminalTalent(brokenMounts, new ShellCommandFactory(ShellMode.UNIX_SHELL, "bash"), (Path) null);
        noCache.setSandboxSystemRestrict(false);
        if (on(part, "command")) out.put("terminal_bash_mat_no_cache" + suffix, run(root, tag, () -> bash(noCache, tag, "ls @broken", 1000, 100)));

        // ---- 执行器 ----
        ProcessExecutor executor = new ProcessExecutor();
        if (on(part, "executor")) out.put("executor_code_no_cmd" + suffix, run(root, tag, () -> executeCode(executor, tag, work, "x", "", ".sh")));
        if (on(part, "executor")) out.put("executor_code_failed" + suffix, run(root, tag, () -> executeCode(executor, tag, work, "x", "sh", "/bad/")));
        if (on(part, "executor")) out.put("executor_ok" + suffix, run(root, tag, () -> executeCmd(executor, tag, work, Arrays.asList("true"), 5000, 1000)));
        if (on(part, "executor")) out.put("executor_timeout_empty" + suffix, run(root, tag, () -> executeCmd(executor, tag, work, Arrays.asList("sleep", "5"), 300, 1000)));
        if (on(part, "executor")) out.put("executor_system_failed" + suffix, run(root, tag, () -> executeCmd(executor, tag, work, Arrays.asList("/no-such-cmd-xyz"), 5000, 1000)));
        ProcessExecutor capped = new ProcessExecutor();
        capped.setMaxOutputSize(1000);
        if (on(part, "executor")) out.put("executor_size_capped" + suffix, run(root, tag, () -> executeCmd(capped, tag, work, Arrays.asList("sh", "-c", "yes"), 10000, 100000)
                .replaceAll("^(y\\n)*y?", "<y...>")));
        if (on(part, "executor")) {
            AtomicReference<String> interrupted = new AtomicReference<>();
            Thread th = new Thread(() -> interrupted.set(run(root, tag, () -> executeCmd(executor, tag, work, Arrays.asList("sleep", "5"), 10000, 1000))));
            th.start();
            Thread.sleep(300);
            th.interrupt();
            th.join();
            out.put("executor_interrupted" + suffix, interrupted.get());
        }

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            text.append("line ").append(i).append('\n');
        }
        if (on(part, "executor")) out.put("executor_truncate" + suffix, truncate(tag, text.toString(), 60));

        byte[] readable = new byte[7000];
        for (int i = 0; i < readable.length; i++) {
            readable[i] = (byte) ('a' + (i % 26));
        }
        readable[10] = 0;
        if (on(part, "executor")) out.put("executor_binary_readable" + suffix, binary(tag, readable));
        if (on(part, "executor")) out.put("executor_binary_class" + suffix, binary(tag, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, 1, 2, 3, 4}));
        if (on(part, "executor")) out.put("executor_binary_unknown" + suffix, binary(tag, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));

        // ---- 文件工具（本地） ----

        if (on(part, "file")) out.put("terminal_ls_missing" + suffix, run(root, tag, () -> ls(base, tag, "nope", false)));
        if (on(part, "file")) out.put("terminal_ls_empty" + suffix, run(root, tag, () -> ls(base, tag, "emptydir", false)));
        if (on(part, "file")) out.put("terminal_ls_tree_denied" + suffix, run(root, tag, () -> ls(base, tag, "tree", true)));
        if (on(part, "file")) out.put("terminal_read" + suffix, run(root, tag, () -> read(base, tag, "a.txt", null, null)));
        if (on(part, "file")) out.put("terminal_read_limit" + suffix, run(root, tag, () -> read(base, tag, "a.txt", 2, 2)));
        if (on(part, "file")) out.put("terminal_read_offset_out" + suffix, run(root, tag, () -> read(base, tag, "a.txt", 99, null)));
        if (on(part, "file")) out.put("terminal_read_missing" + suffix, run(root, tag, () -> read(base, tag, "nope.txt", null, null)));
        if (on(part, "file")) out.put("terminal_read_binary" + suffix, run(root, tag, () -> read(base, tag, "bin.dat", null, null)));
        if (on(part, "file")) out.put("terminal_read_empty" + suffix, run(root, tag, () -> read(base, tag, "empty.txt", null, null)));
        TerminalTalent tiny = terminal(work, ShellMode.UNIX_SHELL, "bash");
        tiny.setMaxCharacterLimit(40);
        if (on(part, "file")) out.put("terminal_read_truncated" + suffix, run(root, tag, () -> read(tiny, tag, "a.txt", null, null)));
        if (on(part, "file")) out.put("terminal_read_long_line" + suffix, run(root, tag, () -> read(tiny, tag, "long.txt", null, null)));
        if (on(part, "file")) out.put("terminal_read_absolute" + suffix, run(root, tag, () -> read(base, tag, "/etc/hosts", null, null)));
        if (on(part, "file")) out.put("terminal_read_outside" + suffix, run(root, tag, () -> read(base, tag, "../pool/x", null, null)));
        if (on(part, "file")) out.put("terminal_read_protected" + suffix, run(root, tag, () -> write(base, tag, ".soloncode/commands/x.md", "x")));
        if (on(part, "file")) out.put("terminal_read_home" + suffix, run(root, tag, () -> read(base, tag, "~/x.txt", null, null)));

        if (on(part, "file")) out.put("terminal_write" + suffix, run(root, tag, () -> write(base, tag, "w.txt", "hello")));
        if (on(part, "file")) out.put("terminal_edit" + suffix, run(root, tag, () -> edit(base, tag, "w.txt", op("hello", "world", null, null))));
        if (on(part, "file")) out.put("terminal_edit_missing" + suffix, run(root, tag, () -> edit(base, tag, "nope.txt", op("a", "b", null, null))));
        if (on(part, "file")) out.put("terminal_edit_empty_old" + suffix, run(root, tag, () -> edit(base, tag, "a.txt", op("", "b", null, null))));
        if (on(part, "file")) out.put("terminal_edit_all_missing" + suffix, run(root, tag, () -> edit(base, tag, "a.txt", op("zzz", "b", null, true))));
        if (on(part, "file")) out.put("terminal_edit_mismatch" + suffix, run(root, tag, () -> edit(base, tag, "a.txt", op("one\ntwX", "b", 1, null))));
        if (on(part, "file")) out.put("terminal_edit_not_unique" + suffix, run(root, tag, () -> edit(base, tag, "dup.txt", op("x", "y", null, null))));
        if (on(part, "file")) out.put("terminal_edit_apply_failed" + suffix, run(root, tag, () -> edit(base, tag, "a.txt",
                op("two", "2", null, null), op("two\nthree", "x", null, null))));

        if (on(part, "file")) out.put("terminal_grep_none" + suffix, run(root, tag, () -> grep(base, tag, "zzz-none", "g")));
        if (on(part, "file")) out.put("terminal_grep_sep" + suffix, run(root, tag, () -> grep(base, tag, "x", "a;b")));
        if (on(part, "file")) out.put("terminal_grep_missing" + suffix, run(root, tag, () -> grep(base, tag, "x", "nope")));
        TerminalTalent grepTiny = terminal(work, ShellMode.UNIX_SHELL, "bash");
        grepTiny.setMaxCharacterLimit(10);
        if (on(part, "file")) out.put("terminal_grep_truncated" + suffix, run(root, tag, () -> grep(grepTiny, tag, "o", "g")));
        if (on(part, "file")) out.put("terminal_glob_none" + suffix, run(root, tag, () -> glob(base, tag, "**/*.none", "g")));
        if (on(part, "file")) out.put("terminal_glob_sep" + suffix, run(root, tag, () -> glob(base, tag, "*.txt", "a\nb")));

        // ---- 挂载 ----
        if (on(part, "file")) out.put("terminal_write_readonly_mount" + suffix, run(root, tag, () -> write(mounted, tag, "@ro/x.txt", "x")));
        if (on(part, "file")) out.put("terminal_read_mount_outside" + suffix, run(root, tag, () -> read(mounted, tag, "@pool/../proj/a.txt", null, null)));
        if (on(part, "file")) out.put("terminal_read_mount_symlink" + suffix, run(root, tag, () -> read(mounted, tag, "@pool/link/a.txt", null, null)));

        // ---- 虚拟来源 ----
        if (on(part, "virtual")) out.put("terminal_vls" + suffix, run(root, tag, () -> ls(mounted, tag, "@mem", false)));
        if (on(part, "virtual")) out.put("terminal_vls_missing" + suffix, run(root, tag, () -> ls(mounted, tag, "@mem/nope", false)));
        if (on(part, "virtual")) out.put("terminal_vls_empty" + suffix, run(root, tag, () -> ls(mounted, tag, "@mem/.hidden-only", false)));
        if (on(part, "virtual")) out.put("terminal_vread" + suffix, run(root, tag, () -> read(mounted, tag, "@mem/a.txt", null, null)));
        if (on(part, "virtual")) out.put("terminal_vread_limit" + suffix, run(root, tag, () -> read(mounted, tag, "@mem/a.txt", 1, 1)));
        if (on(part, "virtual")) out.put("terminal_vread_missing" + suffix, run(root, tag, () -> read(mounted, tag, "@mem/nope.txt", null, null)));
        if (on(part, "virtual")) out.put("terminal_vread_dir" + suffix, run(root, tag, () -> read(mounted, tag, "@mem/dir", null, null)));
        if (on(part, "virtual")) out.put("terminal_vread_offset_out" + suffix, run(root, tag, () -> read(mounted, tag, "@mem/a.txt", 99, null)));
        if (on(part, "virtual")) out.put("terminal_vwrite" + suffix, run(root, tag, () -> write(mounted, tag, "@mem/n.txt", "new")));
        if (on(part, "virtual")) out.put("terminal_vedit" + suffix, run(root, tag, () -> edit(mounted, tag, "@mem/n.txt", op("new", "neu", null, null))));
        if (on(part, "virtual")) out.put("terminal_vedit_missing" + suffix, run(root, tag, () -> edit(mounted, tag, "@mem/nope.txt", op("a", "b", null, null))));
        if (on(part, "virtual")) out.put("terminal_vedit_empty_old" + suffix, run(root, tag, () -> edit(mounted, tag, "@mem/a.txt", op("", "b", null, null))));
        if (on(part, "virtual")) out.put("terminal_vedit_mismatch" + suffix, run(root, tag, () -> edit(mounted, tag, "@mem/a.txt", op("zzz", "b", null, null))));
        if (on(part, "virtual")) out.put("terminal_vedit_all_missing" + suffix, run(root, tag, () -> edit(mounted, tag, "@mem/a.txt", op("zzz", "b", null, true))));
        if (on(part, "virtual")) out.put("terminal_vgrep" + suffix, run(root, tag, () -> grep(mounted, tag, "beta", "@mem")));
        if (on(part, "virtual")) out.put("terminal_vgrep_none" + suffix, run(root, tag, () -> grep(mounted, tag, "zzz", "@mem")));
        if (on(part, "virtual")) out.put("terminal_vgrep_missing" + suffix, run(root, tag, () -> grep(mounted, tag, "zzz", "@mem/nope")));
        if (on(part, "virtual")) out.put("terminal_vglob_none" + suffix, run(root, tag, () -> glob(mounted, tag, "*.none", "@mem")));
        if (on(part, "virtual")) out.put("terminal_vglob_missing" + suffix, run(root, tag, () -> glob(mounted, tag, "*.none", "@mem/nope")));

        MountManager vm = new MountManager(work.toString());
        MemSource big = MemSource.readWrite();
        vm.register(Mount.builder().alias("@off").type(MountType.FILES).source(MemSource.readWrite()).enabled(false).build());
        vm.register(Mount.builder().alias("@noread").type(MountType.FILES)
                .source(new MemSource(MountCapabilities.builder().writable(true).build())).build());
        vm.register(Mount.builder().alias("@rov").type(MountType.FILES).source(MemSource.readWrite()).writable(false).build());
        vm.register(Mount.builder().alias("@big").type(MountType.FILES).source(new MemSource(big.caps) {
            @Override
            public org.noear.solon.ai.mount.source.MountEntry stat(String path) {
                return new org.noear.solon.ai.mount.source.MountEntry(path, path, false, 10 * 1024 * 1024 + 1, null, null);
            }

            @Override
            public List<org.noear.solon.ai.mount.source.MountEntry> find(String path, org.noear.solon.ai.mount.source.FindOptions o) {
                return new ArrayList<>(Collections.singletonList(stat("big.txt")));
            }
        }).build());
        TerminalTalent vt = terminal(vm, ShellMode.UNIX_SHELL, "bash");
        if (on(part, "virtual")) out.put("terminal_v_disabled" + suffix, run(root, tag, () -> read(vt, tag, "@off/a.txt", null, null)));
        if (on(part, "virtual")) out.put("terminal_v_unreadable" + suffix, run(root, tag, () -> read(vt, tag, "@noread/a.txt", null, null)));
        if (on(part, "virtual")) out.put("terminal_v_readonly" + suffix, run(root, tag, () -> write(vt, tag, "@rov/a.txt", "x")));
        if (on(part, "virtual")) out.put("terminal_v_too_large" + suffix, run(root, tag, () -> read(vt, tag, "@big/big.txt", null, null)));
        if (on(part, "virtual")) out.put("terminal_vgrep_skipped" + suffix, run(root, tag, () -> grep(vt, tag, "x", "@big/big.txt")));

        // ---- 编辑匹配诊断 / 执行期失败 / 策略拒绝（内部方法） ----
        TerminalSupport s = new TerminalSupport(new MountManager(work.toString()), Collections.emptySet(), ShellMode.UNIX_SHELL, null);
        String content = "a\nb\nc\n";
        if (on(part, "support")) out.put("support_loose_no_content" + suffix, String.valueOf(loose(s, tag, content, "a", 99)));
        if (on(part, "support")) out.put("support_loose_blank" + suffix, String.valueOf(loose(s, tag, content, "\n\n", 1)));
        if (on(part, "support")) out.put("support_loose_lines" + suffix, String.valueOf(loose(s, tag, content, "b\nc\nd\ne", 2)));
        if (on(part, "support")) out.put("support_loose_mismatch" + suffix, String.valueOf(loose(s, tag, content, "a\nx", 1)));
        if (on(part, "support")) out.put("support_loose_collapse" + suffix, String.valueOf(loose(s, tag, "a  b\nc\n", "a b c", 1)));
        if (on(part, "support")) out.put("support_apply_empty" + suffix, run(root, tag, () -> apply(s, tag, content, "", "x", false, null)));
        if (on(part, "support")) out.put("support_apply_all_missing" + suffix, run(root, tag, () -> apply(s, tag, content, "zzz", "x", true, null)));
        if (on(part, "support")) out.put("support_apply_missing" + suffix, run(root, tag, () -> apply(s, tag, content, "zzz", "x", false, null)));
        if (on(part, "support")) out.put("support_apply_not_unique" + suffix, run(root, tag, () -> apply(s, tag, "x\nx\n", "x", "y", false, null)));
        org.noear.solon.ai.sandbox.config.FilesystemConfig denyRead = new org.noear.solon.ai.sandbox.config.FilesystemConfig(
                Collections.singletonList("secret"), null, null, null, true);
        org.noear.solon.ai.sandbox.config.FilesystemConfig allowWrite = new org.noear.solon.ai.sandbox.config.FilesystemConfig(
                null, null, Collections.singletonList("out"), null, true);
        if (on(part, "support")) out.put("support_deny_read" + suffix, run(root, tag, () -> resolve(s, tag, work, "secret/a.txt", false, true, denyRead)));
        if (on(part, "support")) out.put("support_allow_write" + suffix, run(root, tag, () -> resolve(s, tag, work, "a.txt", true, true, allowWrite)));
        if (on(part, "support")) out.put("support_loose_collapse_direct" + suffix, run(root, tag, () -> collapse(s, tag, "a  b\n", "a b x")));
        if (on(part, "support")) out.put("support_loose_end" + suffix, String.valueOf(loose(s, tag, content, "a", 4)));
        if (on(part, "support")) out.put("support_mount_outside" + suffix, run(root, tag, () -> {
            mountSafety(s, pool, pool.resolve("../proj/a.txt"));
            return "ok";
        }));
        if (on(part, "support")) out.put("support_mount_symlink" + suffix, run(root, tag, () -> {
            mountSafety(s, pool, pool.resolve("link/a.txt"));
            return "ok";
        }));
        MountManager memMounts = new MountManager(work.toString());
        memMounts.register(Mount.builder().alias("@mem").type(MountType.FILES).source(MemSource.readWrite()).build());
        memMounts.register(Mount.builder().alias("@rootless").type(MountType.FILES).source(new RootlessSource(pool)).writable(true).build());
        TerminalSupport ms = new TerminalSupport(memMounts, Collections.emptySet(), ShellMode.UNIX_SHELL, null);
        if (on(part, "support")) out.put("support_mount_not_local" + suffix, run(root, tag, () -> resolve(ms, tag, work, "@mem/a.txt", false, true, null)));
        if (on(part, "support")) out.put("support_mount_no_root" + suffix, run(root, tag, () -> resolve(ms, tag, work, "@rootless/a.txt", false, true, null)));
        StaleMountManager ghost = new StaleMountManager(work.toString(), "@ghost", null, FileMountSource.of(pool));
        TerminalSupport gs = new TerminalSupport(ghost, Collections.emptySet(), ShellMode.UNIX_SHELL, null);
        if (on(part, "support")) out.put("support_mount_unknown" + suffix, run(root, tag, () -> resolve(gs, tag, work, "@ghost/a.txt", false, true, null)));
        StaleMountManager stale = new StaleMountManager(work.toString(), "@gone",
                Mount.builder().alias("@gone").type(MountType.FILES).source(MemSource.readWrite()).enabled(false).build(), MemSource.readWrite());
        TerminalTalent staleTalent = terminal(stale, ShellMode.UNIX_SHELL, "bash");
        if (on(part, "support")) out.put("terminal_v_unavailable" + suffix, run(root, tag, () -> read(staleTalent, tag, "@gone/a.txt", null, null)));
        Path cacheRoot = Files.createDirectories(root.resolve("cache2"));
        TerminalSupport cached = new TerminalSupport(new MountManager(work.toString()), Collections.emptySet(), ShellMode.UNIX_SHELL, null);
        cached.setMaterializer(new MountMaterializer(cacheRoot));
        if (on(part, "support")) out.put("support_cache_path" + suffix, run(root, tag, () -> resolve(cached, tag, work,
                cacheRoot.resolve("x").toString(), false, false, null)));
    }
}
