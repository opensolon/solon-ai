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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * jdtls（Eclipse JDT Language Server）专项适配。
 *
 * <p>jdtls 与其它语言服务器最大的不同是：它不是「装上就能用」，缺少两处外部配合就会稳定
 * 产出假报错，而假报错一旦被自动注入到工具输出，模型就会去修根本不存在的问题：
 *
 * <ul>
 *   <li><b>Lombok</b>：jdtls 只认 {@code -javaagent:lombok.jar}（编辑器插件都是这么做的）。
 *       缺了它，所有 {@code @Getter/@Setter/@Builder} 生成的方法一律报
 *       “The method getXxx() is undefined for the type ...”——这是实测日志里占比最高的一类假错。</li>
 *   <li><b>数据目录</b>：jdtls 启动器默认把工作区数据放在
 *       {@code <cache>/jdtls/jdtls-sha1(basename(cwd))}，只对目录<i>名</i>做哈希。
 *       于是两份同名 checkout（{@code ~/a/demo} 与 {@code ~/b/demo}）会共用同一份索引互相污染，
 *       且旧会话的残留状态会跨进程延续，表现为「明明加了这个方法，它还说不存在」。</li>
 * </ul>
 *
 * <p>加固只作用于运行时命令行，不写回任何配置文件：settings 里不该出现本机绝对路径。
 * 用户如果自己指定了 {@code -data} 或任何 {@code -javaagent}，一律尊重其配置、不再插手。
 *
 * @author noear
 * @since 4.1
 */
public final class JdtlsSupport {
    private static final Logger LOG = LoggerFactory.getLogger(JdtlsSupport.class);

    /**
     * 显式指定 lombok jar（留空则自动探测；填 {@code none} 可彻底关闭注入）
     */
    private static final String LOMBOK_JAR_PROP = "lsp.java.lombokJar";

    /**
     * 关闭本类的全部加固（出问题时的逃生开关）
     */
    private static final String HARDEN_PROP = "lsp.java.harden";

    /**
     * 可安全用于现代 JDK（17/21+）的 lombok 下限。
     *
     * <p>javaagent 的 premain 抛异常会让整个 JVM 启动失败，因此宁可不注入也不能注入一个
     * 与运行时不兼容的老版本：本机 m2 里躺着 1.16.x 是很常见的事。
     */
    private static final int[] LOMBOK_MIN_VERSION = {1, 18, 30};

    /**
     * 探测结果缓存：null 表示尚未探测，空串表示探测过但没有可用 jar
     */
    private static volatile String lombokJarCache;

    private JdtlsSupport() {
    }

    /**
     * 命令是否为 jdtls（按可执行文件名判定，容忍绝对路径与 .cmd/.bat 后缀）
     */
    public static boolean isJdtls(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String name = command.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.toLowerCase().startsWith("jdtls");
    }

    /**
     * 为 jdtls 补齐 Lombok javaagent 与按工作区隔离的数据目录。
     *
     * <p>非 jdtls 命令、或已被用户显式配置过的部分，原样返回（幂等，可重复调用）。
     *
     * @param command   原始命令
     * @param workspace 工作区根目录（决定数据目录的隔离粒度）
     * @return 加固后的命令；与入参内容一致时返回原数组
     */
    public static String[] harden(String[] command, String workspace) {
        if (command == null || command.length == 0 || isJdtls(command[0]) == false) {
            return command;
        }
        if ("false".equalsIgnoreCase(System.getProperty(HARDEN_PROP))) {
            return command;
        }

        List<String> args = new ArrayList<>(Arrays.asList(command));
        boolean changed = false;

        if (hasArgStartsWith(args, "--jvm-arg=-javaagent") == false) {
            String lombokJar = findLombokJar();
            if (lombokJar != null) {
                //插在第一个位置之后：jdtls 启动器要求 --jvm-arg 出现在被透传的 eclipse 参数之前
                args.add(1, "--jvm-arg=-javaagent:" + lombokJar);
                changed = true;
            }
        }

        if (args.contains("-data") == false && workspace != null) {
            String dataDir = resolveDataDir(workspace);
            if (dataDir != null) {
                args.add("-data");
                args.add(dataDir);
                changed = true;
            }
        }

        if (changed == false) {
            return command;
        }
        return args.toArray(new String[0]);
    }

    /**
     * 数据目录：{@code <用户缓存>/solon-lsp/jdtls-<sha1(工作区绝对路径)>}。
     *
     * <p>用绝对路径而非目录名做哈希，是为了让不同 checkout 各自持有独立索引；放在用户缓存
     * 目录而非仓库内，是为了不往用户工程里塞几百 MB 的 Eclipse 元数据。
     */
    static String resolveDataDir(String workspace) {
        try {
            File ws = new File(workspace).getAbsoluteFile().getCanonicalFile();
            File base = new File(userCacheDir(), "solon-lsp");
            return new File(base, "jdtls-" + sha1(ws.getPath())).getPath();
        } catch (Exception e) {
            LOG.debug("Failed to resolve jdtls data dir for {}: {}", workspace, e.getMessage());
            return null;
        }
    }

    private static File userCacheDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (home == null || home.isEmpty()) {
            return new File(System.getProperty("java.io.tmpdir"));
        }
        if (os.contains("mac")) {
            return new File(home, "Library/Caches");
        }
        if (os.contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            if (appData != null && appData.isEmpty() == false) {
                return new File(appData);
            }
            return new File(home, "AppData/Local");
        }
        return new File(home, ".cache");
    }

    /**
     * 定位 lombok jar：显式配置优先，其次取本机 maven 仓库里满足下限的最高版本。
     *
     * <p>只扫目录、不下载：探测本身必须是零网络、可离线的，找不到就安静降级（Java 诊断
     * 仍然可用，只是 Lombok 生成的方法会报假错——这正是我们要消掉的那类噪声，故会给出提示）。
     */
    static String findLombokJar() {
        String cached = lombokJarCache;
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String resolved = probeLombokJar();
        lombokJarCache = (resolved == null) ? "" : resolved;
        return resolved;
    }

    private static String probeLombokJar() {
        String configured = System.getProperty(LOMBOK_JAR_PROP);
        if (configured == null || configured.isEmpty()) {
            configured = System.getenv("LSP_JAVA_LOMBOK_JAR");
        }
        if (configured != null && configured.isEmpty() == false) {
            if ("none".equalsIgnoreCase(configured)) {
                return null;
            }
            File jar = new File(configured);
            if (jar.isFile()) {
                LOG.info("[LSP] jdtls lombok agent -> {} (configured)", jar.getPath());
                return jar.getPath();
            }
            LOG.warn("[LSP] configured lombok jar not found: {}", configured);
            return null;
        }

        File best = null;
        int[] bestVersion = null;
        for (File dir : lombokRepoDirs()) {
            File[] versions = dir.listFiles();
            if (versions == null) {
                continue;
            }
            for (File versionDir : versions) {
                if (versionDir.isDirectory() == false) {
                    continue;
                }
                int[] version = parseVersion(versionDir.getName());
                if (version == null || compareVersion(version, LOMBOK_MIN_VERSION) < 0) {
                    continue;
                }
                File jar = new File(versionDir, "lombok-" + versionDir.getName() + ".jar");
                if (jar.isFile() == false) {
                    continue;
                }
                if (bestVersion == null || compareVersion(version, bestVersion) > 0) {
                    best = jar;
                    bestVersion = version;
                }
            }
        }

        if (best == null) {
            LOG.info("[LSP] no usable lombok jar found (>= {}); jdtls may report Lombok-generated "
                    + "getters/setters as undefined. Set -D{}=<path> to fix", versionText(LOMBOK_MIN_VERSION), LOMBOK_JAR_PROP);
            return null;
        }

        LOG.info("[LSP] jdtls lombok agent -> {}", best.getPath());
        return best.getPath();
    }

    private static List<File> lombokRepoDirs() {
        List<File> dirs = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            return dirs;
        }

        String localRepo = System.getProperty("maven.repo.local");
        if (localRepo != null && localRepo.isEmpty() == false) {
            dirs.add(new File(localRepo, "org/projectlombok/lombok"));
        }
        dirs.add(new File(home, ".m2/repository/org/projectlombok/lombok"));
        return dirs;
    }

    private static boolean hasArgStartsWith(List<String> args, String prefix) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 {@code 1.18.30} 形态的版本号；带后缀（如 {@code 1.18.30-rc1}）或非法值返回 null
     */
    static int[] parseVersion(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] parts = text.split("\\.");
        int[] version = new int[3];
        for (int i = 0; i < 3; i++) {
            if (i >= parts.length) {
                version[i] = 0;
                continue;
            }
            try {
                version[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                //预发布/快照版本不参与自动选择：稳定性优先
                return null;
            }
        }
        return version;
    }

    static int compareVersion(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i] ? 1 : -1;
            }
        }
        return 0;
    }

    private static String versionText(int[] version) {
        return version[0] + "." + version[1] + "." + version[2];
    }

    private static String sha1(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 清空探测缓存（配置变更或测试用）
     */
    static void resetCache() {
        lombokJarCache = null;
    }
}
