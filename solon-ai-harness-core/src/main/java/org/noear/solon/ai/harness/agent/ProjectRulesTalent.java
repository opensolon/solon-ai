/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目规范才能（AGENTS.md 约定，参考 opencode 的指令发现）：主代理与所有子代理共用。
 *
 * <p><b>为什么是才能</b>：才能指令追加在系统提示词模板（角色段）渲染之后，所以代理定义正文、引擎 systemPrompt、
 * {@code HarnessInput.systemPrompt} 任一整段替换角色段都不会把它丢掉，内容里的 {@code #{...}} 也原样保留。</p>
 *
 * <p><b>读取</b>：一律经工作区来源（{@link MountSource}）读取，不直接访问本地磁盘——工作区来源可以是本地目录，
 * 也可以是数据库等实现。与 opencode 一致文件名写死为 {@code AGENTS.md}（与 opencode 不同，不读 CLAUDE.md——
 * 那是 Claude Code 的规范，不是本约定）。从本轮工作目录（{@code __cwd}）往上到工作区根，由近及远收集每一层；
 * 工作目录在工作区外时只读工作区根（opencode 往上可走到用户目录；这里以工作区为界，不读工作区之外的文件）。</p>
 *
 * <p><b>只管通用约定</b>：项目各层的 AGENTS.md 是各工具共认的约定；用户级规范（opencode 的 {@code <配置目录>/AGENTS.md}）、
 * 工具专属目录里的规范、配置里的 instructions 等位置因产品而异，本才能不读，由宿主自行处理（例如并入引擎 systemPrompt）。</p>
 *
 * <p><b>每次运行现读</b>：每次运行开始时读取并写进系统提示词（文件改了，下一次运行的系统提示词随之变化）；
 * 运行中途文件改了，由 {@link #getInterceptor() 拦截器}在推理前追加一条变更消息（不再适用 / 新增 / 有变化的整段原文）。</p>
 *
 * <p><b>子目录按需加载</b>（对齐 opencode 的 read 工具）：read 读文件、ls 列目录成功后，从文件所在目录（列目录时为该目录本身）
 * 往上找规范文件，直到本轮工作目录（不含，工作目录及以上已在系统提示词里）；以消息注入本次运行，按工作记忆里的注入记录去重，
 * 压缩后不可见了，再读到时会重新注入。</p>
 *
 * <p><b>篇幅</b>：与 opencode 一致不设总预算；单个文件最多读 1MB 作为安全上限，超出部分不读，并提示模型用 read 读原文。</p>
 *
 * <p><b>健壮性</b>：读取出错时沿用上次读到的内容（只有条目确实不存在才去掉）；同一个文件经不同层（如软链接别名）到达时只收一次；
 * 判断工作目录是否在工作区内时按真实路径比较（与 cwdPolicy 一致）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public class ProjectRulesTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectRulesTalent.class);

    /**
     * 才能名（出现在系统提示词的才能标题里，也是才能表的键）
     */
    public static final String NAME = "ProjectRules";

    /** 规范文件名：只认 AGENTS.md（不读 CLAUDE.md，它不是本约定的规范） */
    static final String FILE_NAME = "AGENTS.md";
    /** 单个文件的读取安全上限（字节） */
    static final int MAX_FILE_BYTES = 1024 * 1024;

    /** 注入消息的来源标记（metadata 的 source 值） */
    static final String MESSAGE_SOURCE = "project_rules";
    /** 注入消息 metadata：子目录规范的路径列表（去重账本） */
    static final String META_PATHS = "project_rules.paths";
    /** 注入消息 metadata：变更消息告知后的规范（各段原文） */
    static final String META_PARTS = "project_rules.parts";
    /** 本次运行写进系统提示词的规范（各段原文，记在本次提示词的属性上，拦截器据此判断运行中途是否有变化） */
    static final String ATTR_RUN_PARTS = "harness.projectRules.runParts";

    private final MountSource workspaceSource;
    private final String workspace;
    private final ProjectRulesInterceptor interceptor = new ProjectRulesInterceptor(this);
    /** 每个文件上次读到的内容（读取出错时沿用） */
    private final Map<String, Part> lastRead = new ConcurrentHashMap<>();

    /**
     * @param workspaceSource 工作区来源（为 null 时不读）
     * @param workspace       工作区根路径（用于解析本轮工作目录；为空时只读工作区根）
     */
    public ProjectRulesTalent(@Nullable MountSource workspaceSource, @Nullable String workspace) {
        this.workspaceSource = workspaceSource;
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description(Prompt prompt) {
        return HarnessI18n.get(AiI18n.localeOf(prompt), HarnessI18nKeys.PROJECT_RULES_DESCRIPTION);
    }

    /**
     * 变更消息与子目录按需加载的拦截器（与本才能成对挂到代理上）
     */
    ReActInterceptor getInterceptor() {
        return interceptor;
    }

    /**
     * 本次运行写进系统提示词的规范（现读）。
     *
     * <p>有副作用：把本次各段原文记到 prompt 属性上（{@link #ATTR_RUN_PARTS}），供拦截器比较运行中途的变化——
     * 只应在代理真正运行时（激活才能）调用，不要用于预览系统提示词。</p>
     */
    @Override
    public String getInstruction(Prompt prompt) {
        List<Part> parts = load(prompt == null ? null : prompt.attrAs(HarnessEngine.ATTR_CWD));
        if (prompt != null) {
            prompt.attrPut(ATTR_RUN_PARTS, Part.toMaps(parts));
        }
        return parts.isEmpty() ? null : render(parts, AiI18n.localeOf(prompt));
    }

    // ---------------- 收集 ----------------

    /**
     * 按本轮工作目录收集规范（工作目录由近及远到工作区根；没有时返回空列表）
     *
     * @param cwd 本轮工作目录（为空时按工作区根）
     */
    List<Part> load(@Nullable String cwd) {
        List<Part> parts = new ArrayList<>();
        if (workspaceSource == null) {
            return parts;
        }

        Set<String> seen = new HashSet<>();
        List<String> dirs = new ArrayList<>(relativeDirsOf(workspace, cwd));
        Collections.reverse(dirs);
        dirs.add("");
        for (String dir : dirs) {
            addFile(parts, seen, dir + FILE_NAME);
        }
        return parts;
    }

    /**
     * 子目录按需发现（read / ls 成功后调用）：从目标所在目录往上，直到本轮工作目录或工作区根（不含）。
     *
     * @param cwd      本轮工作目录（为空时按工作区根）
     * @param toolPath 工具参数里的路径（相对工作目录或绝对路径；逻辑路径 {@code @xxx} 不处理）
     * @param isDir    目标是否目录（ls 为 true：从该目录本身开始找）
     * @return 由近及远的规范段（不含被读的文件本身；工作区外的目标返回空）
     */
    List<Part> discover(@Nullable String cwd, @Nullable String toolPath, boolean isDir) {
        if (workspaceSource == null || Assert.isEmpty(workspace) || Assert.isEmpty(toolPath) || toolPath.startsWith("@")) {
            return Collections.emptyList();
        }

        try {
            Path root = realOf(Paths.get(workspace));
            Path base = Assert.isEmpty(cwd) ? root : realOf(Paths.get(cwd));
            Path target = realOf(base.resolve(toolPath));
            if (target.startsWith(root) == false) {
                return Collections.emptyList();
            }
            String targetRel = slash(root.relativize(target).toString());

            List<Part> parts = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            for (Path dir = isDir ? target : target.getParent();
                 dir != null && dir.startsWith(root) && dir.equals(root) == false;
                 dir = dir.getParent()) {
                if (base.startsWith(dir)) {
                    break; // 工作目录及其上层已在系统提示词里
                }

                String path = slash(root.relativize(dir).toString()) + "/" + FILE_NAME;
                if (path.equals(targetRel)) {
                    continue; // 被读的就是它：模型已经看到，不再注入
                }
                addFile(parts, seen, path);
            }
            return parts;
        } catch (Throwable e) {
            return Collections.emptyList(); // 发现失败不影响工具本身
        }
    }

    /** 读一个文件并收入（已由前面的层收过、或不存在时跳过） */
    private void addFile(List<Part> parts, Set<String> seen, String path) {
        String id = identityOf(path);
        if (seen.contains(id)) {
            return;
        }

        Part part = read(path);
        if (part != null) {
            seen.add(id);
            parts.add(part);
        }
    }

    /**
     * 文件身份（用于去重）：本地来源按真实路径（软链接别名也能认出同一个文件）；非本地来源按相对路径。
     */
    private String identityOf(String path) {
        try {
            Optional<Path> local = workspaceSource.getLocalPath(path);
            if (local.isPresent()) {
                return "f:" + realOf(local.get());
            }
        } catch (Throwable ignore) {
            // 退回按相对路径
        }
        return "w:" + path;
    }

    // ---------------- 渲染 ----------------

    /**
     * 渲染为一段指令（opencode 格式：每段「来源行 + 内容」，段间空一行；读取时截断的段带上用 read 读原文的提示）
     */
    static String render(List<Part> parts, @Nullable Locale locale) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (i > 0) {
                buf.append("\n\n");
            }
            buf.append(HarnessI18n.fmt(locale, HarnessI18nKeys.PROJECT_RULES_SOURCE, "path", part.path))
                    .append('\n').append(part.content);
            if (part.truncated) {
                buf.append("\n\n").append(HarnessI18n.fmt(locale, HarnessI18nKeys.PROJECT_RULES_TRUNCATED, "path", part.path));
            }
        }
        return buf.toString();
    }

    /**
     * 变更消息（opencode 的 renderUpdate 顺序：先列不再适用的，再列新增的，最后是有变化的；新增与有变化的给整段原文）
     *
     * @return 没有可见变化时返回 null
     */
    static @Nullable String renderUpdate(List<Part> previous, List<Part> current, @Nullable Locale locale) {
        if (current.isEmpty()) {
            return previous.isEmpty() ? null : HarnessI18n.get(locale, HarnessI18nKeys.PROJECT_RULES_UPDATE_ALL_REMOVED);
        }

        Map<String, Part> before = byPath(previous);
        Map<String, Part> after = byPath(current);
        List<String> out = new ArrayList<>();

        for (Part part : previous) {
            if (after.containsKey(part.path) == false) {
                out.add(HarnessI18n.fmt(locale, HarnessI18nKeys.PROJECT_RULES_UPDATE_REMOVED, "path", part.path));
            }
        }
        for (Part part : current) {
            Part old = before.get(part.path);
            if (old == null) {
                out.add(HarnessI18n.get(locale, HarnessI18nKeys.PROJECT_RULES_UPDATE_ADDED) + "\n"
                        + render(Collections.singletonList(part), locale));
            }
        }
        for (Part part : current) {
            Part old = before.get(part.path);
            if (old != null && old.equals(part) == false) {
                out.add(HarnessI18n.get(locale, HarnessI18nKeys.PROJECT_RULES_UPDATE_CHANGED) + "\n"
                        + render(Collections.singletonList(part), locale));
            }
        }

        return out.isEmpty() ? null : String.join("\n\n", out);
    }

    private static Map<String, Part> byPath(List<Part> parts) {
        Map<String, Part> map = new LinkedHashMap<>();
        for (Part part : parts) {
            map.put(part.path, part);
        }
        return map;
    }

    // ---------------- 路径 ----------------

    /**
     * 本轮工作目录相对工作区根的各层目录（如 {@code a/b} → {@code ["a/", "a/b/"]}）；不在工作区内或就是根时为空。
     * 两边都按真实路径比较：软链接别名不会漏层，工作区内指向外部的软链接目录视为工作区外。
     */
    static List<String> relativeDirsOf(@Nullable String workspace, @Nullable String cwd) {
        if (Assert.isEmpty(workspace) || Assert.isEmpty(cwd)) {
            return Collections.emptyList();
        }

        try {
            Path root = realOf(Paths.get(workspace));
            Path dir = realOf(Paths.get(cwd));
            if (dir.startsWith(root) == false || dir.equals(root)) {
                return Collections.emptyList();
            }

            List<String> dirs = new ArrayList<>();
            StringBuilder prefix = new StringBuilder();
            for (Path seg : root.relativize(dir)) {
                prefix.append(seg.toString()).append('/');
                dirs.add(prefix.toString());
            }
            return dirs;
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    /**
     * 真实路径（与 cwdPolicy 的判定一致：先规范化防 {@code ../}，存在时解析软链接，兼容 {@code /tmp → /private/tmp}）；
     * 不存在时取最近一个存在的祖先目录的真实路径再拼上剩余部分，避免一边解析、一边未解析导致前缀对不上。
     */
    static Path realOf(Path path) {
        Path p = path.toAbsolutePath().normalize();
        Path ancestor = p;
        while (ancestor != null && Files.exists(ancestor) == false) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            return p;
        }
        try {
            return ancestor.toRealPath().resolve(ancestor.relativize(p)).normalize();
        } catch (IOException | RuntimeException e) {
            return p;
        }
    }

    private static String slash(String path) {
        return path.replace('\\', '/');
    }

    // ---------------- 读取 ----------------

    /**
     * 读取一个文件：条目不存在、是目录或内容为空时返回 null；读取出错时沿用上次读到的内容（来源抖动不该让规范消失），
     * 没有上次内容才返回 null。
     */
    private @Nullable Part read(String path) {
        try {
            MountEntry entry = workspaceSource.stat(path);
            Part part = (entry == null || entry.isDirectory()) ? null : readLimited(workspaceSource, path, MAX_FILE_BYTES);
            if (part == null) {
                lastRead.remove(path);
            } else {
                lastRead.put(path, part);
            }
            return part;
        } catch (Throwable e) {
            Part last = lastRead.get(path);
            LOG.warn("Project rules load failed{}: path={}, error={}", last == null ? "" : ", keep last content", path, e.getMessage());
            return last;
        }
    }

    /**
     * 按 UTF-8 读取最多 {@code limit} 字节（截断处被切开的多字节字符丢掉），去掉 BOM 与首尾空白；内容为空时返回 null。
     */
    static @Nullable Part readLimited(MountSource source, String path, int limit) throws IOException {
        byte[] bytes;
        boolean truncated = false;

        try (InputStream in = source.openRead(path)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] chunk = new byte[8192];
            int remaining = limit;
            while (remaining > 0) {
                int n = in.read(chunk, 0, Math.min(chunk.length, remaining));
                if (n < 0) {
                    break;
                }
                buf.write(chunk, 0, n);
                remaining -= n;
            }
            if (remaining == 0 && in.read() >= 0) {
                truncated = true;
            }
            bytes = buf.toByteArray();
        }

        String content = new String(bytes, StandardCharsets.UTF_8);
        if (truncated) {
            // 截断处切开的多字节字符解码为替换符，去掉
            int end = content.length();
            while (end > 0 && content.charAt(end - 1) == '\uFFFD') {
                end--;
            }
            content = content.substring(0, end);
        }
        if (content.length() > 0 && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        content = content.trim();

        return content.isEmpty() ? null : new Part(path, content, truncated);
    }

    /**
     * 一段规范（只读；包内使用）
     */
    static final class Part {
        private final String path;
        private final String content;
        private final boolean truncated;

        Part(String path, String content, boolean truncated) {
            this.path = Objects.requireNonNull(path);
            this.content = Objects.requireNonNull(content);
            this.truncated = truncated;
        }

        /**
         * 工作区内的相对路径
         */
        String getPath() {
            return path;
        }

        String getContent() {
            return content;
        }

        /**
         * 读取时是否因文件过大而只读了开头一段
         */
        boolean isTruncated() {
            return truncated;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if ((o instanceof Part) == false) {
                return false;
            }
            Part that = (Part) o;
            return truncated == that.truncated && path.equals(that.path) && content.equals(that.content);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, content, truncated);
        }

        /** 转为可序列化的形式（记在提示词属性与消息 metadata 上） */
        static List<Map<String, Object>> toMaps(List<Part> parts) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Part part : parts) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("path", part.path);
                map.put("content", part.content);
                if (part.truncated) {
                    map.put("truncated", true);
                }
                list.add(map);
            }
            return list;
        }

        /** {@link #toMaps} 的逆操作（形式不对时返回 null） */
        static @Nullable List<Part> fromMaps(@Nullable Object obj) {
            if ((obj instanceof List) == false) {
                return null;
            }
            List<Part> parts = new ArrayList<>();
            for (Object item : (List<?>) obj) {
                if ((item instanceof Map) == false) {
                    return null;
                }
                Map<?, ?> map = (Map<?, ?>) item;
                Object path = map.get("path");
                Object content = map.get("content");
                if ((path instanceof String) == false || (content instanceof String) == false) {
                    return null;
                }
                parts.add(new Part((String) path, (String) content, Boolean.TRUE.equals(map.get("truncated"))));
            }
            return parts;
        }
    }
}
